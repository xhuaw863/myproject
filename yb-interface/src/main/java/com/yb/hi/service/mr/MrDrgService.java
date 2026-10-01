package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DRG/DIP 预分组与入组对比服务(P3-A): 对已编目病案按 his_inp_visit/his_inp_settle 的
 * drg_group_code/dip_code 展示分组结果, 支持同诊断入组差异对比。
 * 铁律: 只读关联查询, 不修改结算/就诊数据, 不回写临床首页。
 */
@Slf4j
@Service
public class MrDrgService {

    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrDrgService(JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /**
     * 已编目病案 DRG/DIP 分组列表(分页): JOIN catalog + visit + settle 取分组编码。
     * 过滤: deptId(出院科室), drgCode(DRG编码模糊), dipCode(DIP编码模糊), startDate/endDate(出院日期区间)。
     */
    public IPage<Map<String, Object>> listGrouped(long page, long size, Long deptId,
                                                   String drgCode, String dipCode,
                                                   String startDate, String endDate) {
        Long tid = TenantContext.require();
        Long scopeOrg = guard.scopeOrgId(null);
        StringBuilder where = new StringBuilder(" WHERE c.deleted = 0 AND c.tenant_id = ? AND c.catalog_status >= 3");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        if (scopeOrg != null) {
            where.append(" AND c.org_id = ?");
            args.add(scopeOrg);
        }
        if (deptId != null) {
            where.append(" AND c.discharge_dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(drgCode)) {
            where.append(" AND (v.drg_group_code LIKE ? OR s.drg_group_code LIKE ?)");
            String like = "%" + drgCode.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (StringUtils.hasText(dipCode)) {
            where.append(" AND s.dip_code LIKE ?");
            args.add("%" + dipCode.trim() + "%");
        }
        if (StringUtils.hasText(startDate)) {
            where.append(" AND c.discharge_date >= ?");
            args.add(startDate + " 00:00:00");
        }
        if (StringUtils.hasText(endDate)) {
            where.append(" AND c.discharge_date <= ?");
            args.add(endDate + " 23:59:59");
        }

        String base = " FROM his_mr_catalog c"
                + " LEFT JOIN his_inp_visit v ON v.id = c.visit_id AND v.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = c.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_dept dd ON dd.id = c.discharge_dept_id"
                + " LEFT JOIN his_inp_settle s ON s.inp_visit_id = c.visit_id AND s.deleted = 0 AND s.yb_status = 2";

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + base + where, Long.class, args.toArray());

        long p = Math.max(1, page);
        long sz = size <= 0 ? 20 : Math.min(size, 200);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz);
        pageArgs.add((p - 1) * sz);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.id AS catalogId, c.visit_id AS visitId, c.catalog_no AS catalogNo,"
                        + " p.name AS patientName, p.gender_name AS genderName, p.age,"
                        + " dd.dept_name AS dischargeDeptName,"
                        + " c.main_diag_code AS mainDiagCode, c.main_diag_name AS mainDiagName,"
                        + " c.los_days AS losDays, c.discharge_date AS dischargeDate,"
                        + " v.drg_group_code AS visitDrgCode,"
                        + " s.drg_group_code AS drgGroupCode, s.dip_code AS dipCode,"
                        + " s.total_amount AS totalAmount, s.fund_pay AS fundPay"
                        + base + where + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());

        Page<Map<String, Object>> result = new Page<>(p, sz);
        result.setRecords(rows);
        result.setTotal(total == null ? 0 : total);
        return result;
    }

    /**
     * 同诊断入组差异对比: 按 main_diag_code 聚合, 展示各诊断下不同 drg_group_code 分布与例数。
     * 参数: diagCode(精确过滤某诊断, 可空返回 TOP 诊断), limit(返回诊断条数, 默认20)。
     */
    public List<Map<String, Object>> comparisonByDiag(String diagCode, int limit) {
        Long tid = TenantContext.require();
        Long scopeOrg = guard.scopeOrgId(null);
        StringBuilder where = new StringBuilder(" WHERE c.deleted = 0 AND c.tenant_id = ? AND c.catalog_status >= 3"
                + " AND c.main_diag_code IS NOT NULL AND c.main_diag_code != ''");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        if (scopeOrg != null) {
            where.append(" AND c.org_id = ?");
            args.add(scopeOrg);
        }
        if (StringUtils.hasText(diagCode)) {
            where.append(" AND c.main_diag_code = ?");
            args.add(diagCode.trim());
        }

        // 按 main_diag_code + COALESCE(s.drg_group_code, v.drg_group_code) 分组
        String base = " FROM his_mr_catalog c"
                + " LEFT JOIN his_inp_visit v ON v.id = c.visit_id AND v.deleted = 0"
                + " LEFT JOIN his_inp_settle s ON s.inp_visit_id = c.visit_id AND s.deleted = 0 AND s.yb_status = 2";

        int lim = limit <= 0 ? 20 : Math.min(limit, 200);
        args.add(lim);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.main_diag_code AS diagCode, c.main_diag_name AS diagName,"
                        + " COALESCE(s.drg_group_code, v.drg_group_code) AS drgGroupCode,"
                        + " s.dip_code AS dipCode,"
                        + " COUNT(*) AS caseCount,"
                        + " ROUND(AVG(s.total_amount), 2) AS avgCost"
                        + base + where
                        + " GROUP BY c.main_diag_code, c.main_diag_name,"
                        + " COALESCE(s.drg_group_code, v.drg_group_code), s.dip_code"
                        + " ORDER BY c.main_diag_code, caseCount DESC"
                        + " LIMIT ?",
                args.toArray());
        return rows;
    }

    /**
     * DRG/DIP 入组概览汇总: 期内已编目病案总数/有分组/未分组/按DRG编码 TOP 分布。
     */
    public Map<String, Object> summary(String startDate, String endDate) {
        Long tid = TenantContext.require();
        Long scopeOrg = guard.scopeOrgId(null);
        StringBuilder where = new StringBuilder(" WHERE c.deleted = 0 AND c.tenant_id = ? AND c.catalog_status >= 3");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        if (scopeOrg != null) {
            where.append(" AND c.org_id = ?");
            args.add(scopeOrg);
        }
        if (StringUtils.hasText(startDate)) {
            where.append(" AND c.discharge_date >= ?");
            args.add(startDate + " 00:00:00");
        }
        if (StringUtils.hasText(endDate)) {
            where.append(" AND c.discharge_date <= ?");
            args.add(endDate + " 23:59:59");
        }

        String base = " FROM his_mr_catalog c"
                + " LEFT JOIN his_inp_visit v ON v.id = c.visit_id AND v.deleted = 0"
                + " LEFT JOIN his_inp_settle s ON s.inp_visit_id = c.visit_id AND s.deleted = 0 AND s.yb_status = 2";

        // 总数
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + base + where, Long.class, args.toArray());
        // 有DRG分组
        List<Object> args2 = new ArrayList<>(args);
        Long hasGroup = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + base + where + " AND COALESCE(s.drg_group_code, v.drg_group_code) IS NOT NULL"
                        + " AND COALESCE(s.drg_group_code, v.drg_group_code) != ''",
                Long.class, args2.toArray());
        // 有DIP
        List<Object> args3 = new ArrayList<>(args);
        Long hasDip = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + base + where + " AND s.dip_code IS NOT NULL AND s.dip_code != ''",
                Long.class, args3.toArray());

        // DRG TOP 分布
        List<Object> args4 = new ArrayList<>(args);
        args4.add(15);
        List<Map<String, Object>> drgDist = jdbcTemplate.queryForList(
                "SELECT COALESCE(s.drg_group_code, v.drg_group_code) AS drgCode, COUNT(*) AS caseCount"
                        + base + where
                        + " AND COALESCE(s.drg_group_code, v.drg_group_code) IS NOT NULL"
                        + " AND COALESCE(s.drg_group_code, v.drg_group_code) != ''"
                        + " GROUP BY drgCode ORDER BY caseCount DESC LIMIT ?",
                args4.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCataloged", total == null ? 0 : total);
        out.put("hasDrgGroup", hasGroup == null ? 0 : hasGroup);
        out.put("hasDip", hasDip == null ? 0 : hasDip);
        out.put("noGroup", (total == null ? 0 : total) - (hasGroup == null ? 0 : hasGroup));
        out.put("drgDistribution", drgDist);
        return out;
    }
}
