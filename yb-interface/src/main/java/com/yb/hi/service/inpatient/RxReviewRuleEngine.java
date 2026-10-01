package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisRxReviewRule;
import com.yb.hi.mapper.inpatient.HisRxReviewRuleMapper;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.service.basedata.HisStaffRxAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处方点评自动规则引擎(T2 阶段5-2)。
 * 发起点评时按启用规则扫描医嘱上下文, 产出建议结论/评分/问题类型与命中明细, 供人工点评参考(预打分, 非最终裁定)。
 * 只读: 引擎不写库; 落库由 {@link HisRxReviewService#initiate} 将建议冻结到点评单 auto_* 列。
 * JdbcTemplate 手写 SQL 显式带 tenant_id AND deleted=0(绕过租户插件)。
 */
@Slf4j
@Service
public class RxReviewRuleEngine {

    private final HisRxReviewRuleMapper ruleMapper;
    private final JdbcTemplate jdbcTemplate;
    private final HisStaffRxAuthService rxAuthService;

    public RxReviewRuleEngine(HisRxReviewRuleMapper ruleMapper, JdbcTemplate jdbcTemplate,
                              HisStaffRxAuthService rxAuthService) {
        this.ruleMapper = ruleMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.rxAuthService = rxAuthService;
    }

    /** 规则清单(供透明展示/维护; 全部规则, 含停用)。 */
    public List<HisRxReviewRule> listRules() {
        return ruleMapper.selectList(null);
    }

    /**
     * 对一条医嘱跑规则, 返回建议(未写库)。
     * result: 无命中=1合理; 否则取命中规则最大 resultHint(3不合理优先于2不规范)。
     * score: 100 - 各命中扣分之和(截断 0..100)。
     * problemType: 严重度最高命中的问题类型。
     */
    public Map<String, Object> evaluate(HisInpOrder order) {
        List<HisRxReviewRule> rules = enabledRules();
        List<Map<String, Object>> findings = new ArrayList<>();
        int result = 1;
        int score = 100;
        int topSeverity = 0;
        String topProblem = null;

        Map<String, Object> drug = order.getDrugId() == null ? null : loadDrug(order.getDrugId());
        Map<String, Object> staff = order.getDoctorId() == null ? null : loadStaff(order.getDoctorId());
        Integer abxGrade = drug == null ? null : parseNoop(drug.get("abx_grade"));
        Integer staffLevel = staff == null ? null : parseNoop(staff.get("antibiotic_level"));

        for (HisRxReviewRule r : rules) {
            Map<String, Object> hit = matchOne(r, order, abxGrade, staffLevel, staff, drug);
            if (hit == null) {
                continue;
            }
            findings.add(hit);
            int rh = r.getResultHint() == null ? 2 : r.getResultHint();
            int sev = r.getSeverity() == null ? 2 : r.getSeverity();
            int ded = r.getScoreDeduct() == null ? 0 : r.getScoreDeduct();
            if (rh > result) {
                result = rh;
            }
            score -= ded;
            if (sev > topSeverity) {
                topSeverity = sev;
                topProblem = r.getProblemTypeHint();
            }
        }
        if (score < 0) {
            score = 0;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findings", findings);
        out.put("result", result);
        out.put("score", score);
        out.put("problemType", result == 1 ? null : topProblem);
        out.put("evaluated", 1);
        return out;
    }

    /** 单条规则命中判定; 命中返回明细 map, 否则 null。 */
    private Map<String, Object> matchOne(HisRxReviewRule r, HisInpOrder o, Integer abxGrade, Integer staffLevel,
                                         Map<String, Object> staff, Map<String, Object> drug) {
        String type = r.getRuleType() == null ? "" : r.getRuleType();
        boolean hit = false;
        String detail = null;
        switch (type) {
            case "abx_under_level":
                if (abxGrade != null && staffLevel != null && staffLevel < abxGrade) {
                    hit = true;
                    detail = "医生抗菌级别(" + staffLevel + ")低于药品分级(" + abxGrade + ")";
                }
                break;
            case "abx_no_auth":
                if (abxGrade != null && staffLevel == null) {
                    hit = true;
                    detail = "开嘱医生无抗菌处方权级别, 却开具" + nameOf(drug) + "(分级" + abxGrade + ")";
                }
                break;
            case "abx_expiry":
                if (abxGrade != null && o.getDoctorId() != null && abxExpired(o.getDoctorId(), abxGrade, staff)) {
                    hit = true;
                    detail = "医生对该抗菌分级的授权已过期";
                }
                break;
            case "duplicate_drug":
                if (o.getDrugId() != null && o.getInpVisitId() != null) {
                    Integer dup = jdbcTemplate.queryForObject(
                            "SELECT COUNT(1) FROM his_inp_order WHERE deleted=0 AND tenant_id=? AND inp_visit_id=?"
                                    + " AND drug_id=? AND order_category=1 AND order_status IN (1,2,3)",
                            Integer.class, tid(), o.getInpVisitId(), o.getDrugId());
                    if (dup != null && dup > 1) {
                        hit = true;
                        detail = "同种药品在本住院就诊内重复开具(" + dup + "条在用)";
                    }
                }
                break;
            case "long_abx_duration":
                if (abxGrade != null && o.getOrderType() != null && o.getOrderType() == 1 && o.getStartTime() != null) {
                    int days = paramDays(r.getParams(), 14);
                    long activeDays = ChronoUnit.DAYS.between(o.getStartTime().toLocalDate(), LocalDate.now());
                    if (activeDays >= days) {
                        hit = true;
                        detail = "长期抗菌医嘱已开立 " + activeDays + " 天(阈值" + days + "天), 请关注疗程";
                    }
                }
                break;
            default:
                break;
        }
        if (!hit) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getRuleCode());
        m.put("name", r.getRuleName());
        m.put("severity", r.getSeverity());
        m.put("result", r.getResultHint());
        m.put("problemType", r.getProblemTypeHint());
        m.put("deduct", r.getScoreDeduct());
        m.put("detail", detail);
        return m;
    }

    /** 到期判定: 优先取按级授权明细有效期, 无明细回落 his_staff.rx_valid_until。 */
    private boolean abxExpired(Long staffId, int grade, Map<String, Object> staff) {
        LocalDate eff = rxAuthService.effectiveAbxValidUntil(staffId, String.valueOf(grade));
        if (eff == null && staff != null && staff.get("rx_valid_until") != null) {
            Object v = staff.get("rx_valid_until");
            if (v instanceof java.sql.Date) {
                eff = ((java.sql.Date) v).toLocalDate();
            } else {
                try {
                    eff = LocalDate.parse(v.toString());
                } catch (Exception ignore) {
                    eff = null;
                }
            }
        }
        return eff != null && eff.isBefore(LocalDate.now());
    }

    private List<HisRxReviewRule> enabledRules() {
        return ruleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisRxReviewRule>()
                        .eq(HisRxReviewRule::getEnabled, 1));
    }

    private Map<String, Object> loadDrug(Long drugId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, generic_name, abx_grade FROM his_drug_catalog WHERE id=? AND deleted=0 AND tenant_id=?",
                drugId, tid());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadStaff(Long staffId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, staff_name, antibiotic_level, rx_valid_until FROM his_staff WHERE id=? AND deleted=0 AND tenant_id=?",
                staffId, tid());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String nameOf(Map<String, Object> drug) {
        return drug == null || drug.get("generic_name") == null ? "抗菌药品" : String.valueOf(drug.get("generic_name"));
    }

    /** 兼容 VARCHAR/数值: 取整数值(抗菌级别/分级), 非数字返回 null。 */
    private static Integer parseNoop(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int paramDays(String paramsJson, int def) {
        if (paramsJson == null || paramsJson.trim().isEmpty()) {
            return def;
        }
        try {
            JSONObject jo = JSON.parseObject(paramsJson);
            Integer d = jo == null ? null : jo.getInteger("days");
            return d == null ? def : d;
        } catch (Exception e) {
            return def;
        }
    }

    private static long tid() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
