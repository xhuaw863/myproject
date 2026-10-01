package com.yb.hi.service.mr;

import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病案统计报表服务(P2 报表统计): 基于编目侧 his_mr_catalog/diag/oper 的多维汇总, 供常用/科室/诊断/手术/住院日/质量报表与 Excel 导出。
 * 铁律: 只读编目侧快照聚合, 不回写临床首页; 机构隔离 scopeOrgId + 出院日期区间过滤。
 * 报表均为查询态(无落库), 与"上报三段闭环"(MrSubmitService)分离。
 */
@Slf4j
@Service
public class MrReportService {

    /** 支持的报表类型。 */
    public static final List<String> TYPES = Arrays.asList(
            "overview", "byDept", "diagTop", "operTop", "losDist", "qualityDist");

    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrReportService(JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    public List<String> types() {
        return TYPES;
    }

    /** 常用概览: 全院编目病案 KPI(状态分布/中医西医/平均住院日/平均质量评分)。 */
    public List<Map<String, Object>> overview(String from, String to) {
        StringBuilder where = baseWhere(from, to, null);
        return jdbcTemplate.queryForList(
                "SELECT COUNT(*) AS catalogTotal,"
                        + " SUM(CASE WHEN c.catalog_status = 1 THEN 1 ELSE 0 END) AS pendingCatalog,"
                        + " SUM(CASE WHEN c.catalog_status = 2 THEN 1 ELSE 0 END) AS cataloging,"
                        + " SUM(CASE WHEN c.catalog_status = 3 THEN 1 ELSE 0 END) AS cataloged,"
                        + " SUM(CASE WHEN c.audit_status = 2 THEN 1 ELSE 0 END) AS audited,"
                        + " SUM(CASE WHEN c.audit_status = 3 THEN 1 ELSE 0 END) AS confirmed,"
                        + " SUM(CASE WHEN c.lock_status = 1 THEN 1 ELSE 0 END) AS locked,"
                        + " SUM(CASE WHEN c.is_tcm = 1 THEN 1 ELSE 0 END) AS tcmCount,"
                        + " SUM(CASE WHEN c.is_tcm = 0 OR c.is_tcm IS NULL THEN 1 ELSE 0 END) AS westCount,"
                        + " ROUND(AVG(c.los_days), 1) AS avgLos,"
                        + " ROUND(AVG(c.quality_score), 1) AS avgQuality"
                        + " FROM his_mr_catalog c" + where,
                argsOf(from, to, null));
    }

    /** 按出院科室: 人数 + 编目状态分布 + 平均住院日 + 平均评分。 */
    public List<Map<String, Object>> byDept(String from, String to) {
        StringBuilder where = baseWhere(from, to, null);
        return jdbcTemplate.queryForList(
                "SELECT dd.dept_name AS deptName, dd.dept_code AS deptCode, COUNT(*) AS caseCount,"
                        + " SUM(CASE WHEN c.catalog_status = 3 THEN 1 ELSE 0 END) AS cataloged,"
                        + " SUM(CASE WHEN c.audit_status >= 2 THEN 1 ELSE 0 END) AS audited,"
                        + " SUM(CASE WHEN c.lock_status = 1 THEN 1 ELSE 0 END) AS locked,"
                        + " ROUND(AVG(c.los_days), 1) AS avgLos,"
                        + " ROUND(AVG(c.quality_score), 1) AS avgQuality"
                        + " FROM his_mr_catalog c"
                        + " LEFT JOIN his_dept dd ON dd.id = c.discharge_dept_id"
                        + where + " GROUP BY c.discharge_dept_id, dd.dept_name, dd.dept_code"
                        + " ORDER BY caseCount DESC",
                argsOf(from, to, null));
    }

    /** 主要诊断 TOP N: 取编目侧出院主诊编码/名称汇总。 */
    public List<Map<String, Object>> diagTop(String from, String to, int limit) {
        StringBuilder where = baseWhere(from, to, null);
        where.append(" AND c.main_diag_code IS NOT NULL AND c.main_diag_code <> ''");
        int lim = limit <= 0 ? 20 : Math.min(limit, 100);
        List<Object> args = new ArrayList<>(Arrays.asList(argsOf(from, to, null)));
        args.add(lim);
        return jdbcTemplate.queryForList(
                "SELECT c.main_diag_code AS diagCode, c.main_diag_name AS diagName, COUNT(*) AS caseCount"
                        + " FROM his_mr_catalog c" + where
                        + " GROUP BY c.main_diag_code, c.main_diag_name ORDER BY caseCount DESC LIMIT ?",
                args.toArray());
    }

    /** 手术操作 TOP N: 取 his_mr_oper 国临版编码/名称汇总(主手术优先)。 */
    public List<Map<String, Object>> operTop(String from, String to, int limit) {
        Long tid = TenantContext.require();
        Long scopeOrg = guard.scopeOrgId(null);
        int lim = limit <= 0 ? 20 : Math.min(limit, 100);
        StringBuilder sb = new StringBuilder(
                "SELECT o.clinical_code AS operCode, o.clinical_name AS operName, COUNT(*) AS caseCount,"
                        + " SUM(CASE WHEN o.main_flag = 1 THEN 1 ELSE 0 END) AS mainCount"
                        + " FROM his_mr_oper o JOIN his_mr_catalog c ON c.id = o.catalog_id AND c.deleted = 0"
                        + " WHERE o.deleted = 0 AND o.tenant_id = " + tid
                        + " AND o.clinical_code IS NOT NULL AND o.clinical_code <> ''");
        if (scopeOrg != null) {
            sb.append(" AND c.org_id = ").append(scopeOrg);
        }
        if (StringUtils.hasText(from)) {
            sb.append(" AND c.discharge_date >= '").append(esc(from)).append(" 00:00:00'");
        }
        if (StringUtils.hasText(to)) {
            sb.append(" AND c.discharge_date <= '").append(esc(to)).append(" 23:59:59'");
        }
        sb.append(" GROUP BY o.clinical_code, o.clinical_name ORDER BY caseCount DESC LIMIT ").append(lim);
        return jdbcTemplate.queryForList(sb.toString());
    }

    /** 住院日分布(指标): <7 / 7-14 / 15-30 / 31-60 / >60 天例数。 */
    public List<Map<String, Object>> losDist(String from, String to) {
        StringBuilder where = baseWhere(from, to, null);
        where.append(" AND c.los_days IS NOT NULL");
        return jdbcTemplate.queryForList(
                "SELECT CASE WHEN c.los_days < 7 THEN '1|<7天'"
                        + " WHEN c.los_days < 15 THEN '2|7-14天'"
                        + " WHEN c.los_days <= 30 THEN '3|15-30天'"
                        + " WHEN c.los_days <= 60 THEN '4|31-60天'"
                        + " ELSE '5|>60天' END AS bucket, COUNT(*) AS caseCount"
                        + " FROM his_mr_catalog c" + where
                        + " GROUP BY bucket ORDER BY bucket",
                argsOf(from, to, null));
    }

    /** 质量评分分布: <60 / 60-79 / 80-89 / 90-100。 */
    public List<Map<String, Object>> qualityDist(String from, String to) {
        StringBuilder where = baseWhere(from, to, null);
        where.append(" AND c.quality_score IS NOT NULL");
        return jdbcTemplate.queryForList(
                "SELECT CASE WHEN c.quality_score < 60 THEN '1|不合格(<60)'"
                        + " WHEN c.quality_score < 80 THEN '2|乙级(60-79)'"
                        + " WHEN c.quality_score < 90 THEN '3|甲下(80-89)'"
                        + " ELSE '4|甲级(90-100)' END AS bucket, COUNT(*) AS caseCount,"
                        + " ROUND(AVG(c.quality_score), 1) AS avgScore"
                        + " FROM his_mr_catalog c" + where
                        + " GROUP BY bucket ORDER BY bucket",
                argsOf(from, to, null));
    }

    /** 报表数据(通用): type 分派到对应查询。 */
    public List<Map<String, Object>> query(String type, String from, String to, int limit) {
        if (type == null) {
            throw new com.yb.hi.framework.common.BizException(400, "缺少报表类型");
        }
        switch (type) {
            case "overview":
                return overview(from, to);
            case "byDept":
                return byDept(from, to);
            case "diagTop":
                return diagTop(from, to, limit);
            case "operTop":
                return operTop(from, to, limit);
            case "losDist":
                return losDist(from, to);
            case "qualityDist":
                return qualityDist(from, to);
            default:
                throw new com.yb.hi.framework.common.BizException(400, "非法报表类型: " + type);
        }
    }

    /**
     * 导出数据集 {head, title}: head=List<List<String>>, rows=List<List<Object>>。供 EasyExcel 写出。
     */
    public Map<String, Object> exportData(String type, String from, String to, int limit) {
        List<Map<String, Object>> data = query(type, from, to, limit);
        Map<String, String> cols = columnsOf(type);
        List<List<String>> head = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> e : cols.entrySet()) {
            titles.add(e.getValue());
            keys.add(e.getKey());
            head.add(new ArrayList<>(Arrays.asList(e.getValue())));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : data) {
            List<Object> row = new ArrayList<>();
            for (String k : keys) {
                Object v = r.get(k);
                if ("bucket".equals(k) && v != null) {
                    String s = String.valueOf(v);
                    int bar = s.indexOf('|');
                    v = bar >= 0 ? s.substring(bar + 1) : s;
                }
                row.add(v);
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("title", titleOf(type));
        return out;
    }

    /** 报表列定义(有序): key(数据列) -> 中文表头。 */
    private static Map<String, String> columnsOf(String type) {
        Map<String, String> m = new LinkedHashMap<>();
        switch (type == null ? "" : type) {
            case "overview":
                m.put("catalogTotal", "编目病案总数");
                m.put("pendingCatalog", "待编目");
                m.put("cataloging", "编目中");
                m.put("cataloged", "已编目");
                m.put("audited", "已审核");
                m.put("confirmed", "已确认");
                m.put("locked", "已锁定");
                m.put("tcmCount", "中医病案");
                m.put("westCount", "西医病案");
                m.put("avgLos", "平均住院日");
                m.put("avgQuality", "平均质量评分");
                break;
            case "byDept":
                m.put("deptName", "出院科室");
                m.put("deptCode", "科室编码");
                m.put("caseCount", "病案数");
                m.put("cataloged", "已编目");
                m.put("audited", "已审核");
                m.put("locked", "已锁定");
                m.put("avgLos", "平均住院日");
                m.put("avgQuality", "平均质量评分");
                break;
            case "diagTop":
                m.put("diagCode", "主要诊断编码");
                m.put("diagName", "主要诊断名称");
                m.put("caseCount", "例数");
                break;
            case "operTop":
                m.put("operCode", "手术操作编码");
                m.put("operName", "手术操作名称");
                m.put("caseCount", "例数");
                m.put("mainCount", "主手术例数");
                break;
            case "losDist":
                m.put("bucket", "住院日区间");
                m.put("caseCount", "例数");
                break;
            case "qualityDist":
                m.put("bucket", "质量等级");
                m.put("caseCount", "例数");
                m.put("avgScore", "区间均分");
                break;
            default:
                break;
        }
        return m;
    }

    private static String titleOf(String type) {
        switch (type == null ? "" : type) {
            case "overview":
                return "病案总览";
            case "byDept":
                return "出院科室分布";
            case "diagTop":
                return "主要诊断构成";
            case "operTop":
                return "手术操作构成";
            case "losDist":
                return "住院日分布";
            case "qualityDist":
                return "质量评分分布";
            default:
                return "病案统计报表";
        }
    }

    // ---------- SQL 助手 ----------

    private StringBuilder baseWhere(String from, String to, Long deptId) {
        StringBuilder where = new StringBuilder(" WHERE c.deleted = 0 AND c.tenant_id = " + TenantContext.require());
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND c.org_id = ").append(scopeOrg);
        }
        if (StringUtils.hasText(from)) {
            where.append(" AND c.discharge_date >= '").append(esc(from)).append(" 00:00:00'");
        }
        if (StringUtils.hasText(to)) {
            where.append(" AND c.discharge_date <= '").append(esc(to)).append(" 23:59:59'");
        }
        if (deptId != null) {
            where.append(" AND c.discharge_dept_id = ").append(deptId);
        }
        return where;
    }

    private Object[] argsOf(String from, String to, Long deptId) {
        return new Object[0];
    }

    private static String esc(String s) {
        return s == null ? null : s.replace("'", "''");
    }
}
