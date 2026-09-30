package com.yb.hi.service.inpatient;

import com.yb.hi.entity.inpatient.HisWard;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisWardMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院业务看板聚合服务(医生站/护士站首页): 待办统计 / 患者统计 / 近7日入出院趋势 / 风险清单。
 * 说明:
 * 1) JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0;
 * 2) wardId/deptId 均为可选过滤(医生站按科室, 护士站按病区), 空则租户内全量;
 * 3) 计数口径: 在院=visit_status=2; 今日入院=admit_date落当日且非取消(2/3/4);
 *    今日出院=visit_status=4且discharge_date落当日; 危重=condition_level IN(1,2)且在院。
 */
@Slf4j
@Service
public class InpDashboardService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 近7日趋势回溯天数(含今日) */
    private static final int TREND_DAYS = 7;

    /** 列表类清单返回上限(看板卡片场景, 防止大机构一次拉全量) */
    private static final int LIST_LIMIT = 50;

    private final JdbcTemplate jdbcTemplate;
    private final InpVisitService visitService;
    private final InpOrderExecService orderExecService;
    private final InpNursingService nursingService;
    private final HisWardMapper wardMapper;

    public InpDashboardService(JdbcTemplate jdbcTemplate, InpVisitService visitService,
                               InpOrderExecService orderExecService, InpNursingService nursingService,
                               HisWardMapper wardMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.visitService = visitService;
        this.orderExecService = orderExecService;
        this.nursingService = nursingService;
        this.wardMapper = wardMapper;
    }

    /* ==================== 医生站看板 ==================== */

    /** 医生站看板: 待办统计 + 患者统计 + 近7日趋势 + 今日手术 + 时限预警。deptId 空时回落当前登录医生科室。 */
    public Map<String, Object> getDoctorDashboard(Long deptId) {
        Long dept = resolveDeptId(deptId);
        Map<String, Integer> counts = visitService.countByStatus(null, dept);

        Map<String, Object> patientStats = new LinkedHashMap<>();
        patientStats.put("total", counts.get("total"));
        patientStats.put("todayAdmit", counts.get("todayAdmit"));
        patientStats.put("todayDischarge", counts.get("todayDischarge"));
        patientStats.put("critical", counts.get("critical"));
        patientStats.put("depositWarning", countDepositWarning(null, dept));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todoStats", doctorTodoStats(dept));
        out.put("patientStats", patientStats);
        out.put("recentAdmissions", recentAdmissions(null, dept));
        out.put("todaySurgeries", todaySurgeries(dept));
        out.put("deadlineAlerts", deadlineAlerts(dept));
        return out;
    }

    /** 医生待办: 待写病历/待处理会诊/待审核医嘱/今日临时医嘱提醒。 */
    private Map<String, Object> doctorTodoStats(Long dept) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 待写病历: 草稿(status=1)且时限质控已设置的行
        String recordsSql = "SELECT COUNT(*) FROM his_inp_medical_record mr"
                + " JOIN his_inp_visit v ON mr.inp_visit_id = v.id AND v.deleted = 0"
                + " WHERE mr.status = 1 AND mr.deadline_time IS NOT NULL"
                + " AND mr.deleted = 0 AND mr.tenant_id = ?";
        out.put("pendingRecords", countByDept(recordsSql, "v.dept_id", dept));
        // 待处理会诊: 申请/受理中且受邀科室为当前科室
        String consultSql = "SELECT COUNT(*) FROM his_inp_consultation c"
                + " WHERE c.status IN (1, 2) AND c.deleted = 0 AND c.tenant_id = ?";
        out.put("pendingConsults", countByDept(consultSql, "c.target_dept_id", dept));
        // 待审核医嘱: order_status=1 新开
        String ordersSql = "SELECT COUNT(*) FROM his_inp_order o"
                + " JOIN his_inp_visit v ON o.inp_visit_id = v.id AND v.deleted = 0"
                + " WHERE o.order_status = 1 AND o.deleted = 0 AND o.tenant_id = ?";
        out.put("pendingOrders", countByDept(ordersSql, "v.dept_id", dept));
        // 今日临时医嘱提醒: order_type=2 且当日开立
        String tempSql = "SELECT COUNT(*) FROM his_inp_order o"
                + " JOIN his_inp_visit v ON o.inp_visit_id = v.id AND v.deleted = 0"
                + " WHERE o.order_type = 2 AND o.deleted = 0 AND o.tenant_id = ?"
                + " AND o.create_time >= CURDATE() AND o.create_time < CURDATE() + INTERVAL 1 DAY";
        out.put("tempOrderAlerts", countByDept(tempSql, "v.dept_id", dept));
        return out;
    }

    /** 预交金预警数: 未处理(handle_result IS NULL)的预交金不足预警(alert_type=3)。 */
    private int countDepositWarning(Long wardId, Long deptId) {
        String sql = "SELECT COUNT(*) FROM his_inp_fee_alert a"
                + " JOIN his_inp_visit v ON a.inp_visit_id = v.id AND v.deleted = 0"
                + " WHERE a.alert_type = 3 AND a.handle_result IS NULL AND a.deleted = 0 AND a.tenant_id = ?";
        return countByScope(sql, "v", wardId, deptId);
    }

    /** 近7日入出院趋势(按日分组, 缺日补0, 供折线图直接渲染)。 */
    private List<Map<String, Object>> recentAdmissions(Long wardId, Long deptId) {
        LocalDate today = LocalDate.now();
        LocalDate startDay = today.minusDays(TREND_DAYS - 1L);
        LocalDateTime start = startDay.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        String admitSql = "SELECT DATE_FORMAT(v.admit_date, '%Y-%m-%d') AS d, COUNT(*) AS c"
                + " FROM his_inp_visit v WHERE v.deleted = 0 AND v.tenant_id = ?"
                + " AND v.admit_date >= ? AND v.admit_date < ?";
        String dischargeSql = "SELECT DATE_FORMAT(v.discharge_date, '%Y-%m-%d') AS d, COUNT(*) AS c"
                + " FROM his_inp_visit v WHERE v.visit_status = 4 AND v.deleted = 0 AND v.tenant_id = ?"
                + " AND v.discharge_date >= ? AND v.discharge_date < ?";
        Map<String, Integer> admitMap = groupCountByDay(admitSql, "admit_date", start, end, wardId, deptId);
        Map<String, Integer> dischargeMap = groupCountByDay(dischargeSql, "discharge_date", start, end, wardId, deptId);

        List<Map<String, Object>> out = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) {
            String day = startDay.plusDays(i).format(DAY_FMT);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day);
            row.put("admitCount", admitMap.getOrDefault(day, 0));
            row.put("dischargeCount", dischargeMap.getOrDefault(day, 0));
            out.add(row);
        }
        return out;
    }

    /** 按日分组计数查询(趋势图共用): dateColumn 为分组日期列, SQL 须含 tenant_id/日期范围占位符。 */
    private Map<String, Integer> groupCountByDay(String baseSql, String dateColumn, LocalDateTime start,
                                                 LocalDateTime end, Long wardId, Long deptId) {
        StringBuilder sql = new StringBuilder(baseSql);
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(start);
        args.add(end);
        if (wardId != null) {
            sql.append(" AND v.ward_id = ?");
            args.add(wardId);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        sql.append(" GROUP BY DATE_FORMAT(v.").append(dateColumn).append(", '%Y-%m-%d')");
        Map<String, Integer> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> row : rows) {
            Object day = row.get("d");
            Object cnt = row.get("c");
            if (day == null || cnt == null) {
                continue;
            }
            out.put(String.valueOf(day), ((Number) cnt).intValue());
        }
        return out;
    }

    /** 今日手术列表(手术日期=当日): JOIN 患者/床位/主刀, 按时间段正序。 */
    private List<Map<String, Object>> todaySurgeries(Long dept) {
        String sql = "SELECT s.id, s.inp_visit_id AS inpVisitId, s.surgery_name AS surgeryName,"
                + " s.surgery_level AS surgeryLevel, s.surgeon_id AS surgeonId, st.staff_name AS surgeonName,"
                + " s.room_no AS roomNo, s.schedule_time AS scheduleTime, s.status,"
                + " v.inp_no AS inpNo, p.name AS patientName, p.gender, p.age, b.bed_no AS bedNo"
                + " FROM his_surgery s"
                + " JOIN his_inp_visit v ON s.inp_visit_id = v.id AND v.deleted = 0"
                + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                + " LEFT JOIN his_staff st ON s.surgeon_id = st.id AND st.deleted = 0"
                + " WHERE s.schedule_date = CURDATE() AND s.deleted = 0 AND s.tenant_id = ?";
        if (dept != null) {
            sql += " AND v.dept_id = ?";
            return jdbcTemplate.queryForList(sql + " ORDER BY s.schedule_time ASC, s.id ASC",
                    tenantId(), dept);
        }
        return jdbcTemplate.queryForList(sql + " ORDER BY s.schedule_time ASC, s.id ASC", tenantId());
    }

    /** 即将超时病历(2小时内到时限且仍为草稿): 供医生站红点提醒。 */
    private List<Map<String, Object>> deadlineAlerts(Long dept) {
        String sql = "SELECT mr.id, mr.inp_visit_id AS inpVisitId, mr.record_type AS recordType, mr.title,"
                + " DATE_FORMAT(mr.deadline_time, '%Y-%m-%d %H:%i') AS deadlineTime,"
                + " v.inp_no AS inpNo, p.name AS patientName, b.bed_no AS bedNo"
                + " FROM his_inp_medical_record mr"
                + " JOIN his_inp_visit v ON mr.inp_visit_id = v.id AND v.deleted = 0"
                + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                + " WHERE mr.status = 1 AND mr.deadline_time IS NOT NULL AND mr.deleted = 0 AND mr.tenant_id = ?"
                + " AND mr.deadline_time >= NOW() AND mr.deadline_time <= DATE_ADD(NOW(), INTERVAL 2 HOUR)";
        String tail = " ORDER BY mr.deadline_time ASC LIMIT " + LIST_LIMIT;
        if (dept != null) {
            return jdbcTemplate.queryForList(sql + " AND v.dept_id = ?" + tail, tenantId(), dept);
        }
        return jdbcTemplate.queryForList(sql + tail, tenantId());
    }

    /* ==================== 护士站看板 ==================== */

    /** 护士站看板: 待办统计 + 患者统计 + 危重患者清单 + 护理评估预警。wardId 空则全租户(不推荐, 前端必传)。 */
    public Map<String, Object> getNurseDashboard(Long wardId) {
        requireWard(wardId);
        Map<String, Integer> counts = visitService.countByStatus(wardId, null);

        Map<String, Object> patientStats = new LinkedHashMap<>();
        patientStats.put("total", counts.get("total"));
        patientStats.put("todayAdmit", counts.get("todayAdmit"));
        patientStats.put("todayDischarge", counts.get("todayDischarge"));
        patientStats.put("critical", counts.get("critical"));
        patientStats.put("specialNursing", counts.get("specialNursing"));
        patientStats.put("firstNursing", counts.get("firstNursing"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todoStats", nurseTodoStats(wardId));
        out.put("patientStats", patientStats);
        out.put("criticalPatients", criticalPatients(wardId));
        out.put("nursingAlerts", nursingAlerts(wardId));
        return out;
    }

    /** 护士待办: 待审核医嘱/待执行医嘱/待评估患者/交班待确认/体温未测。 */
    private Map<String, Object> nurseTodoStats(Long wardId) {
        Map<String, Integer> orderStats = orderExecService.countPendingByWard(wardId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendingAudit", orderStats.get("pendingAudit"));
        out.put("pendingExec", orderStats.get("pendingExec"));
        out.put("pendingAssess", nursingService.countPendingAssessments(wardId));
        // 交班待确认: 已发起未接班的交接班记录(status=1)
        String shiftSql = "SELECT COUNT(*) FROM his_inp_shift_record s"
                + " WHERE s.status = 1 AND s.deleted = 0 AND s.tenant_id = ?";
        out.put("pendingShift", countByWard(shiftSql, "s.ward_id", wardId));
        // 体温未测: 在院且今日无体温单记录(record_type=1)的患者数
        String vitalsSql = "SELECT COUNT(*) FROM his_inp_visit v"
                + " WHERE v.visit_status = 2 AND v.deleted = 0 AND v.tenant_id = ?"
                + " AND NOT EXISTS (SELECT 1 FROM his_inp_nursing_record r"
                + "  WHERE r.inp_visit_id = v.id AND r.record_type = 1 AND r.deleted = 0"
                + "   AND r.record_time >= CURDATE() AND r.record_time < CURDATE() + INTERVAL 1 DAY)";
        out.put("pendingVitals", countByWard(vitalsSql, "v.ward_id", wardId));
        return out;
    }

    /** 危重患者清单(在院且病情等级危/重): JOIN 床位取床号, 危重优先。 */
    private List<Map<String, Object>> criticalPatients(Long wardId) {
        String sql = "SELECT v.id AS visitId, v.inp_no AS inpNo, v.condition_level AS conditionLevel,"
                + " v.nursing_level AS nursingLevel, v.admit_diag AS admitDiag,"
                + " v.deposit_balance AS depositBalance, v.admit_date AS admitDate,"
                + " p.name AS patientName, p.gender, p.age, b.bed_no AS bedNo, b.room_no AS roomNo,"
                + " ds.staff_name AS doctorName"
                + " FROM his_inp_visit v"
                + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                + " LEFT JOIN his_staff ds ON v.doctor_id = ds.id AND ds.deleted = 0"
                + " WHERE v.visit_status = 2 AND v.condition_level IN (1, 2)"
                + " AND v.deleted = 0 AND v.tenant_id = ?";
        String tail = " ORDER BY v.condition_level ASC, v.admit_date ASC LIMIT " + LIST_LIMIT;
        if (wardId != null) {
            return jdbcTemplate.queryForList(sql + " AND v.ward_id = ?" + tail, tenantId(), wardId);
        }
        return jdbcTemplate.queryForList(sql + tail, tenantId());
    }

    /** 护理预警清单: 在院且近7天无护理评估记录的患者(评估缺失或超7天未更新), 附最近评估时间。 */
    private List<Map<String, Object>> nursingAlerts(Long wardId) {
        String sql = "SELECT v.id AS visitId, v.inp_no AS inpNo, v.admit_date AS admitDate,"
                + " p.name AS patientName, b.bed_no AS bedNo,"
                + " DATE_FORMAT((SELECT MAX(r.record_time) FROM his_inp_nursing_record r"
                + "   WHERE r.inp_visit_id = v.id AND r.record_type = 2 AND r.deleted = 0),"
                + "   '%Y-%m-%d %H:%i') AS lastAssessTime"
                + " FROM his_inp_visit v"
                + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                + " WHERE v.visit_status = 2 AND v.deleted = 0 AND v.tenant_id = ?"
                + " AND NOT EXISTS (SELECT 1 FROM his_inp_nursing_record r2"
                + "  WHERE r2.inp_visit_id = v.id AND r2.record_type = 2 AND r2.deleted = 0"
                + "   AND r2.record_time >= DATE_SUB(NOW(), INTERVAL 7 DAY))";
        String tail = " ORDER BY v.admit_date ASC LIMIT " + LIST_LIMIT;
        if (wardId != null) {
            return jdbcTemplate.queryForList(sql + " AND v.ward_id = ?" + tail, tenantId(), wardId);
        }
        return jdbcTemplate.queryForList(sql + tail, tenantId());
    }

    /* ==================== 患者列表统计卡 ==================== */

    /** 患者列表页统计卡: 在院/今日入出院/危重/欠费/预交金预警。wardId/deptId 可选过滤。 */
    public Map<String, Object> getPatientListStats(Long wardId, Long deptId) {
        requireWard(wardId);
        Map<String, Integer> counts = visitService.countByStatus(wardId, deptId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", counts.get("total"));
        out.put("todayAdmit", counts.get("todayAdmit"));
        out.put("todayDischarge", counts.get("todayDischarge"));
        out.put("critical", counts.get("critical"));
        out.put("arrears", counts.get("arrears"));
        out.put("depositWarning", countDepositWarning(wardId, deptId));
        return out;
    }

    /* ==================== 内部工具 ==================== */

    /** 计数(基础): SQL 以 tenant_id 占位结尾。 */
    private int count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n.intValue();
    }

    /** 计数(可选科室条件): dept 为空时不追加条件。 */
    private int countByDept(String baseSql, String deptColumn, Long dept) {
        if (dept == null) {
            return count(baseSql, tenantId());
        }
        return count(baseSql + " AND " + deptColumn + " = ?", tenantId(), dept);
    }

    /** 计数(可选病区条件): wardId 为空时不追加条件。 */
    private int countByWard(String baseSql, String wardColumn, Long wardId) {
        if (wardId == null) {
            return count(baseSql, tenantId());
        }
        return count(baseSql + " AND " + wardColumn + " = ?", tenantId(), wardId);
    }

    /** 计数(病区+科室双可选条件, 作用于指定就诊别名)。 */
    private int countByScope(String baseSql, String visitAlias, Long wardId, Long deptId) {
        StringBuilder sql = new StringBuilder(baseSql);
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (wardId != null) {
            sql.append(" AND ").append(visitAlias).append(".ward_id = ?");
            args.add(wardId);
        }
        if (deptId != null) {
            sql.append(" AND ").append(visitAlias).append(".dept_id = ?");
            args.add(deptId);
        }
        return count(sql.toString(), args.toArray());
    }

    /** 病区归属校验: 存在 + 归属当前登录机构(平台超管放行); wardId 为空直接放行(可选过滤)。 */
    private void requireWard(Long wardId) {
        if (wardId == null) {
            return;
        }
        HisWard ward = wardMapper.selectById(wardId);
        if (ward == null) {
            throw new BizException(404, "病区不存在");
        }
        LoginUser u = UserContext.get();
        if (u != null && !u.hasRole(Roles.SUPER_ADMIN) && ward.getOrgId() != null && u.getOrgId() != null
                && !ward.getOrgId().equals(u.getOrgId())) {
            throw new BizException(403, "该病区不属于当前登录机构, 无权查看");
        }
    }

    /** 科室解析: 入参优先, 缺省回落当前登录医生归属科室。 */
    private Long resolveDeptId(Long deptId) {
        if (deptId != null) {
            return deptId;
        }
        LoginUser u = UserContext.get();
        return u == null ? null : u.getDeptId();
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致)。 */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
