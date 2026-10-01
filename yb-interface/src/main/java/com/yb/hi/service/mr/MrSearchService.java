package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病案检索查询服务(P1-D): 快速检索 / 复合检索(四条件块并行) / 诊断手术检索 / 出院人数对比。
 * 铁律: 全在编目侧 his_mr_catalog(+his_mr_diag/his_mr_oper)检索, 不回写临床首页; 读接口 scopeOrgId 机构隔离。
 */
@Slf4j
@Service
public class MrSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrSearchService(JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /** 编目列表统一投影(附出院科室名/患者信息)。 */
    private static final String PROJECTION =
            "SELECT c.id, c.visit_id AS visitId, c.patient_id AS patientId, c.catalog_no AS catalogNo,"
                    + " p.name AS patientName, p.gender_name AS genderName, p.age,"
                    + " v.inp_no AS inpNo, dd.dept_name AS dischargeDeptName,"
                    + " c.admission_date AS admissionDate, c.discharge_date AS dischargeDate, c.los_days AS losDays,"
                    + " c.main_diag_code AS mainDiagCode, c.main_diag_name AS mainDiagName,"
                    + " c.catalog_status AS catalogStatus, c.audit_status AS auditStatus, c.lock_status AS lockStatus,"
                    + " c.cataloger_name AS catalogerName, c.quality_score AS qualityScore";
    private static final String FROM_JOIN =
            " FROM his_mr_catalog c"
                    + " LEFT JOIN his_patient p ON p.id = c.patient_id AND p.deleted = 0"
                    + " LEFT JOIN his_inp_visit v ON v.id = c.visit_id"
                    + " LEFT JOIN his_dept dd ON dd.id = c.discharge_dept_id";

    /** 租户 + 机构作用域的基础 WHERE(始终带 scopeOrg)。 */
    private String baseWhere(List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE c.deleted = 0 AND c.tenant_id = ?");
        args.add(TenantContext.require());
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND c.org_id = ?");
            args.add(scopeOrg);
        }
        return where.toString();
    }

    /* ==================== 快速检索 ==================== */

    /** 快速检索: 关键字跨 患者姓名/住院号/编目号/主要诊断(编码或名称) 模糊匹配, 可按出院科室过滤。 */
    public IPage<Map<String, Object>> quick(long page, long size, String keyword, Long deptId, Integer catalogStatus) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(baseWhere(args));
        if (deptId != null) {
            where.append(" AND c.discharge_dept_id = ?");
            args.add(deptId);
        }
        if (catalogStatus != null) {
            where.append(" AND c.catalog_status = ?");
            args.add(catalogStatus);
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            where.append(" AND (p.name LIKE ? OR v.inp_no LIKE ? OR c.catalog_no LIKE ?"
                    + " OR c.main_diag_name LIKE ? OR c.main_diag_code LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return page(PROJECTION, FROM_JOIN, where.toString(), " ORDER BY c.discharge_date DESC, c.id DESC", args, page, size);
    }

    /* ==================== 复合检索(四条件块并行) ==================== */

    /**
     * 复合检索: 患者/诊断/手术/就诊属性 四个条件块, 非空块按 logic(ALL=AND / ANY=OR) 组合。
     * 诊断/手术块用 EXISTS 关联子表, 命中其一即算该块成立。
     * req: {logic, page, size, patient:{name,inpNo,gender,ageMin,ageMax},
     *       diag:{code,name,type,main}, oper:{code,name,main},
     *       attr:{dischargeDeptId,catalogStatus,admitFrom,admitTo,dischargeFrom,dischargeTo,qualityMin,qualityMax}}
     */
    public IPage<Map<String, Object>> advanced(Map<String, Object> req) {
        if (req == null) {
            req = new LinkedHashMap<>();
        }
        long page = MrCatalogService.longOf(req.get("page")) == null ? 1 : MrCatalogService.longOf(req.get("page"));
        long size = MrCatalogService.longOf(req.get("size")) == null ? 20 : MrCatalogService.longOf(req.get("size"));
        boolean any = "ANY".equalsIgnoreCase(String.valueOf(req.get("logic")));
        String joiner = any ? " OR " : " AND ";

        List<Object> args = new ArrayList<>();
        String base = baseWhere(args);
        List<String> groups = new ArrayList<>();

        // 患者块
        Map<String, Object> patient = asMap(req.get("patient"));
        if (patient != null) {
            List<String> conds = new ArrayList<>();
            String name = trim(str(patient.get("name")));
            if (name != null) {
                conds.add("p.name LIKE ?");
                args.add("%" + name + "%");
            }
            String inpNo = trim(str(patient.get("inpNo")));
            if (inpNo != null) {
                conds.add("v.inp_no LIKE ?");
                args.add("%" + inpNo + "%");
            }
            String gender = trim(str(patient.get("gender")));
            if (gender != null) {
                conds.add("p.gender_name = ?");
                args.add(gender);
            }
            Integer ageMin = intOrNull(patient.get("ageMin"));
            Integer ageMax = intOrNull(patient.get("ageMax"));
            if (ageMin != null) {
                conds.add("p.age >= ?");
                args.add(ageMin);
            }
            if (ageMax != null) {
                conds.add("p.age <= ?");
                args.add(ageMax);
            }
            if (!conds.isEmpty()) {
                groups.add("(" + String.join(" AND ", conds) + ")");
            }
        }

        // 诊断块(EXISTS)
        Map<String, Object> diag = asMap(req.get("diag"));
        if (diag != null) {
            groups.add(existsDiag(diag, args));
        }
        // 手术块(EXISTS)
        Map<String, Object> oper = asMap(req.get("oper"));
        if (oper != null) {
            groups.add(existsOper(oper, args));
        }

        // 就诊属性块
        Map<String, Object> attr = asMap(req.get("attr"));
        if (attr != null) {
            List<String> conds = new ArrayList<>();
            Long deptId = longOf(attr.get("dischargeDeptId"));
            if (deptId != null) {
                conds.add("c.discharge_dept_id = ?");
                args.add(deptId);
            }
            Integer cstatus = intOrNull(attr.get("catalogStatus"));
            if (cstatus != null) {
                conds.add("c.catalog_status = ?");
                args.add(cstatus);
            }
            LocalDateTime admitFrom = ldt(attr.get("admitFrom"));
            LocalDateTime admitTo = ldtEnd(attr.get("admitTo"));
            if (admitFrom != null) {
                conds.add("c.admission_date >= ?");
                args.add(admitFrom);
            }
            if (admitTo != null) {
                conds.add("c.admission_date <= ?");
                args.add(admitTo);
            }
            LocalDateTime disFrom = ldt(attr.get("dischargeFrom"));
            LocalDateTime disTo = ldtEnd(attr.get("dischargeTo"));
            if (disFrom != null) {
                conds.add("c.discharge_date >= ?");
                args.add(disFrom);
            }
            if (disTo != null) {
                conds.add("c.discharge_date <= ?");
                args.add(disTo);
            }
            Integer qMin = intOrNull(attr.get("qualityMin"));
            Integer qMax = intOrNull(attr.get("qualityMax"));
            if (qMin != null) {
                conds.add("c.quality_score >= ?");
                args.add(qMin);
            }
            if (qMax != null) {
                conds.add("c.quality_score <= ?");
                args.add(qMax);
            }
            if (!conds.isEmpty()) {
                groups.add("(" + String.join(" AND ", conds) + ")");
            }
        }

        StringBuilder where = new StringBuilder(base);
        List<String> effective = new ArrayList<>();
        for (String g : groups) {
            if (g != null && !g.isEmpty()) {
                effective.add(g);
            }
        }
        if (!effective.isEmpty()) {
            where.append(" AND (").append(String.join(joiner, effective)).append(")");
        }
        return page(PROJECTION, FROM_JOIN, where.toString(), " ORDER BY c.discharge_date DESC, c.id DESC", args, page, size);
    }

    /** 诊断 EXISTS 片段(返回空串表示无有效条件)。 */
    private String existsDiag(Map<String, Object> diag, List<Object> args) {
        List<String> conds = new ArrayList<>();
        String code = trim(str(diag.get("code")));
        String name = trim(str(diag.get("name")));
        String type = trim(str(diag.get("type")));
        Integer main = intOrNull(diag.get("main"));
        StringBuilder sql = new StringBuilder("EXISTS (SELECT 1 FROM his_mr_diag dg WHERE dg.catalog_id = c.id AND dg.deleted = 0");
        if (code != null) {
            sql.append(" AND (dg.clinical_code LIKE ? OR dg.yb_code LIKE ?)");
            args.add("%" + code + "%");
            args.add("%" + code + "%");
        }
        if (name != null) {
            sql.append(" AND (dg.clinical_name LIKE ? OR dg.yb_name LIKE ?)");
            args.add("%" + name + "%");
            args.add("%" + name + "%");
        }
        if (type != null) {
            sql.append(" AND dg.diag_type = ?");
            args.add(type);
        }
        if (main != null) {
            sql.append(" AND dg.main_flag = ?");
            args.add(main);
        }
        sql.append(")");
        // 无任何诊断条件则视为空块
        if (code == null && name == null && type == null && main == null) {
            return "";
        }
        return sql.toString();
    }

    /** 手术 EXISTS 片段(返回空串表示无有效条件)。 */
    private String existsOper(Map<String, Object> oper, List<Object> args) {
        String code = trim(str(oper.get("code")));
        String name = trim(str(oper.get("name")));
        Integer main = intOrNull(oper.get("main"));
        if (code == null && name == null && main == null) {
            return "";
        }
        StringBuilder sql = new StringBuilder("EXISTS (SELECT 1 FROM his_mr_oper op WHERE op.catalog_id = c.id AND op.deleted = 0");
        if (code != null) {
            sql.append(" AND (op.clinical_code LIKE ? OR op.yb_code LIKE ?)");
            args.add("%" + code + "%");
            args.add("%" + code + "%");
        }
        if (name != null) {
            sql.append(" AND (op.clinical_name LIKE ? OR op.yb_name LIKE ?)");
            args.add("%" + name + "%");
            args.add("%" + name + "%");
        }
        if (main != null) {
            sql.append(" AND op.main_flag = ?");
            args.add(main);
        }
        sql.append(")");
        return sql.toString();
    }

    /* ==================== 诊断手术检索 ==================== */

    /** 诊断手术检索: 按编码/名称命中医嘱诊断或手术, 回显命中的诊断/手术摘要。scope=diag|oper|both(默认 both)。 */
    public IPage<Map<String, Object>> diagOper(long page, long size, String code, String name, String scope) {
        if (!StringUtils.hasText(code) && !StringUtils.hasText(name)) {
            throw new BizException(400, "请输入诊断/手术编码或名称");
        }
        boolean all = scope == null || "both".equalsIgnoreCase(scope);
        boolean useDiag = all || "diag".equalsIgnoreCase(scope);
        boolean useOper = all || "oper".equalsIgnoreCase(scope);

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(baseWhere(args));
        String like = null;
        if (StringUtils.hasText(code)) {
            like = "%" + code.trim() + "%";
        }
        String nameLike = StringUtils.hasText(name) ? "%" + name.trim() + "%" : null;

        List<String> exists = new ArrayList<>();
        List<Object> localArgs = new ArrayList<>();
        if (useDiag) {
            exists.add(existLike("his_mr_diag", "dg", "clinical_code", "yb_code", "clinical_name", "yb_name", like, nameLike, localArgs));
        }
        if (useOper) {
            exists.add(existLike("his_mr_oper", "op", "clinical_code", "yb_code", "clinical_name", "yb_name", like, nameLike, localArgs));
        }
        exists.removeIf(s -> s == null || s.isEmpty());
        if (exists.isEmpty()) {
            throw new BizException(400, "无效的检索范围");
        }
        where.append(" AND (").append(String.join(" OR ", exists)).append(")");
        args.addAll(localArgs);

        // 命中回显: 追加匹配到的第一条诊断/手术
        String extra = "";
        if (useDiag) {
            extra += ", (SELECT dg.clinical_code FROM his_mr_diag dg WHERE dg.catalog_id = c.id AND dg.deleted = 0"
                    + matchLike("dg.clinical_code", "dg.yb_code", "dg.clinical_name", "dg.yb_name", like, nameLike)
                    + " LIMIT 1) AS hitDiag";
        }
        if (useOper) {
            extra += ", (SELECT op.clinical_name FROM his_mr_oper op WHERE op.catalog_id = c.id AND op.deleted = 0"
                    + matchLike("op.clinical_code", "op.yb_code", "op.clinical_name", "op.yb_name", like, nameLike)
                    + " LIMIT 1) AS hitOper";
        }
        String select = PROJECTION + extra;
        return page(select, FROM_JOIN, where.toString(), " ORDER BY c.discharge_date DESC, c.id DESC", args, page, size);
    }

    /** 生成 EXISTS 片段(编码或名称 LIKE, 二者需同时满足非空条件按 AND)。 */
    private String existLike(String table, String al, String codeCol1, String codeCol2,
                             String nameCol1, String nameCol2, String codeLike, String nameLike, List<Object> args) {
        if (codeLike == null && nameLike == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("EXISTS (SELECT 1 FROM " + table + " " + al
                + " WHERE " + al + ".catalog_id = c.id AND " + al + ".deleted = 0");
        if (codeLike != null) {
            sb.append(" AND (").append(al).append(".").append(codeCol1).append(" LIKE ? OR ")
                    .append(al).append(".").append(codeCol2).append(" LIKE ?)");
            args.add(codeLike);
            args.add(codeLike);
        }
        if (nameLike != null) {
            sb.append(" AND (").append(al).append(".").append(nameCol1).append(" LIKE ? OR ")
                    .append(al).append(".").append(nameCol2).append(" LIKE ?)");
            args.add(nameLike);
            args.add(nameLike);
        }
        sb.append(")");
        return sb.toString();
    }

    /** 命中回显子查询的匹配条件(WHERE 之后追加 AND ...)。 */
    private String matchLike(String c1, String c2, String n1, String n2, String codeLike, String nameLike) {
        List<String> conds = new ArrayList<>();
        if (codeLike != null) {
            conds.add("(" + c1 + " LIKE '" + esc(codeLike) + "' OR " + c2 + " LIKE '" + esc(codeLike) + "')");
        }
        if (nameLike != null) {
            conds.add("(" + n1 + " LIKE '" + esc(nameLike) + "' OR " + n2 + " LIKE '" + esc(nameLike) + "')");
        }
        return conds.isEmpty() ? "" : (" AND " + String.join(" AND ", conds));
    }

    /* ==================== 出院人数对比 ==================== */

    /**
     * 出院人数对比: 按出院科室统计当期与对比期的出院病案数。
     * [from,to] 为当期; 可选 [compareFrom,compareTo] 为对比期(缺省=当期前一个等长区间)。
     * 基于编目侧 his_mr_catalog 的 discharge_date 统计。
     */
    public Map<String, Object> dischargeCompare(String from, String to, String compareFrom, String compareTo) {
        LocalDateTime curFrom = ldt(from);
        LocalDateTime curTo = ldtEnd(to);
        if (curFrom == null || curTo == null) {
            throw new BizException(400, "请填写当期起止日期");
        }
        LocalDateTime prvFrom = ldt(compareFrom);
        LocalDateTime prvTo = ldtEnd(compareTo);
        if (prvFrom == null || prvTo == null) {
            long days = java.time.Duration.between(curFrom, curTo).toDays() + 1;
            prvTo = curFrom.minusDays(1);
            prvFrom = prvTo.minusDays(days - 1);
        }
        Long tid = TenantContext.require();
        Long scopeOrg = guard.scopeOrgId(null);

        List<Map<String, Object>> cur = countByDept(tid, scopeOrg, curFrom, curTo);
        List<Map<String, Object>> prv = countByDept(tid, scopeOrg, prvFrom, prvTo);
        Map<String, Long> prvMap = new LinkedHashMap<>();
        for (Map<String, Object> r : prv) {
            prvMap.put(str(r.get("deptName")), asLong(r.get("cnt")));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long curTotal = 0;
        long prvTotal = 0;
        for (Map<String, Object> r : cur) {
            String dept = str(r.get("deptName"));
            long c = asLong(r.get("cnt"));
            long pv = prvMap.getOrDefault(dept, 0L);
            curTotal += c;
            prvTotal += pv;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deptName", dept);
            row.put("current", c);
            row.put("previous", pv);
            row.put("diff", c - pv);
            rows.add(row);
            prvMap.remove(dept);
        }
        // 对比期独有科室(当期无出院)也补一行, 便于发现下滑
        for (Map.Entry<String, Long> e : prvMap.entrySet()) {
            long pv = e.getValue();
            prvTotal += pv;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deptName", e.getKey());
            row.put("current", 0);
            row.put("previous", pv);
            row.put("diff", -pv);
            rows.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("current", curTotal);
        summary.put("previous", prvTotal);
        summary.put("diff", curTotal - prvTotal);
        summary.put("rate", prvTotal == 0 ? null : Math.round((curTotal - prvTotal) * 10000.0 / prvTotal) / 100.0);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("currentRange", new String[]{curFrom.toString(), curTo.toString()});
        res.put("previousRange", new String[]{prvFrom.toString(), prvTo.toString()});
        res.put("rows", rows);
        res.put("summary", summary);
        return res;
    }

    private List<Map<String, Object>> countByDept(Long tid, Long scopeOrg, LocalDateTime from, LocalDateTime to) {
        StringBuilder sql = new StringBuilder("SELECT IFNULL(dd.dept_name,'未知科室') AS deptName, COUNT(*) AS cnt"
                + " FROM his_mr_catalog c LEFT JOIN his_dept dd ON dd.id = c.discharge_dept_id"
                + " WHERE c.deleted = 0 AND c.tenant_id = ? AND c.discharge_date >= ? AND c.discharge_date <= ?");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        args.add(from);
        args.add(to);
        if (scopeOrg != null) {
            sql.append(" AND c.org_id = ?");
            args.add(scopeOrg);
        }
        sql.append(" GROUP BY deptName ORDER BY cnt DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /* ==================== 通用分页执行 ==================== */

    private IPage<Map<String, Object>> page(String select, String from, String where, String order,
                                            List<Object> args, long page, long size) {
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        long p = Math.max(1, page);
        long s = size <= 0 ? 20 : Math.min(size, 200);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(select + from + where + order + " LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setRecords(rows);
        result.setTotal(total == null ? 0 : total);
        return result;
    }

    // ---- 内部辅助 ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static Long longOf(Object o) {
        return MrCatalogService.longOf(o);
    }

    private static Integer intOrNull(Object o) {
        return MrCatalogService.intOrNull(o);
    }

    private static long asLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private static LocalDateTime ldtEnd(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.length() <= 10 && !s.isEmpty()) {
                try {
                    return java.time.LocalDate.parse(s.replace('T', ' ')).atTime(23, 59, 59);
                } catch (Exception e) {
                    return ldt(o);
                }
            }
        }
        return ldt(o);
    }

    private static LocalDateTime ldt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDateTime) {
            return (LocalDateTime) o;
        }
        if (o instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) o).toLocalDateTime();
        }
        if (o instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) o).atStartOfDay();
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        s = s.replace('T', ' ');
        try {
            if (s.length() <= 10) {
                // to-date 补齐到当天 23:59:59, from-date 由调用处按 >= 使用(此处统一 00:00)
                return java.time.LocalDate.parse(s).atStartOfDay();
            }
            if (s.length() == 16) {
                s = s + ":00";
            }
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }
}
