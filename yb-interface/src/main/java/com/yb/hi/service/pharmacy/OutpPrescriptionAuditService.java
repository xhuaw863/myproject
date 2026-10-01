package com.yb.hi.service.pharmacy;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 门诊处方审核服务(P2): 复用住院药师审核(InpPharmService)范式, 作用于 his_prescription 的审核标志位。
 * 流程: 医生开方(含药品→audit_status=1) → 待审队列 → 药师批量通过(1→2)/驳回留原因(1→3) → 医生可重审(3→1);
 *       自动审核走 RationalMedicationService(Mock): block→3、warn/pass→2。
 * 说明:
 * 1) his_prescription 为租户级表(无 org_id), 原生 SQL 显式带 tenant_id 与 deleted=0;
 * 2) 审核是 dispense_status 之外的并行标志位, 不改发药状态机; 防重复用乐观 UPDATE ... WHERE audit_status=1 判 affected;
 * 3) 审核人取登录用户真实姓名(与发药 dispense_by 口径一致), 不强制职工档案。
 */
@Slf4j
@Service
public class OutpPrescriptionAuditService {

    /** 处方审核状态: 0无需 1待审 2通过 3驳回 */
    public static final int AUDIT_NONE = 0;
    public static final int AUDIT_PENDING = 1;
    public static final int AUDIT_PASSED = 2;
    public static final int AUDIT_REJECTED = 3;

    /** 常见驳回原因(本地枚举, 前端下拉可选项) */
    private static final List<String> REJECT_REASONS = Arrays.asList(
            "用法用量不适宜", "诊断与用药不符", "存在配伍禁忌", "重复用药",
            "超剂量处方", "需皮试未做", "药品禁忌症", "处方信息不完整");

    private final JdbcTemplate jdbcTemplate;
    private final RationalMedicationService rationalMedicationService;

    public OutpPrescriptionAuditService(JdbcTemplate jdbcTemplate, RationalMedicationService rationalMedicationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.rationalMedicationService = rationalMedicationService;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    public List<String> rejectReasons() {
        return REJECT_REASONS;
    }

    /* ================= 待审队列 ================= */

    /** 处方队列(audit_status=指定态 且未发药未作废): 开立时间正序; 支持科室/患者/处方号过滤与分页。auditStatus 仅允许 1待审/3已驳回。 */
    public Map<String, Object> pendingPage(Long deptId, String keyword, Integer auditStatus, int page, int size) {
        int st = (auditStatus != null && auditStatus == AUDIT_REJECTED) ? AUDIT_REJECTED : AUDIT_PENDING;
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : (size > 200 ? 200 : size);
        StringBuilder where = new StringBuilder(" WHERE p.audit_status = " + st
                + " AND p.dispense_status = 0 AND p.status > 0 AND p.deleted = 0 AND p.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (deptId != null) {
            where.append(" AND p.dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (p.patient_name LIKE ? OR p.rx_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        String from = " FROM his_prescription p";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());

        String dataSql = "SELECT p.id, p.rx_no AS rxNo, p.visit_id AS visitId, p.patient_id AS patientId,"
                + " p.patient_name AS patientName, p.dept_id AS deptId, p.dept_name AS deptName,"
                + " p.dr_name AS doctorName, p.rx_type AS rxType, p.diag_name AS diagName, p.total_amount AS totalAmount,"
                + " p.pharmacy_id AS pharmacyId, p.audit_status AS auditStatus, p.reject_reason AS rejectReason,"
                + " DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i:%s') AS prescribeTime,"
                + " (SELECT COUNT(*) FROM his_prescription_item pi WHERE pi.prescription_id = p.id AND pi.deleted = 0) AS itemCount"
                + from + where + " ORDER BY p.create_time ASC, p.id ASC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0L : total);
        out.put("rows", rows);
        return out;
    }

    /** 审核详情: 处方主信息 + 药品明细 + 合理用药自动审查结果。 */
    public Map<String, Object> detail(Long prescriptionId) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.id, p.rx_no AS rxNo, p.visit_id AS visitId, p.patient_id AS patientId, p.patient_name AS patientName,"
                        + " p.dept_id AS deptId, p.dept_name AS deptName, p.dr_name AS doctorName, p.rx_type AS rxType,"
                        + " p.diag_name AS diagName, p.total_amount AS totalAmount, p.audit_status AS auditStatus,"
                        + " p.audit_src AS auditSrc, p.audit_by AS auditBy, p.reject_reason AS rejectReason, p.pharmacy_id AS pharmacyId,"
                        + " DATE_FORMAT(p.audit_time, '%Y-%m-%d %H:%i:%s') AS auditTime,"
                        + " DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i:%s') AS prescribeTime"
                        + " FROM his_prescription p WHERE p.id = ? AND p.tenant_id = ? AND p.deleted = 0",
                prescriptionId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "处方不存在");
        }
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT pi.drug_id AS drugId, pi.item_name AS drugName, pi.spec, pi.quantity, pi.dosage,"
                        + " pi.dosage_unit AS dosageUnit, pi.usage_method AS usageName, pi.frequency, pi.days"
                        + " FROM his_prescription_item pi WHERE pi.prescription_id = ? AND pi.tenant_id = ? AND pi.deleted = 0 ORDER BY pi.id",
                prescriptionId, tenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prescription", rows.get(0));
        out.put("items", items);
        out.put("rational", rationalMedicationService.review(prescriptionId));
        return out;
    }

    /* ================= 审核 / 驳回 / 重审 ================= */

    /** 批量人工通过: 乐观 UPDATE audit_status 1→2, 记审核来源/人/时间并清空历史驳回原因; 返回实际生效行数。 */
    @Transactional(rollbackFor = Exception.class)
    public int batchAudit(List<Long> prescriptionIds, String auditor) {
        if (CollectionUtils.isEmpty(prescriptionIds)) {
            throw new BizException(400, "请选择要审核的处方");
        }
        String ids = joinIds(prescriptionIds);
        if (ids.isEmpty()) {
            throw new BizException(400, "请选择要审核的处方");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_prescription SET audit_status = " + AUDIT_PASSED + ", audit_src = 'manual',"
                        + " audit_by = ?, audit_time = NOW(), reject_reason = NULL, update_by = ?, update_time = NOW()"
                        + " WHERE id IN (" + ids + ") AND audit_status = " + AUDIT_PENDING
                        + " AND deleted = 0 AND tenant_id = ?",
                auditor, auditor, tenantId());
        log.info("门诊药师批量通过: auditor={}, 申请{}条, 实际{}条", auditor, prescriptionIds.size(), affected);
        return affected;
    }

    /** 驳回: 乐观 UPDATE audit_status 1→3 并记录驳回原因; 不在待审态报错。 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long prescriptionId, String reason, String auditor) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "驳回原因不能为空");
        }
        String cause = reason.trim();
        if (cause.length() > 500) {
            cause = cause.substring(0, 500);
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_prescription SET audit_status = " + AUDIT_REJECTED + ", audit_src = 'manual',"
                        + " audit_by = ?, audit_time = NOW(), reject_reason = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND audit_status = " + AUDIT_PENDING + " AND deleted = 0 AND tenant_id = ?",
                auditor, cause, auditor, prescriptionId, tenantId());
        if (affected == 0) {
            throw new BizException("该处方不在待审状态, 可能已被其他药师处理, 请刷新后重试");
        }
        log.info("门诊药师驳回: prescriptionId={}, auditor={}, reason={}", prescriptionId, auditor, cause);
    }

    /** 重审: 将被驳回处方(3)重新置回待审(1), 清空驳回原因, 供医生修改后或药师复核。 */
    @Transactional(rollbackFor = Exception.class)
    public void reAudit(Long prescriptionId, String auditor) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_prescription SET audit_status = " + AUDIT_PENDING + ", reject_reason = NULL,"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND audit_status = " + AUDIT_REJECTED + " AND deleted = 0 AND tenant_id = ?",
                auditor, prescriptionId, tenantId());
        if (affected == 0) {
            throw new BizException("该处方不是已驳回状态, 无法重审");
        }
        log.info("门诊处方重审(驳回→待审): prescriptionId={}, auditor={}", prescriptionId, auditor);
    }

    /**
     * 自动审核(合理用药 Mock): block→驳回(3)并落审查意见, warn/pass→通过(2)。
     * 仅待审态执行; 返回审核结果(level/状态)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> autoAudit(Long prescriptionId, String auditor) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        Map<String, Object> review = rationalMedicationService.review(prescriptionId);
        String level = String.valueOf(review.get("level"));
        @SuppressWarnings("unchecked")
        List<String> blocks = (List<String>) review.getOrDefault("blocks", new ArrayList<>());
        @SuppressWarnings("unchecked")
        List<String> warns = (List<String>) review.getOrDefault("warns", new ArrayList<>());
        int target = "block".equals(level) ? AUDIT_REJECTED : AUDIT_PASSED;
        String reason = "block".equals(level) ? trim500("自动审核拦截: " + String.join("; ", blocks)) : null;
        int affected = jdbcTemplate.update(
                "UPDATE his_prescription SET audit_status = ?, audit_src = 'auto', audit_by = ?, audit_time = NOW(),"
                        + " reject_reason = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND audit_status = " + AUDIT_PENDING + " AND deleted = 0 AND tenant_id = ?",
                target, auditor, reason, auditor, prescriptionId, tenantId());
        if (affected == 0) {
            throw new BizException("该处方不在待审状态, 自动审核未执行");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prescriptionId", prescriptionId);
        out.put("level", level);
        out.put("auditStatus", target);
        out.put("blocks", blocks);
        out.put("warns", warns);
        log.info("门诊处方自动审核: prescriptionId={}, level={}, auditor={}", prescriptionId, level, auditor);
        return out;
    }

    /* ================= 统计 ================= */

    /** 工作台统计: 待审数 / 今日已审(通过) / 今日驳回。 */
    public Map<String, Object> stats() {
        long tid = tenantId();
        String base = " FROM his_prescription p WHERE p.deleted = 0 AND p.tenant_id = ?";
        Long pending = count(base + " AND p.audit_status = " + AUDIT_PENDING, tid);
        Long passedToday = count(base + " AND p.audit_status = " + AUDIT_PASSED
                + " AND p.audit_time >= CURDATE() AND p.audit_time < CURDATE() + INTERVAL 1 DAY", tid);
        Long rejectedToday = count(base + " AND p.audit_status = " + AUDIT_REJECTED
                + " AND p.audit_time >= CURDATE() AND p.audit_time < CURDATE() + INTERVAL 1 DAY", tid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending", pending == null ? 0L : pending);
        out.put("passedToday", passedToday == null ? 0L : passedToday);
        out.put("rejectedToday", rejectedToday == null ? 0L : rejectedToday);
        return out;
    }

    /* ================= 内部工具 ================= */

    private static String joinIds(List<Long> ids) {
        if (ids == null) {
            return "";
        }
        return ids.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(","));
    }

    private Long count(String sqlSuffix, long tid) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*)" + sqlSuffix, Long.class, tid);
    }

    private static String trim500(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
