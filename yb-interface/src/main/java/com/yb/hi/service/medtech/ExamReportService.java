package com.yb.hi.service.medtech;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.medtech.HisExamReport;
import com.yb.hi.entity.medtech.HisExamResultItem;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.medtech.HisExamReportMapper;
import com.yb.hi.mapper.medtech.HisExamResultItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检查/检验报告服务(报告书写工作台: 草稿->提交->审核双签, 发布回写医嘱执行状态)。
 * 口径:
 * 1) 报告状态: 0草稿 -> 1已报告(待审) -> 2已审核(发布); 审核不通过退回 1->0 继续书写;
 * 2) 单号: BG + yyyyMMdd + 4位序号, synchronized 生成 + DB 回读当日最大序号兜底重启防撞号;
 * 3) 检验类(lab)报告持结果明细子项(整体替换式保存), 异常标志后端按参考范围复算 0正常/1偏高/2偏低,
 *    危急值 3/4 在提交送审时由 CriticalValueService 命中规则升级并生成危急值记录;
 * 4) 审核通过回写 his_order.exec_status=2(医技执行完成), 报告查询以 his_exam_report 落库数据为准。
 */
@Slf4j
@Service
public class ExamReportService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 报告单号前缀 */
    private static final String REPORT_PREFIX = "BG";

    private final HisExamReportMapper reportMapper;
    private final HisExamResultItemMapper resultItemMapper;
    private final CriticalValueService criticalValueService;
    private final JdbcTemplate jdbcTemplate;

    /** 单号内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public ExamReportService(HisExamReportMapper reportMapper, HisExamResultItemMapper resultItemMapper,
                             CriticalValueService criticalValueService, JdbcTemplate jdbcTemplate) {
        this.reportMapper = reportMapper;
        this.resultItemMapper = resultItemMapper;
        this.criticalValueService = criticalValueService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 草稿 ================= */

    /**
     * 创建报告草稿: 校验医嘱(未作废) -> 幂等(该医嘱已有报告直接返回最新一份, 防重复建单)
     * -> 生成 BG 单号, report_type 空时按医嘱类型推导(检验->lab, 其余->exam)。
     */
    public HisExamReport createDraft(Long orderId, String reportType) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        Long orgId = requireOrgId();
        long tid = tenantId();
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT id, patient_id, order_type, status FROM his_order"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0", orderId, tid);
        if (orders.isEmpty()) {
            throw new BizException(400, "医嘱单不存在");
        }
        Map<String, Object> order = orders.get(0);
        Number status = (Number) order.get("status");
        if (status != null && status.intValue() < 0) {
            throw new BizException("该医嘱单已作废, 无法创建报告");
        }

        // 幂等: 同一医嘱已有未删除报告(草稿/待审/已发布)直接返回最新一份, 前端继续编辑/查看
        HisExamReport existed = reportMapper.selectOne(Wrappers.<HisExamReport>lambdaQuery()
                .eq(HisExamReport::getOrderId, orderId)
                .orderByDesc(HisExamReport::getId)
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }

        String type = normalizeReportType(reportType, str(order.get("order_type")));
        HisExamReport r = new HisExamReport();
        r.setOrgId(orgId);
        r.setReportNo(nextReportNo());
        r.setOrderId(orderId);
        r.setPatientId(toLong(order.get("patient_id")));
        r.setReportType(type);
        r.setStatus(0);
        r.setCriticalFlag(0);
        reportMapper.insert(r);
        log.info("报告草稿创建: reportNo={}, orderId={}, type={}", r.getReportNo(), orderId, type);
        return r;
    }

    /** 报告类型归一: 入参优先(中文口语值兼容: 检验->lab, 检查->exam, 否则按原值小写直存);
     *  空时按医嘱类型推导(检验->lab, 其余->exam)。不归一会使 isLab 判断失效, 结果明细被静默丢弃、危急值永不命中 */
    private static String normalizeReportType(String reportType, String orderType) {
        if (StringUtils.hasText(reportType)) {
            String t = reportType.trim();
            if ("检验".equals(t) || "lab".equalsIgnoreCase(t)) {
                return "lab";
            }
            if ("检查".equals(t) || "exam".equalsIgnoreCase(t)) {
                return "exam";
            }
            return t.toLowerCase();
        }
        return "检验".equals(orderType) ? "lab" : "exam";
    }

    /**
     * 保存草稿: 仅草稿(0)状态可保存(退回 1->0 后可继续编辑);
     * 检验类(lab)时结果明细整体替换(旧子项逻辑删除 -> 逐行插入), 异常标志后端按参考范围复算 0/1/2。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamReport saveDraft(Long reportId, String findings, String conclusion, List<Map<String, Object>> resultItems) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisExamReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(400, "报告不存在");
        }
        if (report.getStatus() == null || report.getStatus() != 0) {
            throw new BizException("仅草稿状态可保存(当前状态: " + statusLabel(report.getStatus()) + ")");
        }
        long tid = tenantId();
        jdbcTemplate.update(
                "UPDATE his_exam_report SET findings = ?, conclusion = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                findings, conclusion, currentUserName(), reportId, tid);

        if (isLab(report.getReportType()) && resultItems != null) {
            // 整体替换: 旧子项逻辑删除(保留审计), 新行重新插入
            jdbcTemplate.update(
                    "UPDATE his_exam_result_item SET deleted = 1, update_time = NOW(), update_by = ?"
                            + " WHERE report_id = ? AND tenant_id = ? AND deleted = 0",
                    currentUserName(), reportId, tid);
            int saved = 0;
            for (Map<String, Object> row : resultItems) {
                String itemCode = str(row.get("itemCode"));
                String itemName = str(row.get("itemName"));
                if (!StringUtils.hasText(itemCode) && !StringUtils.hasText(itemName)) {
                    continue;
                }
                HisExamResultItem item = new HisExamResultItem();
                item.setReportId(reportId);
                item.setItemCode(StringUtils.hasText(itemCode) ? itemCode.trim()
                        : (itemName == null ? "" : itemName.trim()));
                item.setItemName(StringUtils.hasText(itemName) ? itemName.trim() : item.getItemCode());
                item.setResultValue(str(row.get("resultValue")));
                item.setResultUnit(str(row.get("resultUnit")));
                BigDecimal low = toBd(row.get("refRangeLow"));
                BigDecimal high = toBd(row.get("refRangeHigh"));
                item.setRefRangeLow(low);
                item.setRefRangeHigh(high);
                item.setAbnormalFlag(calcAbnormal(toBd(row.get("resultValue")), low, high));
                item.setRemark(str(row.get("remark")));
                resultItemMapper.insert(item);
                saved++;
            }
            log.info("报告草稿保存: reportId={}, 结果明细{}项", reportId, saved);
        } else {
            log.info("报告草稿保存: reportId={}", reportId);
        }
        // 注意: 本方法在事务中, jdbcTemplate 更新后二次 selectById 会命中 MyBatis 一级缓存返回更新前旧对象,
        // 这里直接回填内存对象返回(与库中结果一致), 避免脏读。
        report.setFindings(findings);
        report.setConclusion(conclusion);
        return report;
    }

    /** 按参考范围复算基本异常标志: 0正常 1偏高 2偏低(危急值 3/4 提交送审时由规则升级) */
    static int calcAbnormal(BigDecimal value, BigDecimal low, BigDecimal high) {
        if (value == null) {
            return 0;
        }
        if (high != null && value.compareTo(high) > 0) {
            return 1;
        }
        if (low != null && value.compareTo(low) < 0) {
            return 2;
        }
        return 0;
    }

    /* ================= 提交送审 ================= */

    /**
     * 提交审核: 乐观锁 status 0->1, 记录报告医师与报告时间; 随即自动检测危急值
     * (命中阈值生成危急值记录 + 置报告 critical_flag=1 + 结果项标志升级 3偏高危急/4偏低危急)。
     * 返回报告快照 + 本次新生成的危急值列表, 前端据此弹红色警告框。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitForReview(Long reportId, Long doctorId) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_exam_report SET status = 1, report_doctor_id = ?, report_time = NOW(),"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                doctorId, currentUserName(), reportId, tenantId());
        if (affected == 0) {
            throw new BizException("报告不存在或状态已变更(仅草稿可提交审核)");
        }
        List<com.yb.hi.entity.medtech.HisCriticalValue> criticals = criticalValueService.checkCriticalValues(reportId);
        HisExamReport report = reportMapper.selectById(reportId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", reportId);
        result.put("reportNo", report == null ? null : report.getReportNo());
        result.put("status", report == null ? null : report.getStatus());
        result.put("criticalFlag", report == null || report.getCriticalFlag() == null ? 0 : report.getCriticalFlag());
        result.put("criticals", criticals);
        log.info("报告提交送审: reportId={}, doctorId={}, 危急值{}条", reportId, doctorId, criticals.size());
        return result;
    }

    /* ================= 审核 ================= */

    /**
     * 审核报告(双签):
     * 通过: 乐观锁 status 1->2 + 审核医师/时间, 回写 his_order.exec_status=2(医技执行完成, 报告已发布);
     * 退回: 乐观锁 status 1->0, 返回草稿继续书写(保留报告医师/报告时间提交痕迹)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamReport reviewReport(Long reportId, Long reviewDoctorId, boolean approved) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisExamReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(400, "报告不存在");
        }
        long tid = tenantId();
        if (approved) {
            int affected = jdbcTemplate.update(
                    "UPDATE his_exam_report SET status = 2, review_doctor_id = ?, review_time = NOW(),"
                            + " update_by = ?, update_time = NOW()"
                            + " WHERE id = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                    reviewDoctorId, currentUserName(), reportId, tid);
            if (affected == 0) {
                throw new BizException("报告不存在或状态已变更(仅待审报告可审核通过)");
            }
            if (report.getOrderId() != null) {
                jdbcTemplate.update(
                        "UPDATE his_order SET exec_status = 2, update_by = ?, update_time = NOW()"
                                + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                        currentUserName(), report.getOrderId(), tid);
            }
            log.info("报告审核通过: reportId={}, reviewDoctorId={}, orderId回写exec_status=2={}",
                    reportId, reviewDoctorId, report.getOrderId());
        } else {
            int affected = jdbcTemplate.update(
                    "UPDATE his_exam_report SET status = 0, update_by = ?, update_time = NOW()"
                            + " WHERE id = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                    currentUserName(), reportId, tid);
            if (affected == 0) {
                throw new BizException("报告不存在或状态已变更(仅待审报告可退回)");
            }
            log.info("报告退回重写: reportId={}, reviewDoctorId={}", reportId, reviewDoctorId);
        }
        // 注意: 本方法在事务中, jdbcTemplate 更新后二次 selectById 会命中 MyBatis 一级缓存返回旧对象,
        // 这里直接回填内存对象返回(与库中结果一致), 避免脏读。
        if (approved) {
            report.setStatus(2);
            report.setReviewDoctorId(reviewDoctorId);
            report.setReviewTime(java.time.LocalDateTime.now());
        } else {
            report.setStatus(0);
        }
        return report;
    }

    /**
     * 撤回报告(作废): 乐观锁 status 1/2 -> 3(已作废), 留痕撤回人/时间/原因。
     * 供医技端撤回已发布报告; 医生站通过 listReportsByPatient 仍可见(status=3 附 revoked)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisExamReport voidReport(Long reportId, String reason) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisExamReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(400, "报告不存在");
        }
        if (report.getStatus() != null && report.getStatus() == 3) {
            throw new BizException("该报告已作废, 无需重复撤回");
        }
        long tid = tenantId();
        Long revokeBy = currentStaffId();
        String rsn = StringUtils.hasText(reason) ? reason.trim() : null;
        int affected = jdbcTemplate.update(
                "UPDATE his_exam_report SET status = 3, revoke_by = ?, revoke_time = NOW(), revoke_reason = ?,"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status IN (1, 2) AND tenant_id = ? AND deleted = 0",
                revokeBy, rsn, currentUserName(), reportId, tid);
        if (affected == 0) {
            throw new BizException("报告不存在或状态不可撤回(仅已报告/已审核可作废)");
        }
        report.setStatus(3);
        report.setRevokeBy(revokeBy);
        report.setRevokeTime(java.time.LocalDateTime.now());
        report.setRevokeReason(rsn);
        log.info("报告撤回作废: reportId={}, revokeBy={}, reason={}", reportId, revokeBy, rsn);
        return report;
    }

    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getStaffId();
    }

    /* ================= 查询 ================= */

    /**
     * 报告分页(机构级; keyword 匹配患者姓名/报告单号/患者ID, from/to 过滤报告日期)。
     * JOIN his_patient 取患者信息, JOIN his_staff 取报告/审核医师姓名, JOIN his_order 取医嘱单号。
     */
    public IPage<Map<String, Object>> listReports(Long orgId, String reportType, Integer status,
                                                  String keyword, String from, String to, long page, long size) {
        return listReports(orgId == null ? null : java.util.Collections.singletonList(orgId),
                reportType, status, keyword, from, to, page, size);
    }

    /**
     * 报告分页(机构集合级; T2 阶段4 跨机构查看): orgIds 为该登录医生可访问的机构白名单(单机构=现状零回归,
     * 多机构=医共体成员范围放宽)。仍强制 r.tenant_id 过滤(不放行跨租户)。keyword 匹配患者姓名/报告单号/患者ID。
     */
    public IPage<Map<String, Object>> listReports(java.util.Collection<Long> orgIds, String reportType, Integer status,
                                                  String keyword, String from, String to, long page, long size) {
        if (orgIds == null || orgIds.isEmpty()) {
            throw new BizException(400, "机构范围不能为空");
        }
        long p = safePage(page);
        long s = safeSize(size);
        StringBuilder where = new StringBuilder(" WHERE r.deleted = 0 AND r.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        List<Long> scope = new ArrayList<>();
        for (Long oid : orgIds) {
            if (oid != null) { scope.add(oid); }
        }
        if (scope.isEmpty()) {
            throw new BizException(400, "机构范围不能为空");
        }
        where.append(" AND r.org_id IN (");
        for (int i = 0; i < scope.size(); i++) {
            where.append(i == 0 ? "?" : ",?");
            args.add(scope.get(i));
        }
        where.append(")");
        if (StringUtils.hasText(reportType)) {
            where.append(" AND r.report_type = ?");
            args.add(reportType.trim());
        }
        if (status != null) {
            where.append(" AND r.status = ?");
            args.add(status);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = "%" + keyword.trim() + "%";
            where.append(" AND (p.name LIKE ? OR r.report_no LIKE ? OR CAST(r.patient_id AS CHAR) LIKE ?)");
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        if (StringUtils.hasText(from)) {
            where.append(" AND DATE(IFNULL(r.report_time, r.create_time)) >= ?");
            args.add(from.trim());
        }
        if (StringUtils.hasText(to)) {
            where.append(" AND DATE(IFNULL(r.report_time, r.create_time)) <= ?");
            args.add(to.trim());
        }
        String joins = " FROM his_exam_report r"
                + " LEFT JOIN his_patient p ON p.id = r.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_staff rd ON rd.id = r.report_doctor_id AND rd.deleted = 0"
                + " LEFT JOIN his_staff rv ON rv.id = r.review_doctor_id AND rv.deleted = 0"
                + " LEFT JOIN his_order o ON o.id = r.order_id AND o.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());

        String dataSql = baseReportCols()
                + joins + where + " ORDER BY r.id DESC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());

        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /** 报告列表公共输出列(camelCase 别名, 前端直取) */
    private static String baseReportCols() {
        return "SELECT r.id, r.report_no AS reportNo, r.order_id AS orderId, r.patient_id AS patientId,"
                + " r.report_type AS reportType, r.status, r.critical_flag AS criticalFlag,"
                + " DATE_FORMAT(r.report_time, '%Y-%m-%d %H:%i:%s') AS reportTime,"
                + " DATE_FORMAT(r.review_time, '%Y-%m-%d %H:%i:%s') AS reviewTime,"
                + " DATE_FORMAT(r.create_time, '%Y-%m-%d %H:%i:%s') AS createTime,"
                + " p.name AS patientName, p.patient_no AS patientNo, p.gender_name AS genderName, p.age,"
                + " rd.staff_name AS reportDoctorName, rv.staff_name AS reviewDoctorName,"
                + " o.order_no AS orderNo, o.order_type AS orderType, o.diag_name AS diagName,"
                + " DATE_FORMAT(r.revoke_time, '%Y-%m-%d %H:%i:%s') AS revokeTime, r.revoke_reason AS revokeReason";
    }

    /** 按患者查询历史报告(含已撤回/作废 status=3, 附 revoked 标识供医生站"看见被撤回"; 每条附结果明细子项) */
    public List<Map<String, Object>> listReportsByPatient(Long patientId, String reportType) {
        if (patientId == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        StringBuilder sql = new StringBuilder(baseReportCols()
                + " FROM his_exam_report r"
                + " LEFT JOIN his_patient p ON p.id = r.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_staff rd ON rd.id = r.report_doctor_id AND rd.deleted = 0"
                + " LEFT JOIN his_staff rv ON rv.id = r.review_doctor_id AND rv.deleted = 0"
                + " LEFT JOIN his_order o ON o.id = r.order_id AND o.deleted = 0"
                + " WHERE r.deleted = 0 AND r.tenant_id = ? AND r.patient_id = ? AND r.status IN (1, 2, 3)");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(patientId);
        if (StringUtils.hasText(reportType)) {
            sql.append(" AND r.report_type = ?");
            args.add(reportType.trim());
        }
        sql.append(" ORDER BY r.id DESC LIMIT 200");
        List<Map<String, Object>> reports = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : reports) {
            Object st = r.get("status");
            r.put("revoked", st instanceof Number && ((Number) st).intValue() == 3);
            r.put("resultItems", resultItemMapper.selectList(Wrappers.<HisExamResultItem>lambdaQuery()
                    .eq(HisExamResultItem::getReportId, toLong(r.get("id")))
                    .orderByAsc(HisExamResultItem::getId)));
        }
        return reports;
    }

    /**
     * OP-D 报告趋势(14.5): 同一患者同一检验项目历史时间序列(仅取已报告/已审核),
     * 供医生站 ECharts 趋势图 + 参考范围背景带。
     */
    public List<Map<String, Object>> reportTrend(Long patientId, String itemCode) {
        if (patientId == null || !StringUtils.hasText(itemCode)) {
            throw new BizException(400, "患者ID与项目编码不能为空");
        }
        String sql = "SELECT i.id, r.id AS reportId, r.report_no AS reportNo, r.report_type AS reportType,"
                + " DATE_FORMAT(r.report_time, '%Y-%m-%d %H:%i') AS reportTime,"
                + " i.item_name AS itemName, i.result_value AS resultValue, i.result_unit AS resultUnit,"
                + " i.ref_range_low AS refLow, i.ref_range_high AS refHigh, i.abnormal_flag AS abnormalFlag"
                + " FROM his_exam_result_item i"
                + " JOIN his_exam_report r ON r.id = i.report_id AND r.deleted = 0 AND r.status IN (1, 2)"
                + " WHERE i.deleted = 0 AND i.tenant_id = ? AND r.patient_id = ? AND i.item_code = ?"
                + " ORDER BY r.report_time ASC, r.id ASC LIMIT 100";
        return jdbcTemplate.queryForList(sql, tenantId(), patientId, itemCode.trim());
    }

    /** 报告详情: 报告字段平铺 + 患者/医嘱信息 + 结果明细子项(检验类) */
    public Map<String, Object> getReportDetail(Long reportId) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisExamReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(400, "报告不存在");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", report.getId());
        detail.put("reportNo", report.getReportNo());
        detail.put("orderId", report.getOrderId());
        detail.put("patientId", report.getPatientId());
        detail.put("reportType", report.getReportType());
        detail.put("findings", report.getFindings());
        detail.put("conclusion", report.getConclusion());
        detail.put("keyImages", report.getKeyImages());
        detail.put("status", report.getStatus());
        detail.put("criticalFlag", report.getCriticalFlag());
        detail.put("reportDoctorId", report.getReportDoctorId());
        detail.put("reviewDoctorId", report.getReviewDoctorId());

        // 患者/医嘱/医师信息回填
        Map<String, Object> extra = new LinkedHashMap<>();
        if (report.getPatientId() != null) {
            List<Map<String, Object>> pts = jdbcTemplate.queryForList(
                    "SELECT name AS patientName, patient_no AS patientNo, gender_name AS genderName, age"
                            + " FROM his_patient WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    report.getPatientId(), tenantId());
            if (!pts.isEmpty()) {
                extra.putAll(pts.get(0));
            }
        }
        if (report.getOrderId() != null) {
            List<Map<String, Object>> ods = jdbcTemplate.queryForList(
                    "SELECT order_no AS orderNo, order_type AS orderType, diag_name AS diagName, dr_name AS drName"
                            + " FROM his_order WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    report.getOrderId(), tenantId());
            if (!ods.isEmpty()) {
                extra.putAll(ods.get(0));
            }
        }
        if (report.getReportDoctorId() != null) {
            extra.put("reportDoctorName", staffName(report.getReportDoctorId()));
        }
        if (report.getReviewDoctorId() != null) {
            extra.put("reviewDoctorName", staffName(report.getReviewDoctorId()));
        }
        detail.putAll(extra);
        detail.put("reportTime", report.getReportTime());
        detail.put("reviewTime", report.getReviewTime());
        detail.put("resultItems", resultItemMapper.selectList(Wrappers.<HisExamResultItem>lambdaQuery()
                .eq(HisExamResultItem::getReportId, reportId)
                .orderByAsc(HisExamResultItem::getId)));
        return detail;
    }

    private String staffName(Long staffId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT staff_name FROM his_staff WHERE id = ? AND tenant_id = ? AND deleted = 0",
                staffId, tenantId());
        return rows.isEmpty() ? null : str(rows.get(0).get("staff_name"));
    }

    /* ================= 单号 ================= */

    private String nextReportNo() {
        return generateNo();
    }

    /** 单号生成: BG+yyyyMMdd+4位序号, synchronized 唯一, 跨日重置时DB回读当日最大序号兜底重启, 占用冲突再自增 */
    private synchronized String generateNo() {
        String today = LocalDate.now().format(DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = REPORT_PREFIX + today + String.format("%04d", seqNo);
        while (reportNoExists(no)) {
            seqNo++;
            no = REPORT_PREFIX + today + String.format("%04d", seqNo);
        }
        return no;
    }

    /** 查当日已有单号最大序号(重启后防撞号; Mapper 查询经租户插件自动按当前租户过滤) */
    private int maxSeqFromDb(String today) {
        HisExamReport one = reportMapper.selectOne(Wrappers.<HisExamReport>lambdaQuery()
                .likeRight(HisExamReport::getReportNo, REPORT_PREFIX + today)
                .orderByDesc(HisExamReport::getReportNo)
                .last("LIMIT 1"));
        if (one == null || one.getReportNo() == null || one.getReportNo().length() < 10) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getReportNo().substring(one.getReportNo().length() - 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean reportNoExists(String reportNo) {
        return reportMapper.selectCount(Wrappers.<HisExamReport>lambdaQuery()
                .eq(HisExamReport::getReportNo, reportNo)) > 0;
    }

    /* ================= 辅助 ================= */

    static boolean isLab(String reportType) {
        return "lab".equalsIgnoreCase(reportType);
    }

    private static String statusLabel(Integer status) {
        if (status == null) {
            return "-";
        }
        switch (status) {
            case 0: return "草稿";
            case 1: return "待审";
            case 2: return "已审核";
            case 3: return "已作废";
            default: return String.valueOf(status);
        }
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private Long requireOrgId() {
        LoginUser u = UserContext.get();
        if (u == null || u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作医技业务");
        }
        return u.getOrgId();
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static BigDecimal toBd(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        if (o instanceof Number) {
            return new BigDecimal(o.toString());
        }
        String s = o.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
