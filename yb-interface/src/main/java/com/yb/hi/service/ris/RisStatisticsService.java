package com.yb.hi.service.ris;

import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 统计分析服务(工作量/阳性率/报告时效/设备利用/收入 五类报表)
 * 统一口径:
 * - 机构边界: {@link OrgAccessGuard#scopeOrgId}, 牵头机构可看医共体全部(null), 非牵头锁定本机构;
 * - 租户过滤: JdbcTemplate 原生 SQL 不走租户插件, 显式 tenant_id + deleted=0;
 * - 日期口径: 工作量/收入按申请日期(apply_time), 阳性率按报告日期(rpt_date), 时效/设备按执行时间(exam_end_time);
 * - 返回形态: 每类报表返回分组 Map(键=分组维度说明), 值为行 List(列含计数/均值/占比, 供前端表格直渲染)。
 */
@Slf4j
@Service
public class RisStatisticsService {

    /** 单设备单日标准工作时长(分钟, 8 小时, 开机率分母) */
    private static final int DAILY_WORK_MINUTES = 480;

    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard orgAccessGuard;

    public RisStatisticsService(JdbcTemplate jdbcTemplate, OrgAccessGuard orgAccessGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.orgAccessGuard = orgAccessGuard;
    }

    /**
     * 工作量统计: 按医生/科室/设备三维度统计检查量与报告量
     * 检查量 = 申请单数(his_exam_request), 报告量 = 关联报告数(his_exam_report, 含未审核草稿)。
     *
     * @param orgId     机构(null=按登录身份圈定)
     * @param deptId    执行科室过滤(可空)
     * @param doctorId  申请医生过滤(可空)
     * @param startDate 起始日期 yyyy-MM-dd(按申请日期)
     * @param endDate   截止日期 yyyy-MM-dd
     * @return {byDoctor: List, byDept: List, byDevice: List}
     */
    public Map<String, Object> workloadStats(Long orgId, Long deptId, Long doctorId,
                                             String startDate, String endDate) {
        Long effOrgId = orgAccessGuard.scopeOrgId(orgId);
        Long tenantId = currentTenantId();
        List<Object> args = baseArgs(tenantId, effOrgId);
        StringBuilder where = new StringBuilder(baseWhere("q", effOrgId));
        where.append(" AND DATE(q.apply_time) BETWEEN ? AND ?");
        args.add(startDate);
        args.add(endDate);
        if (deptId != null) {
            where.append(" AND q.target_dept_id = ?");
            args.add(deptId);
        }
        if (doctorId != null) {
            where.append(" AND q.apply_doctor_id = ?");
            args.add(doctorId);
        }
        String join = " FROM his_exam_request q"
                + " LEFT JOIN his_exam_report r ON r.request_id = q.id AND r.deleted = 0";

        // 按申请医生
        List<Map<String, Object>> byDoctor = jdbcTemplate.queryForList(
                "SELECT q.apply_doctor_id, IFNULL(q.apply_doctor_name, '未指定') AS doctor_name,"
                        + " COUNT(*) AS exam_cnt,"
                        + " SUM(CASE WHEN r.id IS NOT NULL THEN 1 ELSE 0 END) AS report_cnt"
                        + join + where + " GROUP BY q.apply_doctor_id, q.apply_doctor_name"
                        + " ORDER BY exam_cnt DESC",
                args.toArray());

        // 按执行科室
        List<Map<String, Object>> byDept = jdbcTemplate.queryForList(
                "SELECT q.target_dept_id, IFNULL(q.target_dept_name, '未分配') AS dept_name,"
                        + " COUNT(*) AS exam_cnt,"
                        + " SUM(CASE WHEN r.id IS NOT NULL THEN 1 ELSE 0 END) AS report_cnt"
                        + join + where + " GROUP BY q.target_dept_id, q.target_dept_name"
                        + " ORDER BY exam_cnt DESC",
                args.toArray());

        // 按设备
        List<Map<String, Object>> byDevice = jdbcTemplate.queryForList(
                "SELECT q.device_id, IFNULL(d.device_name, '未分配') AS device_name, d.modality,"
                        + " COUNT(*) AS exam_cnt,"
                        + " SUM(CASE WHEN r.id IS NOT NULL THEN 1 ELSE 0 END) AS report_cnt"
                        + join + " LEFT JOIN his_imaging_device d ON q.device_id = d.id AND d.deleted = 0"
                        + where + " GROUP BY q.device_id, d.device_name, d.modality"
                        + " ORDER BY exam_cnt DESC",
                args.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byDoctor", byDoctor);
        result.put("byDept", byDept);
        result.put("byDevice", byDevice);
        return result;
    }

    /**
     * 阳性率统计: 按检查类型/部位统计 positive_flag=1 占比(仅统计已判定 positive_flag>=0 的报告)
     *
     * @param orgId    机构(null=按登录身份圈定)
     * @param deptType 科室类型过滤: RADIOLOGY/ULTRASOUND/ENDOSCOPY(可空)
     * @return {byExamType: List, byBodyPart: List}, 行含 total/judged/positive/positive_rate
     */
    public Map<String, Object> positiveRateStats(Long orgId, String deptType, String startDate, String endDate) {
        Long effOrgId = orgAccessGuard.scopeOrgId(orgId);
        Long tenantId = currentTenantId();
        List<Object> args = baseArgs(tenantId, effOrgId);
        StringBuilder where = new StringBuilder(
                " WHERE r.tenant_id = ? AND r.deleted = 0 AND r.report_type = 'exam'"
                        + " AND r.positive_flag >= 0 AND DATE(r.rpt_date) BETWEEN ? AND ?");
        args.add(startDate);
        args.add(endDate);
        if (effOrgId != null) {
            where.append(" AND r.org_id = ?");
        }
        if (deptType != null && !deptType.isEmpty()) {
            where.append(" AND r.exam_dept_type = ?");
            args.add(deptType);
        }
        String join = " FROM his_exam_report r JOIN his_exam_request q ON r.request_id = q.id AND q.deleted = 0";
        String select = "SELECT {DIM} AS dim_label, COUNT(*) AS total,"
                + " SUM(CASE WHEN r.positive_flag = 1 THEN 1 ELSE 0 END) AS positive,"
                + " ROUND(SUM(CASE WHEN r.positive_flag = 1 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS positive_rate"
                + join + where;

        List<Map<String, Object>> byExamType = jdbcTemplate.queryForList(
                select.replace("{DIM}", "IFNULL(q.exam_type, '未知')") + " GROUP BY q.exam_type ORDER BY total DESC",
                args.toArray());
        List<Map<String, Object>> byBodyPart = jdbcTemplate.queryForList(
                select.replace("{DIM}", "IFNULL(r.body_part, '未记录')") + " GROUP BY r.body_part ORDER BY total DESC LIMIT 50",
                args.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byExamType", byExamType);
        result.put("byBodyPart", byBodyPart);
        return result;
    }

    /**
     * 报告时效统计: 检查完成(exam_end_time) -> 报告审核(review_time) 平均时长(分钟), 分普通/急诊/危急值三类
     *
     * @return List: {category: normal/urgent/critical, total, avg_minutes, min_minutes, max_minutes, within_2h_rate}
     */
    public List<Map<String, Object>> reportTimeStats(Long orgId, String startDate, String endDate) {
        Long effOrgId = orgAccessGuard.scopeOrgId(orgId);
        Long tenantId = currentTenantId();
        List<Object> args = baseArgs(tenantId, effOrgId);
        String where = " WHERE r.tenant_id = ? AND r.deleted = 0 AND r.status = 2 AND r.review_time IS NOT NULL"
                + " AND DATE(e.exam_end_time) BETWEEN ? AND ?";
        args.add(startDate);
        args.add(endDate);
        if (effOrgId != null) {
            where += " AND r.org_id = ?";
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT CASE WHEN r.critical_flag = 1 THEN 'critical' WHEN q.is_urgent = 1 THEN 'urgent'"
                        + " ELSE 'normal' END AS category,"
                        + " COUNT(*) AS total,"
                        + " ROUND(AVG(TIMESTAMPDIFF(MINUTE, e.exam_end_time, r.review_time)), 1) AS avg_minutes,"
                        + " MIN(TIMESTAMPDIFF(MINUTE, e.exam_end_time, r.review_time)) AS min_minutes,"
                        + " MAX(TIMESTAMPDIFF(MINUTE, e.exam_end_time, r.review_time)) AS max_minutes,"
                        + " ROUND(SUM(CASE WHEN TIMESTAMPDIFF(MINUTE, e.exam_end_time, r.review_time) <= 120 THEN 1 ELSE 0 END)"
                        + " * 100.0 / COUNT(*), 2) AS within_2h_rate"
                        + " FROM his_exam_report r"
                        + " JOIN his_exam_request q ON r.request_id = q.id AND q.deleted = 0"
                        + " LEFT JOIN his_exam_execution e ON e.request_id = r.request_id AND e.deleted = 0"
                        + where + " GROUP BY category",
                args.toArray());
        return rows;
    }

    /**
     * 设备利用率: 按设备统计检查量/检查总时长/开机率(检查占用时长 ÷ 天数×8 小时标准工时)
     *
     * @return List: {device_name, modality, check_cnt, total_minutes, avg_minutes, active_days, utilization_pct}
     */
    public List<Map<String, Object>> deviceUtilizationStats(Long orgId, String startDate, String endDate) {
        Long effOrgId = orgAccessGuard.scopeOrgId(orgId);
        Long tenantId = currentTenantId();
        List<Object> args = baseArgs(tenantId, effOrgId);
        String where = " WHERE e.tenant_id = ? AND e.deleted = 0 AND e.device_id IS NOT NULL"
                + " AND DATE(e.exam_end_time) BETWEEN ? AND ?";
        args.add(startDate);
        args.add(endDate);
        if (effOrgId != null) {
            where += " AND e.org_id = ?";
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT d.device_name, d.modality,"
                        + " COUNT(e.id) AS check_cnt,"
                        + " IFNULL(SUM(TIMESTAMPDIFF(MINUTE, e.exam_start_time, e.exam_end_time)), 0) AS total_minutes,"
                        + " ROUND(IFNULL(AVG(TIMESTAMPDIFF(MINUTE, e.exam_start_time, e.exam_end_time)), 0), 1) AS avg_minutes,"
                        + " COUNT(DISTINCT DATE(e.exam_end_time)) AS active_days,"
                        + " ROUND(IFNULL(SUM(TIMESTAMPDIFF(MINUTE, e.exam_start_time, e.exam_end_time)), 0) * 100.0"
                        + " / (COUNT(DISTINCT DATE(e.exam_end_time)) * " + DAILY_WORK_MINUTES + "), 2) AS utilization_pct"
                        + " FROM his_exam_execution e"
                        + " JOIN his_imaging_device d ON e.device_id = d.id AND d.deleted = 0"
                        + where + " GROUP BY d.id, d.device_name, d.modality ORDER BY check_cnt DESC",
                args.toArray());
        return rows;
    }

    /**
     * 收入统计: 按执行科室/检查类型统计检查费用(exam_charge)总额
     *
     * @return {byDept: List, byExamType: List}, 行含 order_cnt/total_charge
     */
    public Map<String, Object> revenueStats(Long orgId, String startDate, String endDate) {
        Long effOrgId = orgAccessGuard.scopeOrgId(orgId);
        Long tenantId = currentTenantId();
        List<Object> args = baseArgs(tenantId, effOrgId);
        StringBuilder where = new StringBuilder(baseWhere("q", effOrgId));
        where.append(" AND DATE(q.apply_time) BETWEEN ? AND ?");
        args.add(startDate);
        args.add(endDate);
        // 7 已取消申请不计收入
        where.append(" AND q.status <> 7");

        List<Map<String, Object>> byDept = jdbcTemplate.queryForList(
                "SELECT q.target_dept_id, IFNULL(q.target_dept_name, '未分配') AS dept_name,"
                        + " COUNT(*) AS order_cnt, IFNULL(SUM(q.exam_charge), 0) AS total_charge"
                        + " FROM his_exam_request q" + where + " GROUP BY q.target_dept_id, q.target_dept_name"
                        + " ORDER BY total_charge DESC",
                args.toArray());
        List<Map<String, Object>> byExamType = jdbcTemplate.queryForList(
                "SELECT IFNULL(q.exam_type, '未知') AS exam_type,"
                        + " COUNT(*) AS order_cnt, IFNULL(SUM(q.exam_charge), 0) AS total_charge"
                        + " FROM his_exam_request q" + where + " GROUP BY q.exam_type ORDER BY total_charge DESC",
                args.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byDept", byDept);
        result.put("byExamType", byExamType);
        return result;
    }

    // ---------- 内部实现 ----------

    /** 申请单(q)基础过滤: 租户 + 机构(可空) */
    private static String baseWhere(String alias, Long effOrgId) {
        String where = " WHERE " + alias + ".tenant_id = ? AND " + alias + ".deleted = 0";
        if (effOrgId != null) {
            where += " AND " + alias + ".org_id = ?";
        }
        return where;
    }

    /** 基础参数: tenant_id 在前, effOrgId 非空时紧随(与 baseWhere 占位顺序一致) */
    private static List<Object> baseArgs(Long tenantId, Long effOrgId) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        if (effOrgId != null) {
            args.add(effOrgId);
        }
        return args;
    }

    private static Long currentTenantId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getTenantId();
    }
}
