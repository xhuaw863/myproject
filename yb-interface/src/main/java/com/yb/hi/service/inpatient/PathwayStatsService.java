package com.yb.hi.service.inpatient;

import com.yb.hi.framework.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 临床路径统计质控服务(对标三级公立医院病种质控与专业路径系统"四大率"口径):
 * - overview 管理驾驶舱(实时): 在径患者/今日入径/今日待办/逾期任务/本月变异退出动态 + 本月四大率 + 在径患者一览;
 * - rates 质控四大率(总体 + dim=dept科室/doctor主管医生/disease病种分维):
 *   入径率分母 = 窗口内出院(visit_status=4)且出院主诊断(diag_type=4, is_main=1) ICD-10 三位类目
 *   (LEFT(REPLACE(diag_code,'.',''),3)) 命中启用路径模板 disease_code 类目集合的去重 visit 数,
 *   分子 = 其中存在关联路径实例(inp_visit_id)的去重 visit 数;
 *   完成率 = 实例 status=2 / 入径实例数; 退出率 = status=3 / 入径数;
 *   病例变异率 = 发生>=1条 exec_status=4 的实例数 / 入径数; 项次变异率 = 变异 exec 条数 / 总 exec 条数;
 * - trend 月度趋势(入径例数/完成率/变异率按月, 窗口默认近12个月);
 * - variancePareto / exitPareto 变异·退出原因分类柏拉图(计数降序+累计占比, 空分类归"未分类");
 * - efficiency 效率费用三口径对比(入径完成组/非入径同病种出院组/模板标准 avg_length·total_cost 加权)+达标符合率;
 * - coverage 病种覆盖(启用模板数/覆盖科室数/各病种入径占其出院比);
 * - detail 病例明细分页钻取(入径/状态/天数/变异项数/实际住院日/实际费用)。
 * 口径总则: 统计窗口 from..to 一律按 his_inp_visit.discharge_date(出院口径)圈定队列;
 * JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0;
 * 机构隔离由控制器 guard.scopeOrgId 传入(orgId=null 表示本租户全部机构); 单条查询异常归零/空集保统计可用。
 */
@Slf4j
@Service
public class PathwayStatsService {

    /** 未分类占位名(存量变异/退出记录无分类码时归入, 不回溯) */
    private static final String UNCLASSIFIED = "未分类";

    private final JdbcTemplate jdbcTemplate;

    public PathwayStatsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 路径统计(orgId 为 null 时统计当前租户全部机构) - 旧极简总量接口, 保留兼容 */
    public Map<String, Object> getStats(Long orgId) {
        long tenant = tenantId();
        StringBuilder where = new StringBuilder(" WHERE deleted = 0 AND tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (orgId != null) {
            where.append(" AND org_id = ?");
            args.add(orgId);
        }
        String instWhere = where.toString();
        String execWhere = where.toString();

        long totalInstances = countOrZero("SELECT COUNT(*) FROM his_pathway_instance" + instWhere, args);
        long completedCount = countOrZero("SELECT COUNT(*) FROM his_pathway_instance" + instWhere + " AND status = 2", args);
        long exitedCount = countOrZero("SELECT COUNT(*) FROM his_pathway_instance" + instWhere + " AND status = 3", args);
        // 平均在径天数(仅已完成实例)
        Double avgDays = jdbcTemplate.queryForObject(
                "SELECT AVG(DATEDIFF(end_date, start_date)) FROM his_pathway_instance"
                        + instWhere + " AND status = 2 AND end_date IS NOT NULL AND start_date IS NOT NULL",
                Double.class, args.toArray());
        // 变异率(全体执行记录口径)
        long totalExec = countOrZero("SELECT COUNT(*) FROM his_pathway_exec" + execWhere, args);
        long varianceCount = countOrZero("SELECT COUNT(*) FROM his_pathway_exec" + execWhere + " AND exec_status = 4", args);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orgId", orgId);
        out.put("totalInstances", totalInstances);
        out.put("completedCount", completedCount);
        out.put("exitedCount", exitedCount);
        out.put("activeCount", totalInstances - completedCount - exitedCount);
        out.put("avgStayDays", avgDays == null ? 0.0 : Math.round(avgDays * 10.0) / 10.0);
        out.put("totalExec", totalExec);
        out.put("varianceCount", varianceCount);
        out.put("varianceRate", totalExec == 0 ? 0.0 : Math.round(varianceCount * 10000.0 / totalExec) / 10000.0);
        return out;
    }

    /* ==================== 统计窗口条件 ==================== */

    /** 统计过滤条件: 出院窗口/科室/医生/模板(病种), 均可空 */
    private static final class Crit {
        LocalDate from;
        LocalDate to;
        Long deptId;
        Long doctorId;
        Long templateId;

        /** [from, to] 解析(yyyy-MM-dd, 空回落默认近 defaultDays 天), 保证 from<=to */
        void resolveRange(String fromStr, String toStr, int defaultDays) {
            from = parseDate(fromStr);
            to = parseDate(toStr);
            if (to == null) {
                to = LocalDate.now();
            }
            if (from == null) {
                from = to.minusDays(defaultDays - 1L);
            }
            if (from.isAfter(to)) {
                LocalDate t = from;
                from = to;
                to = t;
            }
        }
    }

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Crit crit(LocalDate from, LocalDate to, Long deptId, Long doctorId, Long templateId) {
        Crit c = new Crit();
        c.from = from;
        c.to = to;
        c.deptId = deptId;
        c.doctorId = doctorId;
        c.templateId = templateId;
        return c;
    }

    /* ==================== 一、管理驾驶舱(实时) ==================== */

    /** 驾驶舱: 实时 KPI + 本月四大率 + 在径患者一览 + 近30天变异/退出待复核列表 */
    public Map<String, Object> overview(Long orgId) {
        long tenant = tenantId();
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orgId", orgId);
        out.put("date", today.toString());

        // --- 实时 KPI ---
        out.put("inPathwayCount", countOrZero("SELECT COUNT(*) FROM his_pathway_instance i"
                + " WHERE i.deleted = 0 AND i.tenant_id = ? AND i.status IN (1, 4)" + orgSql("i.org_id", orgId),
                args(tenant, orgId)));
        out.put("todayEnterCount", countOrZero("SELECT COUNT(*) FROM his_pathway_instance i"
                + " WHERE i.deleted = 0 AND i.tenant_id = ? AND i.start_date >= ? AND i.start_date < ?"
                + orgSql("i.org_id", orgId), args(tenant, dayStart, dayEnd, orgId)));
        out.put("todayTaskCount", countOrZero("SELECT COUNT(*) FROM his_pathway_exec e"
                + " WHERE e.deleted = 0 AND e.tenant_id = ? AND e.exec_date = ? AND e.exec_status = 1"
                + orgSql("e.org_id", orgId), args(tenant, today, orgId)));
        out.put("overdueTaskCount", countOrZero("SELECT COUNT(*) FROM his_pathway_exec e"
                + " WHERE e.deleted = 0 AND e.tenant_id = ? AND e.exec_date < ? AND e.exec_status = 1"
                + orgSql("e.org_id", orgId), args(tenant, today, orgId)));
        out.put("varianceMonthCount", countOrZero("SELECT COUNT(*) FROM his_pathway_exec e"
                + " WHERE e.deleted = 0 AND e.tenant_id = ? AND e.exec_status = 4 AND e.exec_date >= ?"
                + orgSql("e.org_id", orgId), args(tenant, monthStart, orgId)));
        out.put("exitMonthCount", countOrZero("SELECT COUNT(*) FROM his_pathway_instance i"
                + " WHERE i.deleted = 0 AND i.tenant_id = ? AND i.status = 3 AND i.update_time >= ?"
                + orgSql("i.org_id", orgId), args(tenant, monthStart, orgId)));

        // --- 本月四大率(出院口径窗口=本月1日..今日, 总体+分病种) ---
        out.put("monthRates", rates(crit(today.withDayOfMonth(1), today, null, null, null), orgId, tenant));

        // --- 在径患者一览(进行中/暂停, 按科室与第X天进度) ---
        List<Map<String, Object>> patients = safeList("SELECT i.id instanceId, i.inp_visit_id visitId,"
                + " p.name patientName, v.inp_no inpNo, d.dept_name deptName,"
                + " st.staff_name doctorName, t.pathway_name pathwayName, i.current_day currentDay,"
                + " (SELECT COUNT(DISTINCT e.day_no) FROM his_pathway_exec e"
                + "  WHERE e.instance_id = i.id AND e.deleted = 0) totalDays,"
                + " DATE_FORMAT(i.start_date, '%Y-%m-%d') startDate, i.status,"
                + " (SELECT COUNT(*) FROM his_pathway_exec e"
                + "  WHERE e.instance_id = i.id AND e.deleted = 0 AND e.exec_status = 4) varianceCount"
                + " FROM his_pathway_instance i"
                + " JOIN his_inp_visit v ON v.id = i.inp_visit_id AND v.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0"
                + " LEFT JOIN his_staff st ON st.id = i.doctor_id AND st.deleted = 0"
                + " LEFT JOIN his_pathway_template t ON t.id = i.template_id AND t.deleted = 0"
                + " WHERE i.deleted = 0 AND i.tenant_id = ? AND i.status IN (1, 4)"
                + orgSql("i.org_id", orgId)
                + " ORDER BY v.dept_id, i.start_date DESC LIMIT 300", args(tenant, orgId));
        for (Map<String, Object> row : patients) {
            row.put("dayPercent", pct(num(row.get("currentDay")), num(row.get("totalDays"))));
        }
        out.put("activePatients", patients);

        // --- 近30天变异/退出动态(质控待复核) ---
        out.put("recentVariances", safeList("SELECT e.id execId, e.instance_id instanceId,"
                + " p.name patientName, t.pathway_name pathwayName, e.day_no dayNo,"
                + " e.variance_type_name varianceTypeName, e.variance_reason varianceReason,"
                + " DATE_FORMAT(e.exec_date, '%Y-%m-%d') execDate"
                + " FROM his_pathway_exec e"
                + " JOIN his_pathway_instance i ON i.id = e.instance_id AND i.deleted = 0"
                + " JOIN his_inp_visit v ON v.id = i.inp_visit_id AND v.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_pathway_template t ON t.id = i.template_id AND t.deleted = 0"
                + " WHERE e.deleted = 0 AND e.tenant_id = ? AND e.exec_status = 4 AND e.exec_date >= ?"
                + orgSql("e.org_id", orgId)
                + " ORDER BY e.exec_date DESC, e.id DESC LIMIT 50",
                args(tenant, today.minusDays(29), orgId)));
        out.put("recentExits", safeList("SELECT i.id instanceId, p.name patientName,"
                + " t.pathway_name pathwayName, i.exit_type_name exitTypeName, i.exit_reason exitReason,"
                + " DATE_FORMAT(i.update_time, '%Y-%m-%d') exitDate"
                + " FROM his_pathway_instance i"
                + " JOIN his_inp_visit v ON v.id = i.inp_visit_id AND v.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_pathway_template t ON t.id = i.template_id AND t.deleted = 0"
                + " WHERE i.deleted = 0 AND i.tenant_id = ? AND i.status = 3 AND i.update_time >= ?"
                + orgSql("i.org_id", orgId)
                + " ORDER BY i.update_time DESC LIMIT 50",
                args(tenant, today.minusDays(29).atStartOfDay(), orgId)));
        return out;
    }

    /* ==================== 二、质控四大率 ==================== */

    /** 对外入口: 解析窗口后计算四大率(/rates 端点, 出院窗口缺省近90天) */
    public Map<String, Object> rates(String from, String to, Long deptId, Long doctorId, Long templateId, Long orgId) {
        Crit c = new Crit();
        c.resolveRange(from, to, 90);
        c.deptId = deptId;
        c.doctorId = doctorId;
        c.templateId = templateId;
        return rates(c, orgId, tenantId());
    }

    /** 四大率: 总体(出院口径队列) + 分维(dept/doctor/disease) + 医生入径率/科室完成率排行 */
    public Map<String, Object> rates(Crit c, Long orgId, long tenant) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", String.valueOf(c.from));
        out.put("to", String.valueOf(c.to));
        out.put("overall", firstRow(ratesRowsRaw(c, null, orgId, tenant)));
        List<Map<String, Object>> deptRows = ratesRowsRaw(c, "dept", orgId, tenant);
        List<Map<String, Object>> doctorRows = ratesRowsRaw(c, "doctor", orgId, tenant);
        List<Map<String, Object>> diseaseRows = ratesRowsRaw(c, "disease", orgId, tenant);
        out.put("byDept", deptRows);
        out.put("byDoctor", doctorRows);
        out.put("byDisease", diseaseRows);
        // 排行: 医生入径率 / 科室完成率(剔除分母0, 降序取前20)
        out.put("doctorEnterRank", topRank(doctorRows, "enterRate", 20));
        out.put("deptCompleteRank", topRank(deptRows, "completeRate", 20));
        return out;
    }

    /**
     * 四大率核心 SQL: 队列=窗口内出院且出院主诊断类目命中启用模板类目的 visit(分母 denominator);
     * 分子=其中入径 visit(entered); 完成/退出/变异按关联实例及其 exec 聚合。
     * 入径实例按 start_date 落窗过滤, 与出院队列时间口径一致(未出院已入径实例不计入本组率)。
     * dim=null 输出单行总体; dept/doctor/disease 按维分组。参数顺序统一由 Sql 构建器维护。
     */
    private List<Map<String, Object>> ratesRowsRaw(Crit c, String dim, Long orgId, long tenant) {
        Sql s = new Sql();
        String dimId = "CAST(NULL AS CHAR) dimId,";
        String dimName = "'全部' dimName,";
        String extraJoin = "";
        String postJoin = "";
        String groupBy = "";
        if ("dept".equals(dim)) {
            extraJoin = " LEFT JOIN his_dept dd ON dd.id = v.dept_id AND dd.deleted = 0";
            dimId = "CAST(v.dept_id AS CHAR) dimId,";
            dimName = "IFNULL(dd.dept_name, '未分科') dimName,";
            groupBy = " GROUP BY v.dept_id, dd.dept_name";
        } else if ("doctor".equals(dim)) {
            extraJoin = " LEFT JOIN his_staff ds ON ds.id = v.doctor_id AND ds.deleted = 0";
            dimId = "CAST(v.doctor_id AS CHAR) dimId,";
            dimName = "IFNULL(ds.staff_name, '未知') dimName,";
            groupBy = " GROUP BY v.doctor_id, ds.staff_name";
        } else if ("disease".equals(dim)) {
            // 模板维表按 i.template_id 关联, 须在实例 i 已 join 之后(postJoin)才能引用别名 i
            postJoin = " LEFT JOIN his_pathway_template t ON t.id = i.template_id AND t.deleted = 0";
            dimId = "CAST(t.id AS CHAR) dimId,";
            dimName = "IFNULL(t.pathway_name, '其他病种') dimName,";
            groupBy = " GROUP BY t.id, t.pathway_name";
        }
        s.raw("SELECT ").raw(dimId).raw(" ").raw(dimName)
                .raw(" COUNT(DISTINCT v.id) denominator,")
                .raw(" COUNT(DISTINCT CASE WHEN i.id IS NOT NULL THEN v.id END) entered,")
                .raw(" COUNT(i.id) instanceCount,")
                .raw(" SUM(CASE WHEN i.status = 2 THEN 1 ELSE 0 END) completedCount,")
                .raw(" SUM(CASE WHEN i.status = 3 THEN 1 ELSE 0 END) exitedCount,")
                .raw(" SUM(CASE WHEN vvar.c > 0 THEN 1 ELSE 0 END) varianceInstanceCount,")
                .raw(" IFNULL(SUM(vexec.total), 0) execTotal, IFNULL(SUM(vexec.varc), 0) execVariance")
                .raw(" FROM his_inp_visit v").raw(extraJoin)
                .raw(" LEFT JOIN his_pathway_instance i ON i.deleted = 0 AND i.tenant_id = ?")
                .p(tenant)
                .raw(" AND i.inp_visit_id = v.id AND i.start_date >= ? AND i.start_date < ?")
                .p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay())
                .raw(postJoin)
                .raw(" LEFT JOIN (SELECT e.instance_id iid, COUNT(*) total,")
                .raw(" SUM(CASE WHEN e.exec_status = 4 THEN 1 ELSE 0 END) varc")
                .raw(" FROM his_pathway_exec e WHERE e.deleted = 0 AND e.tenant_id = ? GROUP BY e.instance_id) vexec")
                .p(tenant)
                .raw(" ON vexec.iid = i.id")
                .raw(" LEFT JOIN (SELECT e2.instance_id iid, COUNT(*) c")
                .raw(" FROM his_pathway_exec e2 WHERE e2.deleted = 0 AND e2.tenant_id = ?")
                .raw(" AND e2.exec_status = 4 GROUP BY e2.instance_id) vvar ON vvar.iid = i.id")
                .p(tenant)
                .raw(" WHERE v.deleted = 0 AND v.tenant_id = ? AND v.visit_status = 4")
                .p(tenant)
                .raw(" AND v.discharge_date >= ? AND v.discharge_date < ?")
                .p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay());
        s.eq("v.org_id", orgId).eq("v.dept_id", c.deptId).eq("v.doctor_id", c.doctorId);
        // 入径率分母限定: 出院主诊断 ICD-10 三位类目命中启用模板 disease_code 类目集合
        s.raw(" AND EXISTS (SELECT 1 FROM his_inp_diagnosis md WHERE md.inp_visit_id = v.id")
                .raw(" AND md.deleted = 0 AND md.tenant_id = ? AND md.diag_type = 4 AND md.is_main = 1")
                .p(tenant)
                .raw(" AND md.diag_code IS NOT NULL AND LEFT(REPLACE(md.diag_code, '.', ''), 3) IN (")
                .raw(" SELECT DISTINCT LEFT(REPLACE(t2.disease_code, '.', ''), 3) FROM his_pathway_template t2")
                .raw(" WHERE t2.deleted = 0 AND t2.tenant_id = ? AND t2.status = 1")
                .p(tenant)
                .raw(" AND t2.disease_code IS NOT NULL AND t2.disease_code <> ''))");
        if (c.templateId != null) {
            s.raw(" AND i.template_id = ?").p(c.templateId);
        }
        s.raw(groupBy);
        List<Map<String, Object>> rows = safeList(s.sql(), s.params);
        for (Map<String, Object> row : rows) {
            long denominator = num(row.get("denominator"));
            long entered = num(row.get("entered"));
            long instanceCount = num(row.get("instanceCount"));
            long completedCount = num(row.get("completedCount"));
            long exitedCount = num(row.get("exitedCount"));
            long varianceInstanceCount = num(row.get("varianceInstanceCount"));
            long execTotal = num(row.get("execTotal"));
            long execVariance = num(row.get("execVariance"));
            row.put("enterRate", pct(entered, denominator));
            row.put("completeRate", pct(completedCount, instanceCount));
            row.put("exitRate", pct(exitedCount, instanceCount));
            row.put("caseVarianceRate", pct(varianceInstanceCount, instanceCount));
            row.put("itemVarianceRate", pct(execVariance, execTotal));
        }
        return rows;
    }

    /* ==================== 三、月度趋势 ==================== */

    /** 月度趋势: 入径例数/完成率/病例变异率按月(按入径 start_date 归月), 窗口缺省近12个月 */
    public Map<String, Object> trend(String from, String to, Long deptId, Long doctorId, Long templateId, Long orgId) {
        Crit c = new Crit();
        c.resolveRange(from, to, 365);
        long tenant = tenantId();
        Sql s = new Sql();
        s.raw("SELECT DATE_FORMAT(i.start_date, '%Y-%m') ym,")
                .raw(" COUNT(*) enterCount,")
                .raw(" SUM(CASE WHEN i.status = 2 THEN 1 ELSE 0 END) completedCount,")
                .raw(" SUM(CASE WHEN i.status = 3 THEN 1 ELSE 0 END) exitedCount,")
                .raw(" SUM(CASE WHEN EXISTS (SELECT 1 FROM his_pathway_exec e WHERE e.deleted = 0")
                .raw(" AND e.tenant_id = ? AND e.instance_id = i.id AND e.exec_status = 4) THEN 1 ELSE 0 END) varianceInstanceCount")
                .p(tenant)
                .raw(" FROM his_pathway_instance i")
                .raw(" JOIN his_inp_visit v ON v.id = i.inp_visit_id AND v.deleted = 0 AND v.tenant_id = ?")
                .p(tenant)
                .raw(" WHERE i.deleted = 0 AND i.tenant_id = ? AND i.start_date >= ? AND i.start_date < ?")
                .p(tenant)
                .p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay());
        s.eq("i.org_id", orgId).eq("i.template_id", c.templateId)
                .eq("v.dept_id", c.deptId).eq("i.doctor_id", c.doctorId)
                .raw(" GROUP BY ym ORDER BY ym");
        List<Map<String, Object>> rows = safeList(s.sql(), s.params);
        for (Map<String, Object> row : rows) {
            long enterCount = num(row.get("enterCount"));
            row.put("completeRate", pct(num(row.get("completedCount")), enterCount));
            row.put("exitRate", pct(num(row.get("exitedCount")), enterCount));
            row.put("caseVarianceRate", pct(num(row.get("varianceInstanceCount")), enterCount));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", String.valueOf(c.from));
        out.put("to", String.valueOf(c.to));
        out.put("months", rows);
        return out;
    }

    /* ==================== 四、变异/退出原因柏拉图 ==================== */

    /** 变异原因分类柏拉图: 窗口内(exec_date 落窗)变异 exec 按 variance_type 计数降序+累计占比 */
    public Map<String, Object> variancePareto(String from, String to, Long deptId, Long templateId, Long orgId) {
        Crit c = new Crit();
        c.resolveRange(from, to, 90);
        long tenant = tenantId();
        Sql s = new Sql();
        s.raw("SELECT IFNULL(e.variance_type, '') code, IFNULL(e.variance_type_name, '").raw(UNCLASSIFIED).raw("') name, COUNT(*) cnt")
                .raw(" FROM his_pathway_exec e")
                .raw(" JOIN his_pathway_instance i ON i.id = e.instance_id AND i.deleted = 0")
                .raw(" JOIN his_inp_visit v ON v.id = i.inp_visit_id AND v.deleted = 0")
                .raw(" WHERE e.deleted = 0 AND e.tenant_id = ? AND e.exec_status = 4 AND e.exec_date >= ? AND e.exec_date <= ?")
                .p(tenant).p(c.from).p(c.to);
        s.eq("e.org_id", orgId).eq("i.org_id", orgId).eq("v.dept_id", deptId).eq("i.template_id", templateId)
                .raw(" GROUP BY code, name ORDER BY cnt DESC");
        return paretoPack(safeList(s.sql(), s.params));
    }

    /** 退出原因分类柏拉图: 窗口内退出实例(update_time 落窗)按 exit_type 计数降序+累计占比 */
    public Map<String, Object> exitPareto(String from, String to, Long deptId, Long templateId, Long orgId) {
        Crit c = new Crit();
        c.resolveRange(from, to, 90);
        long tenant = tenantId();
        Sql s = new Sql();
        s.raw("SELECT IFNULL(i.exit_type, '') code, IFNULL(i.exit_type_name, '").raw(UNCLASSIFIED).raw("') name, COUNT(*) cnt")
                .raw(" FROM his_pathway_instance i")
                .raw(" JOIN his_inp_visit v ON v.id = i.inp_visit_id AND v.deleted = 0")
                .raw(" WHERE i.deleted = 0 AND i.tenant_id = ? AND i.status = 3 AND i.update_time >= ? AND i.update_time < ?")
                .p(tenant).p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay());
        s.eq("i.org_id", orgId).eq("v.dept_id", deptId).eq("i.template_id", templateId)
                .raw(" GROUP BY code, name ORDER BY cnt DESC");
        return paretoPack(safeList(s.sql(), s.params));
    }

    /** 柏拉图打包: 计数降序 + 占比/累计占比(应用层计算), 附高频变异病种 Top10 */
    private Map<String, Object> paretoPack(List<Map<String, Object>> rows) {
        long total = 0;
        for (Map<String, Object> row : rows) {
            total += num(row.get("cnt"));
        }
        long cumulative = 0;
        for (Map<String, Object> row : rows) {
            long cnt = num(row.get("cnt"));
            cumulative += cnt;
            row.put("rate", pct(cnt, total));
            row.put("cumulativeRate", pct(cumulative, total));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("items", rows);
        return out;
    }

    /** 高频变异病种 Top N(变异 exec 条数与涉及实例数, 按 exec_date 落窗) */
    public List<Map<String, Object>> topVarianceDisease(Crit c, Long orgId, long tenant, int limit) {
        Sql s = new Sql();
        s.raw("SELECT t.id templateId, t.pathway_name pathwayName, t.disease_name diseaseName,")
                .raw(" COUNT(*) varianceCount, COUNT(DISTINCT i.id) varianceInstanceCount")
                .raw(" FROM his_pathway_exec e")
                .raw(" JOIN his_pathway_instance i ON i.id = e.instance_id AND i.deleted = 0")
                .raw(" JOIN his_pathway_template t ON t.id = i.template_id AND t.deleted = 0")
                .raw(" WHERE e.deleted = 0 AND e.tenant_id = ? AND e.exec_status = 4 AND e.exec_date >= ? AND e.exec_date <= ?")
                .p(tenant).p(c.from).p(c.to);
        s.eq("e.org_id", orgId).eq("i.org_id", orgId).eq("i.template_id", c.templateId)
                .raw(" GROUP BY t.id, t.pathway_name, t.disease_name ORDER BY varianceCount DESC LIMIT " + Math.max(1, Math.min(limit, 50)));
        return safeList(s.sql(), s.params);
    }

    /** 柏拉图端点组合: 变异构成 + 高频变异病种 Top10 */
    public Map<String, Object> varianceParetoWithTop(String from, String to, Long deptId, Long templateId, Long orgId) {
        Map<String, Object> out = variancePareto(from, to, deptId, templateId, orgId);
        Crit c = new Crit();
        c.resolveRange(from, to, 90);
        c.deptId = deptId;
        c.templateId = templateId;
        out.put("topDiseases", topVarianceDisease(c, orgId, tenantId(), 10));
        return out;
    }

    /* ==================== 五、效率与费用(三口径对比) ==================== */

    /**
     * 效率费用: 入径完成组(窗口内出院且实例 status=2)/非入径同病种出院组(类目命中队列无实例)/
     * 模板标准组(avg_length·total_cost 按窗口内入径实例所用模板版本加权), 给出均值差与达标符合率
     * (完成组中 住院日<=标准 且 费用<=标准 的例数占完成组比)。
     */
    public Map<String, Object> efficiency(String from, String to, Long deptId, Long templateId, Long orgId) {
        Crit c = new Crit();
        c.resolveRange(from, to, 90);
        long tenant = tenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", String.valueOf(c.from));
        out.put("to", String.valueOf(c.to));

        // 入径完成组: 平均住院日/费用/例数
        Sql a = new Sql();
        a.raw("SELECT COUNT(*) caseCount, AVG(DATEDIFF(v.discharge_date, v.admit_date)) avgLos, AVG(v.total_cost) avgCost")
                .raw(" FROM his_pathway_instance i")
                .raw(" JOIN his_inp_visit v ON v.id = i.inp_visit_id AND v.deleted = 0")
                .raw(" WHERE i.deleted = 0 AND i.tenant_id = ? AND i.status = 2 AND i.start_date >= ? AND i.start_date < ?")
                .p(tenant).p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay())
                .raw(" AND v.visit_status = 4 AND v.discharge_date >= ? AND v.discharge_date < ?")
                .p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay());
        a.eq("i.org_id", orgId).eq("v.org_id", orgId).eq("v.dept_id", deptId).eq("i.template_id", templateId);
        out.put("pathwayFinished", effPack(firstRow(safeList(a.sql(), a.params))));

        // 非入径同病种出院组: 类目命中队列且无关联实例
        Sql b = new Sql();
        b.raw("SELECT COUNT(*) caseCount, AVG(DATEDIFF(v.discharge_date, v.admit_date)) avgLos, AVG(v.total_cost) avgCost")
                .raw(" FROM his_inp_visit v WHERE v.deleted = 0 AND v.tenant_id = ? AND v.visit_status = 4")
                .raw(" AND v.discharge_date >= ? AND v.discharge_date < ?")
                .p(tenant).p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay())
                .raw(" AND NOT EXISTS (SELECT 1 FROM his_pathway_instance i2 WHERE i2.inp_visit_id = v.id")
                .raw(" AND i2.deleted = 0 AND i2.tenant_id = ?)").p(tenant)
                .raw(" AND EXISTS (SELECT 1 FROM his_inp_diagnosis md WHERE md.inp_visit_id = v.id")
                .raw(" AND md.deleted = 0 AND md.tenant_id = ? AND md.diag_type = 4 AND md.is_main = 1").p(tenant)
                .raw(" AND md.diag_code IS NOT NULL AND LEFT(REPLACE(md.diag_code, '.', ''), 3) IN (")
                .raw(" SELECT DISTINCT LEFT(REPLACE(t2.disease_code, '.', ''), 3) FROM his_pathway_template t2")
                .raw(" WHERE t2.deleted = 0 AND t2.tenant_id = ? AND t2.status = 1").p(tenant)
                .raw(" AND t2.disease_code IS NOT NULL AND t2.disease_code <> ''))");
        b.eq("v.org_id", orgId).eq("v.dept_id", deptId);
        Map<String, Object> non = effPack(firstRow(safeList(b.sql(), b.params)));
        if (templateId != null) {
            // 限定单病种时, 对照组再按该模板诊断类目收窄
            Sql f = new Sql();
            f.raw("SELECT COUNT(*) caseCount, AVG(DATEDIFF(v.discharge_date, v.admit_date)) avgLos, AVG(v.total_cost) avgCost")
                    .raw(" FROM his_inp_visit v JOIN his_inp_diagnosis md ON md.inp_visit_id = v.id")
                    .raw(" AND md.deleted = 0 AND md.tenant_id = ? AND md.diag_type = 4 AND md.is_main = 1").p(tenant)
                    .raw(" JOIN his_pathway_template t3 ON t3.id = ? AND t3.deleted = 0").p(templateId)
                    .raw(" AND LEFT(REPLACE(md.diag_code, '.', ''), 3) = LEFT(REPLACE(t3.disease_code, '.', ''), 3)")
                    .raw(" WHERE v.deleted = 0 AND v.tenant_id = ? AND v.visit_status = 4")
                    .p(tenant)
                    .raw(" AND v.discharge_date >= ? AND v.discharge_date < ?")
                    .p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay())
                    .raw(" AND NOT EXISTS (SELECT 1 FROM his_pathway_instance i2 WHERE i2.inp_visit_id = v.id")
                    .raw(" AND i2.deleted = 0 AND i2.tenant_id = ?)").p(tenant);
            f.eq("v.org_id", orgId).eq("v.dept_id", deptId);
            non = effPack(firstRow(safeList(f.sql(), f.params)));
        }
        out.put("nonPathwaySameDisease", non);

        // 模板标准组: 窗口内入径实例所用模板 avg_length/total_cost 按实例数加权
        Sql t = new Sql();
        t.raw("SELECT SUM(t.avg_length * n.cnt) / SUM(n.cnt) stdLos, SUM(t.total_cost * n.cnt) / SUM(n.cnt) stdCost")
                .raw(" FROM (SELECT i.template_id tid, COUNT(*) cnt FROM his_pathway_instance i")
                .raw(" WHERE i.deleted = 0 AND i.tenant_id = ? AND i.start_date >= ? AND i.start_date < ?")
                .p(tenant).p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay());
        t.eq("i.org_id", orgId).eq("i.template_id", templateId)
                .raw(" GROUP BY i.template_id) n")
                .raw(" JOIN his_pathway_template t ON t.id = n.tid AND t.deleted = 0");
        Map<String, Object> stdRow = firstRow(safeList(t.sql(), t.params));
        Map<String, Object> std = new LinkedHashMap<>();
        std.put("stdLos", round1(dbl(stdRow.get("stdLos"))));
        std.put("stdCost", round2(dbl(stdRow.get("stdCost"))));
        out.put("templateStandard", std);

        // 均值差与达标符合率
        Map<String, Object> finished = out.get("pathwayFinished") instanceof Map
                ? asMap(out.get("pathwayFinished")) : new LinkedHashMap<String, Object>();
        double fLos = dbl(finished.get("avgLos"));
        double fCost = dbl(finished.get("avgCost"));
        double sLos = dbl(std.get("stdLos"));
        double sCost = dbl(std.get("stdCost"));
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("losVsStandard", round1(fLos - sLos));
        diff.put("costVsStandard", round2(fCost - sCost));
        diff.put("losVsNonPathway", round1(fLos - dbl(non.get("avgLos"))));
        diff.put("costVsNonPathway", round2(fCost - dbl(non.get("avgCost"))));
        out.put("diff", diff);
        Sql ok = new Sql();
        ok.raw("SELECT COUNT(*) totalCnt, SUM(CASE WHEN DATEDIFF(v.discharge_date, v.admit_date) <= COALESCE(t.avg_length, 9999)")
                .raw(" AND v.total_cost <= COALESCE(t.total_cost, 1e12) THEN 1 ELSE 0 END) AS okCnt")
                .raw(" FROM his_pathway_instance i")
                .raw(" JOIN his_inp_visit v ON v.id = i.inp_visit_id AND v.deleted = 0")
                .raw(" JOIN his_pathway_template t ON t.id = i.template_id AND t.deleted = 0")
                .raw(" WHERE i.deleted = 0 AND i.tenant_id = ? AND i.status = 2 AND i.start_date >= ? AND i.start_date < ?")
                .p(tenant).p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay());
        ok.eq("i.org_id", orgId).eq("v.dept_id", deptId).eq("i.template_id", templateId);
        Map<String, Object> okRow = firstRow(safeList(ok.sql(), ok.params));
        okRow.put("complianceRate", pct(num(okRow.get("okCnt")), num(okRow.get("totalCnt"))));
        out.put("standardCompliance", okRow);
        return out;
    }

    private Map<String, Object> effPack(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseCount", num(row.get("caseCount")));
        out.put("avgLos", round1(dbl(row.get("avgLos"))));
        out.put("avgCost", round2(dbl(row.get("avgCost"))));
        return out;
    }

    /* ==================== 六、病种覆盖 ==================== */

    /** 病种覆盖: 启用/发布模板数·覆盖科室数 + 各病种入径占其出院比(推广度, 窗口默认近90天) */
    public Map<String, Object> coverage(String from, String to, Long orgId) {
        Crit c = new Crit();
        c.resolveRange(from, to, 90);
        long tenant = tenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", String.valueOf(c.from));
        out.put("to", String.valueOf(c.to));
        out.put("enabledTemplateCount", countOrZero("SELECT COUNT(*) FROM his_pathway_template t"
                        + " WHERE t.deleted = 0 AND t.tenant_id = ? AND t.status = 1" + orgSql("t.org_id", orgId),
                args(tenant, orgId)));
        out.put("publishedVersionSum", scalarLong("SELECT IFNULL(SUM(t.version), 0) FROM his_pathway_template t"
                        + " WHERE t.deleted = 0 AND t.tenant_id = ? AND t.status = 1" + orgSql("t.org_id", orgId),
                args(tenant, orgId)));
        out.put("coveredDeptCount", countOrZero("SELECT COUNT(DISTINCT t.dept_id) FROM his_pathway_template t"
                        + " WHERE t.deleted = 0 AND t.tenant_id = ? AND t.status = 1 AND t.dept_id IS NOT NULL"
                        + orgSql("t.org_id", orgId), args(tenant, orgId)));
        out.put("usedTemplateCount", countOrZero("SELECT COUNT(DISTINCT i.template_id) FROM his_pathway_instance i"
                        + " WHERE i.deleted = 0 AND i.tenant_id = ? AND i.start_date >= ? AND i.start_date < ?"
                        + orgSql("i.org_id", orgId),
                args(tenant, c.from.atStartOfDay(), c.to.plusDays(1).atStartOfDay(), orgId)));
        // 各病种推广度: 该模板入径实例数 / 该类目窗口内出院数
        Sql s = new Sql();
        s.raw("SELECT t.id templateId, t.pathway_name pathwayName, t.disease_code diseaseCode,")
                .raw(" t.disease_name diseaseName, t.dept_id deptId, IFNULL(d.dept_name, '未指定') deptName,")
                .raw(" (SELECT COUNT(*) FROM his_pathway_instance i WHERE i.deleted = 0 AND i.tenant_id = ?")
                .p(tenant)
                .raw(" AND i.template_id = t.id AND i.start_date >= ? AND i.start_date < ?)")
                .p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay())
                .raw(" enteredCount,")
                .raw(" (SELECT COUNT(DISTINCT v.id) FROM his_inp_visit v")
                .raw(" JOIN his_inp_diagnosis md ON md.inp_visit_id = v.id AND md.deleted = 0 AND md.tenant_id = ?")
                .p(tenant)
                .raw(" AND md.diag_type = 4 AND md.is_main = 1 AND md.diag_code IS NOT NULL")
                .raw(" WHERE v.deleted = 0 AND v.tenant_id = ? AND v.visit_status = 4")
                .p(tenant)
                .raw(" AND v.discharge_date >= ? AND v.discharge_date < ?")
                .p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay())
                .raw(" AND LEFT(REPLACE(md.diag_code, '.', ''), 3) = LEFT(REPLACE(t.disease_code, '.', ''), 3)")
                .raw(" AND (? IS NULL OR v.org_id = ?))")
                .p(orgId).p(orgId)
                .raw(" denominator,")
                .raw(" (SELECT COUNT(*) FROM his_pathway_exec e")
                .raw(" JOIN his_pathway_instance i2 ON i2.id = e.instance_id AND i2.deleted = 0 AND i2.template_id = t.id")
                .raw(" WHERE e.deleted = 0 AND e.tenant_id = ? AND e.exec_status = 4) varianceExecCount")
                .p(tenant)
                .raw(" FROM his_pathway_template t")
                .raw(" LEFT JOIN his_dept d ON d.id = t.dept_id AND d.deleted = 0")
                .raw(" WHERE t.deleted = 0 AND t.tenant_id = ? AND t.status = 1")
                .p(tenant);
        s.eq("t.org_id", orgId).raw(" ORDER BY enteredCount DESC");
        List<Map<String, Object>> rows = safeList(s.sql(), s.params);
        for (Map<String, Object> row : rows) {
            row.put("coverageRate", pct(num(row.get("enteredCount")), num(row.get("denominator"))));
        }
        out.put("diseases", rows);
        return out;
    }

    /* ==================== 七、病例明细 ==================== */

    /** 病例明细钻取(分页): 入径/状态/天数/变异项数/实际住院日/实际费用, 按出径先后倒序 */
    public Map<String, Object> detail(String from, String to, Long deptId, Long doctorId, Long templateId,
                                      Integer status, Integer page, Integer size, Long orgId) {
        Crit c = new Crit();
        c.resolveRange(from, to, 90);
        long tenant = tenantId();
        int p = page == null || page < 1 ? 1 : page;
        int sz = size == null || size < 1 ? 20 : Math.min(size, 200);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", p);
        out.put("size", sz);

        Sql base = new Sql();
        base.raw(" WHERE i.deleted = 0 AND i.tenant_id = ? AND i.start_date >= ? AND i.start_date < ?")
                .p(tenant).p(c.from.atStartOfDay()).p(c.to.plusDays(1).atStartOfDay());
        base.eq("i.org_id", orgId).eq("v.dept_id", deptId).eq("i.doctor_id", doctorId).eq("i.template_id", templateId);
        if (status != null) {
            base.raw(" AND i.status = ?").p(status);
        }
        String joins = " FROM his_pathway_instance i"
                + " JOIN his_inp_visit v ON v.id = i.inp_visit_id AND v.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0"
                + " LEFT JOIN his_staff st ON st.id = i.doctor_id AND st.deleted = 0"
                + " LEFT JOIN his_pathway_template t ON t.id = i.template_id AND t.deleted = 0";
        out.put("total", scalarLong("SELECT COUNT(*)" + joins + base.sql(), base.params));

        Sql q = new Sql();
        q.raw("SELECT i.id instanceId, p.name patientName, v.inp_no inpNo,")
                .raw(" d.dept_name deptName, st.staff_name doctorName, t.pathway_name pathwayName,")
                .raw(" DATE_FORMAT(i.start_date, '%Y-%m-%d') startDate,")
                .raw(" DATE_FORMAT(i.end_date, '%Y-%m-%d') endDate, i.status, i.current_day currentDay,")
                .raw(" (SELECT COUNT(*) FROM his_pathway_exec e WHERE e.deleted = 0 AND e.tenant_id = ?")
                .p(tenant)
                .raw(" AND e.instance_id = i.id AND e.exec_status = 4) varianceCount,")
                .raw(" (SELECT COUNT(*) FROM his_pathway_exec e2 WHERE e2.deleted = 0 AND e2.tenant_id = ?")
                .p(tenant)
                .raw(" AND e2.instance_id = i.id) execCount,")
                .raw(" CASE WHEN v.discharge_date IS NULL THEN NULL")
                .raw(" ELSE DATEDIFF(v.discharge_date, v.admit_date) END losDays,")
                .raw(" DATE_FORMAT(v.discharge_date, '%Y-%m-%d') dischargeDate, v.total_cost totalCost")
                .raw(joins).raw(base.sql()).addAll(base.params)
                .raw(" ORDER BY i.start_date DESC, i.id DESC LIMIT ? OFFSET ?")
                .p(sz).p((p - 1) * sz);
        List<Map<String, Object>> rows = safeList(q.sql(), q.params);
        for (Map<String, Object> row : rows) {
            row.put("totalCost", round2(dbl(row.get("totalCost"))));
        }
        out.put("records", rows);
        return out;
    }

    /* ==================== 内部工具 ==================== */

    /**
     * SQL 构建器: SQL 片段经 raw 原样拼接(占位符 '?' 直接写在 raw 文本里),
     * 参数经 p 按其在 SQL 中出现的同一顺序绑定到 params, 二者一一对应。
     * 注意: 每个 p(v) 必须对应前面 raw 文本中的一个 '?'(eq 自带 '?'/参数),
     * sql() 内做占位符与参数数量一致性自检。杜绝"p 再追加 '?'"导致占位符翻倍。
     */
    private static final class Sql {
        private final StringBuilder sb = new StringBuilder();
        private final List<Object> params = new ArrayList<>();

        Sql raw(String part) {
            sb.append(part);
            return this;
        }

        /** 绑定一个参数到 raw 文本中下一个 '?'(不再额外追加 '?') */
        Sql p(Object v) {
            params.add(v);
            return this;
        }

        /** 批量并入另一构建器已累积的参数(用于把 base 过滤参数按位并入 q, 与嵌入的 base.sql() 占位符对齐) */
        Sql addAll(List<Object> more) {
            params.addAll(more);
            return this;
        }

        /** 值非空时追加 " AND col = ?" 并绑参(自带占位符, 与 p 的"只绑值"互补) */
        Sql eq(String col, Object v) {
            if (v != null) {
                sb.append(" AND ").append(col).append(" = ?");
                params.add(v);
            }
            return this;
        }

        String sql() {
            int ph = 0;
            for (int i = 0; i < sb.length(); i++) {
                if (sb.charAt(i) == '?') {
                    ph++;
                }
            }
            if (ph != params.size()) {
                log.warn("临床路径统计 SQL 占位符({})与参数({})数量不一致, 请核查: {}", ph, params.size(), sb);
            }
            return sb.toString();
        }
    }

    /** 计数查询(异常时归零, 保证统计接口可用性) */
    private long countOrZero(String sql, List<Object> args) {
        try {
            Long cnt = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
            return cnt == null ? 0L : cnt;
        } catch (DataAccessException e) {
            log.warn("临床路径统计计数失败(归零): {}", e.getMessage());
            return 0L;
        }
    }

    private long scalarLong(String sql, List<Object> args) {
        try {
            Long v = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
            return v == null ? 0L : v;
        } catch (DataAccessException e) {
            log.warn("临床路径统计标量查询失败(归零): {}", e.getMessage());
            return 0L;
        }
    }

    /** 列表查询(异常时空集, 单图失败不拖垮整个报表) */
    private List<Map<String, Object>> safeList(String sql, List<Object> args) {
        try {
            return jdbcTemplate.queryForList(sql, args.toArray());
        } catch (DataAccessException e) {
            log.warn("临床路径统计列表查询失败(置空): {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? new LinkedHashMap<String, Object>() : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
    }

    /** 排行: 按 key 降序取前 limit(剔除分母为0的行) */
    private static List<Map<String, Object>> topRank(List<Map<String, Object>> rows, String key, int limit) {
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.removeIf(r -> num(r.get("denominator")) <= 0 && !"completeRate".equals(key));
        sorted.sort((x, y) -> Double.compare(num(y.get(key)), num(x.get(key))));
        return sorted.size() > limit ? new ArrayList<>(sorted.subList(0, limit)) : sorted;
    }

    /** 机构过滤 SQL(orgId=null 表示全部机构, 不加条件) */
    private static String orgSql(String col, Long orgId) {
        return orgId == null ? "" : " AND " + col + " = ?";
    }

    /** 参数表: tenant [org] 常规组合 */
    private static List<Object> args(Object... values) {
        List<Object> list = new ArrayList<>();
        for (Object v : values) {
            if (v != null) {
                list.add(v);
            }
        }
        return list;
    }

    private static long num(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number) {
            return Math.round(((Number) v).doubleValue());
        }
        try {
            return Math.round(Double.parseDouble(String.valueOf(v)));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double dbl(Object v) {
        if (v == null) {
            return 0.0;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** 百分比率值(0-1, 4位小数), 分母0返回0 */
    private static double pct(long n, long d) {
        return d <= 0 ? 0.0 : Math.round(n * 10000.0 / d) / 10000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
