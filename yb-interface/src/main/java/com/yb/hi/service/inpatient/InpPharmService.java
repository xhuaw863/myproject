package com.yb.hi.service.inpatient;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.service.OrgAccessGuard;
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
 * 住院药师审核服务(T35): 药品类医嘱(pharm_audit_status=1)的审方工作台。
 * 流程: 医生开立药品医嘱 → 进入待审队列 → 药师签名批量通过(→2)/驳回留原因(→3)
 *       → 护士审核(InpOrderExecService.batchAudit)前强制校验药审通过, 未通过整体拒绝;
 *       驳回后医生可复制修改重新开立(新医嘱重新进入待审)。
 * 说明:
 * 1) JOIN 分页查询走 JdbcTemplate 手写 SQL(项目惯例: 原生 SQL 显式带 tenant_id 与 deleted=0);
 * 2) 机构隔离: 医嘱归属机构(org_id)须与当前登录机构一致(医共体各院药房独立审方);
 * 3) 药师身份取 his_staff.id(未关联职工的账号不能执行审方签名)。
 */
@Slf4j
@Service
public class InpPharmService {

    /** 药审状态: 0无需 1待审 2通过 3驳回 */
    public static final int PHARM_NONE = 0;
    public static final int PHARM_PENDING = 1;
    public static final int PHARM_PASSED = 2;
    public static final int PHARM_REJECTED = 3;

    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public InpPharmService(JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /** 当前登录机构必读校验: 药师须归属机构才能审方(平台超管按当前机构上下文) */
    private Long requireCurrentOrg() {
        Long orgId = guard.currentOrgId();
        if (orgId == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问药师审核数据");
        }
        return orgId;
    }

    /* ================= 待审队列 ================= */

    /**
     * 待审医嘱队列(pharm_audit_status=1 的药品类医嘱): JOIN 就诊/患者/床位/药品目录/开嘱医生,
     * 开立时间正序(先开先审); 支持科室过滤(deptId=就诊科室)与药品关键词模糊匹配, 分页。
     */
    public Map<String, Object> getReviewQueue(Long deptId, String keyword, int page, int size) {
        Long orgId = requireCurrentOrg();
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : (size > 200 ? 200 : size);

        StringBuilder where = new StringBuilder(
                " FROM his_inp_order o"
                        + " JOIN his_inp_visit v ON o.inp_visit_id = v.id AND v.deleted = 0 AND v.tenant_id = ?"
                        + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                        + " LEFT JOIN his_drug_catalog d ON o.drug_id = d.id AND d.deleted = 0"
                        + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                        + " LEFT JOIN his_staff ds ON o.doctor_id = ds.id AND ds.deleted = 0"
                        + " LEFT JOIN his_dept dep ON v.dept_id = dep.id AND dep.deleted = 0"
                        + " WHERE o.pharm_audit_status = " + PHARM_PENDING
                        + " AND o.deleted = 0 AND o.tenant_id = ? AND o.org_id = ?");
        List<Object> args = new ArrayList<>(Arrays.asList(tenantId(), tenantId(), orgId));
        if (deptId != null) {
            where.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (d.generic_name LIKE ? OR o.order_content LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());

        String dataSql = "SELECT o.id, o.inp_visit_id AS inpVisitId, o.order_type AS orderType,"
                + " o.order_category AS orderCategory, o.order_content AS orderContent,"
                + " o.spec, o.dosage, o.dosage_unit AS dosageUnit, o.usage_code AS usageCode,"
                + " o.freq_code AS freqCode, o.quantity, o.drug_id AS drugId,"
                + " d.generic_name AS drugName, d.spec AS drugSpec, d.drug_class AS drugClass,"
                + " v.inp_no AS inpNo, v.dept_id AS deptId, dep.dept_name AS deptName,"
                + " v.admit_diag AS admitDiag,"
                + " p.name AS patientName, p.gender, p.age,"
                + " b.bed_no AS bedNo, b.room_no AS roomNo,"
                + " ds.staff_name AS doctorName,"
                + " DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i:%s') AS createTime"
                + where + " ORDER BY o.create_time ASC, o.id ASC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0L : total);
        out.put("rows", rows);
        return out;
    }

    /* ================= 审核 / 驳回 ================= */

    /**
     * 批量通过: 乐观更新 pharm_audit_status 1->2 并记录审核药师与时间,
     * 同时清空历史驳回原因(新审核周期); 仅待审态可更新, 部分已被他人处理时返回实际生效行数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchAudit(List<Long> orderIds, Long pharmacistId) {
        if (CollectionUtils.isEmpty(orderIds)) {
            throw new BizException(400, "请选择要审核的医嘱");
        }
        if (pharmacistId == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行药师审核签名");
        }
        Long orgId = requireCurrentOrg();
        String ids = joinIds(orderIds);
        if (ids.isEmpty()) {
            throw new BizException(400, "请选择要审核的医嘱");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_order SET pharm_audit_status = " + PHARM_PASSED
                        + ", pharm_audit_id = ?, pharm_audit_time = NOW(), pharm_reject_reason = NULL"
                        + " WHERE id IN (" + ids + ") AND pharm_audit_status = " + PHARM_PENDING
                        + " AND deleted = 0 AND tenant_id = ? AND org_id = ?",
                pharmacistId, tenantId(), orgId);
        log.info("住院药师批量通过: pharmacistId={}, 申请{}条, 实际{}条", pharmacistId, orderIds.size(), affected);
        return affected;
    }

    /** 驳回: 乐观更新 pharm_audit_status 1->3 并记录驳回原因; 不在待审态报错。 */
    @Transactional(rollbackFor = Exception.class)
    public void rejectOrder(Long orderId, Long pharmacistId, String reason) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        if (pharmacistId == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行药师审核签名");
        }
        String cause = reason == null ? null : reason.trim();
        if (cause == null || cause.isEmpty()) {
            throw new BizException(400, "驳回原因不能为空");
        }
        if (cause.length() > 500) {
            cause = cause.substring(0, 500);
        }
        Long orgId = requireCurrentOrg();
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_order SET pharm_audit_status = " + PHARM_REJECTED
                        + ", pharm_audit_id = ?, pharm_audit_time = NOW(), pharm_reject_reason = ?"
                        + " WHERE id = ? AND pharm_audit_status = " + PHARM_PENDING
                        + " AND deleted = 0 AND tenant_id = ? AND org_id = ?",
                pharmacistId, cause, orderId, tenantId(), orgId);
        if (affected == 0) {
            throw new BizException("该医嘱不在待审状态, 可能已被其他药师处理, 请刷新后重试");
        }
        log.info("住院药师驳回: orderId={}, pharmacistId={}, reason={}", orderId, pharmacistId, cause);
    }

    /* ================= 统计 / 历史 ================= */

    /** 工作台统计: 待审数 / 今日已审(通过) / 今日驳回。 */
    public Map<String, Object> getStats() {
        Long orgId = requireCurrentOrg();
        String base = " FROM his_inp_order o WHERE o.deleted = 0 AND o.tenant_id = ? AND o.org_id = ?";
        Object[] baseArgs = {tenantId(), orgId};
        Long pending = count(base + " AND o.pharm_audit_status = " + PHARM_PENDING, baseArgs);
        String today = " AND o.pharm_audit_status = ? AND o.pharm_audit_time >= CURDATE()"
                + " AND o.pharm_audit_time < CURDATE() + INTERVAL 1 DAY";
        Long auditedToday = count(base + today, append(baseArgs, PHARM_PASSED));
        Long rejectedToday = count(base + today, append(baseArgs, PHARM_REJECTED));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending", pending == null ? 0L : pending);
        out.put("auditedToday", auditedToday == null ? 0L : auditedToday);
        out.put("rejectedToday", rejectedToday == null ? 0L : rejectedToday);
        return out;
    }

    /** 驳回历史(按就诊): 该就诊全部被驳回的药品医嘱, 时间倒序, 供医生站与药房回查。 */
    public List<Map<String, Object>> getRejectHistory(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        Long orgId = requireCurrentOrg();
        return jdbcTemplate.queryForList(
                "SELECT o.id, o.order_content AS orderContent, o.spec, o.dosage, o.dosage_unit AS dosageUnit,"
                        + " IFNULL(d.generic_name, o.order_content) AS drugName, d.spec AS drugSpec,"
                        + " o.pharm_reject_reason AS rejectReason,"
                        + " ph.staff_name AS pharmacistName,"
                        + " DATE_FORMAT(o.pharm_audit_time, '%Y-%m-%d %H:%i:%s') AS pharmAuditTime"
                        + " FROM his_inp_order o"
                        + " LEFT JOIN his_drug_catalog d ON o.drug_id = d.id AND d.deleted = 0"
                        + " LEFT JOIN his_staff ph ON o.pharm_audit_id = ph.id AND ph.deleted = 0"
                        + " WHERE o.inp_visit_id = ? AND o.pharm_audit_status = " + PHARM_REJECTED
                        + " AND o.deleted = 0 AND o.tenant_id = ? AND o.org_id = ?"
                        + " ORDER BY o.pharm_audit_time DESC, o.id DESC",
                visitId, tenantId(), orgId);
    }

    /* ================= 内部工具 ================= */

    /** id 列表拼接为 IN 字面量(Long 过滤后 toString, 无注入面); 全空返回空串 */
    private static String joinIds(List<Long> ids) {
        if (ids == null) {
            return "";
        }
        return ids.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(","));
    }

    private Long count(String sqlSuffix, Object[] args) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*)" + sqlSuffix, Long.class, args);
    }

    private static Object[] append(Object[] base, Object extra) {
        Object[] out = Arrays.copyOf(base, base.length + 1);
        out[base.length] = extra;
        return out;
    }
}
