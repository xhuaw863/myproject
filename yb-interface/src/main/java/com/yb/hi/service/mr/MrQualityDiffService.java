package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.entity.mr.HisMrCatalog;
import com.yb.hi.entity.mr.HisMrDiag;
import com.yb.hi.entity.mr.HisMrOper;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.mr.HisMrCatalogMapper;
import com.yb.hi.mapper.mr.HisMrDiagMapper;
import com.yb.hi.mapper.mr.HisMrOperMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 质控前后对比服务(P3-B): 对同一份病案, 比较编目修订前(临床首页原始数据)与编目修订后
 * (当前编目态数据)的 MrQualityService.validate 结果差异, 展示新增/消除/仍存在的错误项。
 * 纯只读计算, 不落库, 不回写。
 */
@Slf4j
@Service
public class MrQualityDiffService {

    private final HisMrCatalogMapper catalogMapper;
    private final HisMrDiagMapper diagMapper;
    private final HisMrOperMapper operMapper;
    private final MrQualityService qualityService;
    private final JdbcTemplate jdbcTemplate;

    public MrQualityDiffService(HisMrCatalogMapper catalogMapper, HisMrDiagMapper diagMapper,
                                 HisMrOperMapper operMapper, MrQualityService qualityService,
                                 JdbcTemplate jdbcTemplate) {
        this.catalogMapper = catalogMapper;
        this.diagMapper = diagMapper;
        this.operMapper = operMapper;
        this.qualityService = qualityService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 单份对比: 返回 {before:[...], after:[...], added:[...], resolved:[...], unchanged:[...]}。
     * before = 基于 his_case_front_page 原始主诊/科室/日期做 catalog 级校验;
     * after = 当前编目态 catalog + diag/oper 全量校验。
     */
    public Map<String, Object> diff(Long visitId) {
        TenantContext.require();
        HisMrCatalog catalog = catalogMapper.selectOne(new QueryWrapper<HisMrCatalog>()
                .eq("visit_id", visitId).last("LIMIT 1"));
        if (catalog == null) {
            throw new BizException(404, "病案编目不存在");
        }

        // "编目后" validate: 当前 catalog 状态(含 diag/oper 子表)
        List<Map<String, Object>> afterErrs = qualityService.validate(catalog);

        // "编目前" validate: 从 his_case_front_page 取原始数据做 catalog 级校验(不含子表)
        HisMrCatalog beforeCatalog = buildBeforeCatalog(catalog);
        List<Map<String, Object>> beforeErrs = validateBefore(beforeCatalog);

        // diff by ruleCode
        Set<String> beforeCodes = new HashSet<>();
        for (Map<String, Object> e : beforeErrs) {
            beforeCodes.add(String.valueOf(e.get("ruleCode")));
        }
        Set<String> afterCodes = new HashSet<>();
        for (Map<String, Object> e : afterErrs) {
            afterCodes.add(String.valueOf(e.get("ruleCode")));
        }

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> resolved = new ArrayList<>();
        List<Map<String, Object>> unchanged = new ArrayList<>();

        for (Map<String, Object> e : afterErrs) {
            String rc = String.valueOf(e.get("ruleCode"));
            if (!beforeCodes.contains(rc)) {
                added.add(e);
            } else {
                unchanged.add(e);
            }
        }
        for (Map<String, Object> e : beforeErrs) {
            String rc = String.valueOf(e.get("ruleCode"));
            if (!afterCodes.contains(rc)) {
                resolved.add(e);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visitId", visitId);
        out.put("catalogId", catalog.getId());
        out.put("before", beforeErrs);
        out.put("after", afterErrs);
        out.put("added", added);
        out.put("resolved", resolved);
        out.put("unchanged", unchanged);
        out.put("summary", "编目前" + beforeErrs.size() + "项 → 编目后" + afterErrs.size()
                + "项(新增" + added.size() + "/消除" + resolved.size() + "/仍存在" + unchanged.size() + ")");
        return out;
    }

    /**
     * 批量对比汇总: 对多份 visitId 计算 diff 统计, 返回每份的 {visitId, beforeCount, afterCount, addedCount, resolvedCount}。
     */
    public List<Map<String, Object>> batchDiff(List<Long> visitIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (visitIds == null || visitIds.isEmpty()) {
            return results;
        }
        for (Long vid : visitIds) {
            HisMrCatalog catalog = catalogMapper.selectOne(new QueryWrapper<HisMrCatalog>()
                    .eq("visit_id", vid).last("LIMIT 1"));
            if (catalog == null) {
                continue;
            }
            List<Map<String, Object>> afterErrs = qualityService.validate(catalog);
            HisMrCatalog beforeCatalog = buildBeforeCatalog(catalog);
            List<Map<String, Object>> beforeErrs = validateBefore(beforeCatalog);

            Set<String> beforeCodes = new HashSet<>();
            for (Map<String, Object> e : beforeErrs) {
                beforeCodes.add(String.valueOf(e.get("ruleCode")));
            }
            Set<String> afterCodes = new HashSet<>();
            for (Map<String, Object> e : afterErrs) {
                afterCodes.add(String.valueOf(e.get("ruleCode")));
            }
            int added = 0;
            int resolved = 0;
            for (String rc : afterCodes) {
                if (!beforeCodes.contains(rc)) added++;
            }
            for (String rc : beforeCodes) {
                if (!afterCodes.contains(rc)) resolved++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("visitId", vid);
            row.put("catalogId", catalog.getId());
            row.put("patientName", patientName(catalog.getPatientId()));
            row.put("beforeCount", beforeErrs.size());
            row.put("afterCount", afterErrs.size());
            row.put("addedCount", added);
            row.put("resolvedCount", resolved);
            results.add(row);
        }
        return results;
    }

    /**
     * 编目前 catalog 级校验(不含 diag/oper 子表): 仅检查出院科室/日期/主诊字段完整性。
     * 临床首页原始诊断可能为空(医师未填出院主诊), 此时 MR_MAIN_DIAG_REQUIRED 会报错。
     */
    private List<Map<String, Object>> validateBefore(HisMrCatalog catalog) {
        List<Map<String, Object>> errs = new ArrayList<>();
        if (catalog.getDischargeDeptId() == null) {
            errs.add(errBefore("强制", "MR_DEPT_REQUIRED", "catalog.dischargeDeptId", "出院科室不能为空"));
        }
        if (catalog.getAdmissionDate() == null || catalog.getDischargeDate() == null) {
            errs.add(errBefore("强制", "MR_DATE_REQUIRED", "catalog.dischargeDate", "入院/出院日期不能为空"));
        }
        if (catalog.getMainDiagCode() == null || catalog.getMainDiagCode().isEmpty()) {
            errs.add(errBefore("强制", "MR_MAIN_DIAG_REQUIRED", "diag.dmain", "缺少出院主要诊断(需填写国临版编码)"));
        }
        // S/T 损伤需外因: 从 catalog.summary JSON 中取 injury 字段(原始 his_case_front_page.injury_poison_code)
        String mdc = catalog.getMainDiagCode();
        if (mdc != null && (mdc.startsWith("S") || mdc.startsWith("T"))) {
            String injury = extractInjuryFromSummary(catalog.getSummary());
            if (injury == null || injury.isEmpty()) {
                errs.add(errBefore("强制", "MR_INJURY_REQUIRED", "diag.injure", "主要诊断为损伤中毒(S/T), 需录入损伤中毒外部原因"));
            }
        }
        return errs;
    }

    private String extractInjuryFromSummary(String summary) {
        if (summary == null || summary.isEmpty()) return null;
        try {
            com.alibaba.fastjson2.JSONObject obj = com.alibaba.fastjson2.JSON.parseObject(summary);
            return obj.getString("injury");
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> errBefore(String category, String ruleCode, String fieldKey, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleCategory", category);
        m.put("ruleCode", ruleCode);
        m.put("fieldKey", fieldKey);
        m.put("errorMsg", msg);
        return m;
    }

    /**
     * 构造"编目前"临时 catalog: 使用 his_case_front_page 原始诊断/手术编码, 不走 his_mr_diag/oper。
     * 这样 validate 看到的是临床医师填写时的状态(未经编目员修订)。
     */
    private HisMrCatalog buildBeforeCatalog(HisMrCatalog current) {
        HisMrCatalog before = new HisMrCatalog();
        before.setId(current.getId());
        before.setVisitId(current.getVisitId());
        before.setPatientId(current.getPatientId());
        before.setOrgId(current.getOrgId());
        before.setSummary(current.getSummary());

        Long sourcePageId = current.getSourceCasePageId();
        if (sourcePageId != null) {
            List<Map<String, Object>> fp = jdbcTemplate.queryForList(
                    "SELECT admission_date AS admissionDate, discharge_date AS dischargeDate,"
                            + " admission_dept_id AS admissionDeptId, discharge_dept_id AS dischargeDeptId,"
                            + " discharge_main_diag_code AS mainDiagCode, discharge_main_diag_name AS mainDiagName"
                            + " FROM his_case_front_page WHERE id = ? AND deleted = 0 LIMIT 1",
                    sourcePageId);
            if (!fp.isEmpty()) {
                Map<String, Object> row = fp.get(0);
                before.setDischargeDeptId(toLong(row.get("dischargeDeptId")));
                before.setAdmissionDate(toDateTime(row.get("admissionDate")));
                before.setDischargeDate(toDateTime(row.get("dischargeDate")));
                before.setMainDiagCode(strOf(row.get("mainDiagCode")));
                before.setMainDiagName(strOf(row.get("mainDiagName")));
                return before;
            }
        }
        // fallback: 若 sourceCasePageId 为空或查不到, 使用当前 catalog 的日期/科室但主诊断回空(模拟刚生成未填状态)
        before.setDischargeDeptId(current.getDischargeDeptId());
        before.setAdmissionDate(current.getAdmissionDate());
        before.setDischargeDate(current.getDischargeDate());
        before.setMainDiagCode(null);
        return before;
    }

    private String patientName(Long patientId) {
        if (patientId == null) return null;
        List<Map<String, Object>> r = jdbcTemplate.queryForList(
                "SELECT name FROM his_patient WHERE id = ? AND deleted = 0", patientId);
        return r.isEmpty() ? null : strOf(r.get(0).get("name"));
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString().trim()); } catch (Exception e) { return null; }
    }

    private static String strOf(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static java.time.LocalDateTime toDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.LocalDateTime) return (java.time.LocalDateTime) o;
        if (o instanceof java.sql.Timestamp) return ((java.sql.Timestamp) o).toLocalDateTime();
        try { return java.time.LocalDateTime.parse(o.toString().trim().replace(" ", "T")); } catch (Exception e) { return null; }
    }
}
