package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.EmrQualityRuleDTO;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.inpatient.HisEmrQualityRule;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.util.SafeJsonTool;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.inpatient.HisEmrQualityRuleMapper;
import com.yb.hi.mapper.inpatient.HisEmrTemplateMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 病历质控服务: 完整性/时限性/逻辑性/规范性四类规则(33 条标准种子), 按病历逐条检查评分,
 * 支持科室质控排名与质控报告汇总, 启动时按租户幂等播种规则(@Order(8))。
 *
 * 评分语义: 100 分起扣; severity=1 警告(不扣分, 前缀【警告】), severity=2 按 deduct_score 扣分(前缀【扣分】),
 * severity=3 一票否决(直接 0 分, 前缀【一票否决】); 结果持久化到 quality_score/quality_detail。
 * 规则匹配: rule_config.category 非空时须等于记录类别(模板 template_category 或经典类型回退映射),
 * 否则按 rule.record_type 过滤(null=全类型)。
 */
@Slf4j
@Order(8)
@Service
public class EmrQualityService implements ApplicationRunner {

    /** 满分基数 */
    private static final BigDecimal FULL_SCORE = new BigDecimal("100");
    /** 不合格线(低于视为不合格) */
    private static final BigDecimal UNQUALIFIED_LINE = new BigDecimal("60");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 标准质控规则种子: {规则编码, 规则名称, record_type, rule_type(1完整性 2时限性 3逻辑性 4规范性),
     * rule_config(JSONObject), 扣分, severity(1警告 2扣分 3一票否决), 说明}
     */
    private static final Object[][] RULE_SEEDS = {
            /* ---- 入院记录(category=1, record_type=1) ---- */
            {"ADMIT_TIME_24H", "入院记录24小时内完成", 1, 2, dln(1, 24, null, "admitDate"), "20", 2,
                    "入院记录须在患者入院后24小时内完成"},
            {"ADMIT_CHIEF_REQ", "主诉必填", 1, 1, req(1, "chiefComplaint"), "5", 2,
                    "入院记录主诉不得为空"},
            {"ADMIT_CHIEF_LEN", "主诉不超过200字", 1, 4, mlen(1, "chiefComplaint", 200), "2", 1,
                    "主诉应简明扼要, 不超过200字"},
            {"ADMIT_PRESENT_REQ", "现病史必填", 1, 1, req(1, "presentIllness"), "5", 2,
                    "入院记录现病史不得为空"},
            {"ADMIT_ALLERGY_REQ", "过敏史必填", 1, 1, req(1, "allergyHistory"), "5", 2,
                    "入院记录过敏史不得为空(无过敏须注明'无')"},
            {"ADMIT_DIAG_REQ", "初步诊断必填", 1, 1, req(1, "admitDiagnosis"), "5", 2,
                    "入院记录初步诊断不得为空"},
            {"ADMIT_EXAM_REQ", "体格检查必填", 1, 1, req(1, "physicalExam"), "5", 2,
                    "入院记录体格检查不得为空"},
            {"ADMIT_PLAN_REQ", "诊疗计划必填", 1, 1, req(1, "treatPlan"), "5", 2,
                    "入院记录诊疗计划不得为空"},
            /* ---- 首次病程(category=2, record_type=2) ---- */
            {"FIRST_TIME_8H", "首次病程8小时内完成", 2, 2, dln(2, 8, null, "admitDate"), "20", 2,
                    "首次病程记录须在患者入院后8小时内完成"},
            {"FIRST_FEATURES_REQ", "病例特点必填", 2, 1, req(2, "caseFeatures"), "5", 2,
                    "首次病程病例特点不得为空"},
            {"FIRST_DIFFDIAG_REQ", "鉴别诊断必填", 2, 1, req(2, "diffDiag"), "5", 2,
                    "首次病程鉴别诊断不得为空"},
            {"FIRST_PLAN_REQ", "诊疗计划必填", 2, 1, req(2, "treatPlan"), "5", 2,
                    "首次病程诊疗计划不得为空"},
            /* ---- 上级医师查房(category=4, record_type=4) ---- */
            {"ROUND_TIME_48H", "上级医师查房48小时内完成", 4, 2, dln(4, 48, null, "admitDate"), "50", 3,
                    "患者入院48小时内须完成首次上级医师查房, 超时一票否决"},
            {"ROUND_OPINION_REQ", "上级医师意见必填", 4, 1, req(4, "attendingOpinion"), "10", 2,
                    "查房记录上级医师意见不得为空"},
            {"ROUND_LEVEL_REQ", "查房级别必填", 4, 1, req(4, "roundLevel"), "3", 1,
                    "查房记录应注明查房级别"},
            /* ---- 日常病程(category=3, record_type=3) ---- */
            {"DAILY_S_REQ", "主观(S)必填", 3, 1, req(3, "subjective"), "5", 2,
                    "日常病程主观资料(S)不得为空"},
            {"DAILY_O_REQ", "客观(O)必填", 3, 1, req(3, "objective"), "5", 2,
                    "日常病程客观资料(O)不得为空"},
            {"DAILY_A_REQ", "评估(A)必填", 3, 1, req(3, "assessment"), "5", 2,
                    "日常病程评估(A)不得为空"},
            {"DAILY_P_REQ", "计划(P)必填", 3, 1, req(3, "plan"), "5", 2,
                    "日常病程计划(P)不得为空"},
            /* ---- 手术记录(category=5, record_type=6) ---- */
            {"SURGERY_TIME_24H", "手术记录24小时内完成", 6, 2, dln(5, 24, null, "surgeryDate"), "20", 2,
                    "手术记录须在手术结束后24小时内完成"},
            {"SURGERY_PROCESS_REQ", "手术经过必填", 6, 1, req(5, "surgeryProcess"), "10", 2,
                    "手术记录手术经过不得为空"},
            {"SURGERY_DIAG_REQ", "术后诊断必填", 6, 1, req(5, "postOpDiag"), "5", 2,
                    "手术记录术后诊断不得为空"},
            /* ---- 术后病程(category=6, record_type=7) ---- */
            {"POST_SURGERY_RECOVERY_REQ", "麻醉恢复必填", 7, 1, req(6, "anesthesiaRecovery"), "3", 1,
                    "术后病程应记录麻醉恢复情况"},
            {"POST_SURGERY_COND_REQ", "术后情况必填", 7, 1, req(6, "postCondition"), "5", 2,
                    "术后病程术后情况不得为空"},
            {"POST_SURGERY_ORDERS_REQ", "术后医嘱必填", 7, 1, req(6, "postOrders"), "10", 2,
                    "术后病程术后医嘱不得为空"},
            /* ---- 出院小结(category=7, record_type=8) ---- */
            {"DISCHARGE_TIME_3D", "出院小结3日内完成", 8, 2, dln(7, null, 3, "dischargeDate"), "20", 2,
                    "出院小结须在患者出院后3日内完成"},
            {"DISCHARGE_DIAG_REQ", "出院诊断必填", 8, 1, req(7, "dischargeDiag"), "5", 2,
                    "出院小结出院诊断不得为空"},
            {"DISCHARGE_SUMMARY_REQ", "诊疗经过必填", 8, 1, req(7, "treatSummary"), "5", 2,
                    "出院小结诊疗经过不得为空"},
            {"DISCHARGE_ORDERS_REQ", "出院医嘱必填", 8, 1, req(7, "dischargeOrders"), "5", 2,
                    "出院小结出院医嘱不得为空"},
            /* ---- 死亡记录(category=8, record_type=9) ---- */
            {"DEATH_TIME_REQ", "死亡时间必填", 9, 1, req(8, "deathTime"), "10", 2,
                    "死亡记录死亡时间不得为空"},
            {"DEATH_CAUSE_REQ", "死亡原因必填", 9, 1, req(8, "deathCause"), "10", 2,
                    "死亡记录死亡原因不得为空"},
            /* ---- 通用(全类型) ---- */
            {"EMR_SIGN_REQ", "记录医师签名完整", null, 1, req(null, "doctorId"), "5", 2,
                    "病历须有记录医师签名"},
            {"EMR_CONTENT_REQ", "病历内容不得为空", null, 1, req(null, "content"), "10", 2,
                    "病历内容不得为空"}
    };

    private final HisEmrQualityRuleMapper ruleMapper;
    private final HisInpMedicalRecordMapper recordMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisEmrTemplateMapper templateMapper;
    private final HisDeptMapper deptMapper;
    private final OrgAccessGuard guard;
    private final SysTenantService tenantService;
    private final JdbcTemplate jdbcTemplate;
    private final SafeJsonTool safeJsonTool;

    public EmrQualityService(HisEmrQualityRuleMapper ruleMapper, HisInpMedicalRecordMapper recordMapper,
                             HisInpVisitMapper visitMapper, HisEmrTemplateMapper templateMapper,
                             HisDeptMapper deptMapper, OrgAccessGuard guard,
                             SysTenantService tenantService, JdbcTemplate jdbcTemplate,
                             SafeJsonTool safeJsonTool) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.visitMapper = visitMapper;
        this.templateMapper = templateMapper;
        this.deptMapper = deptMapper;
        this.guard = guard;
        this.tenantService = tenantService;
        this.jdbcTemplate = jdbcTemplate;
        this.safeJsonTool = safeJsonTool;
    }

    /* ================= 启动播种 ================= */

    @Override
    public void run(ApplicationArguments args) {
        List<SysTenant> tenants;
        try {
            tenants = tenantService.listAll();
        } catch (Exception e) {
            log.warn("病历质控规则种子跳过(租户表未就绪): {}", e.getMessage());
            return;
        }
        if (tenants == null) {
            return;
        }
        tenants.sort(Comparator.comparing(SysTenant::getId));
        for (SysTenant t : tenants) {
            if (t == null || t.getId() == null) {
                continue;
            }
            if (SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                continue; // 平台运营方租户无医院业务数据
            }
            Long prev = TenantContext.get();
            try {
                TenantContext.set(t.getId());
                seedTenant(t.getId());
            } catch (Exception e) {
                log.warn("租户[{}] 病历质控规则种子跳过(可能表未建): {}", t.getId(), e.getMessage());
            } finally {
                if (prev == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(prev);
                }
            }
        }
    }

    /** 按租户播种缺失的标准规则(逐码判空, 部分缺失可补种; 质控表无唯一键, 各租户独立拥有规则) */
    private void seedTenant(Long tenantId) {
        Long orgId = resolveLeadOrgId(tenantId);
        if (orgId == null) {
            return; // 机构未建(RbacInitializer 未跑完), 下次启动补种
        }
        int added = 0;
        for (Object[] def : RULE_SEEDS) {
            String code = (String) def[0];
            boolean localExists = !ruleMapper.selectList(Wrappers.<HisEmrQualityRule>lambdaQuery()
                    .eq(HisEmrQualityRule::getRuleCode, code)).isEmpty();
            if (localExists) {
                continue;
            }
            HisEmrQualityRule r = new HisEmrQualityRule();
            r.setOrgId(orgId);
            r.setRuleCode(code);
            r.setRuleName((String) def[1]);
            r.setRecordType((Integer) def[2]);
            r.setRuleType((Integer) def[3]);
            r.setRuleConfig(JSON.toJSONString(def[4]));
            r.setDeductScore(new BigDecimal((String) def[5]));
            r.setSeverity((Integer) def[6]);
            r.setDescription((String) def[7]);
            r.setStatus(1);
            ruleMapper.insert(r);
            added++;
        }
        if (added > 0) {
            log.info("租户[{}] 病历质控规则初始化完成(新增{}条)", tenantId, added);
        }
    }

    /** 解析租户牵头机构: 优先 is_lead=1, 兜底最小 id(his_emr_quality_rule.org_id 非空) */
    private Long resolveLeadOrgId(Long tenantId) {
        List<Long> lead = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 AND is_lead = 1 ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), tenantId);
        if (!lead.isEmpty()) {
            return lead.get(0);
        }
        List<Long> any = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), tenantId);
        return any.isEmpty() ? null : any.get(0);
    }

    /* ================= 规则维护 ================= */

    /** 规则列表(recordType/ruleType/status 可选筛选) */
    public R<List<HisEmrQualityRule>> listRules(Integer recordType, Integer ruleType, Integer status) {
        List<HisEmrQualityRule> list = ruleMapper.selectList(Wrappers.<HisEmrQualityRule>lambdaQuery()
                .eq(recordType != null, HisEmrQualityRule::getRecordType, recordType)
                .eq(ruleType != null, HisEmrQualityRule::getRuleType, ruleType)
                .eq(status != null, HisEmrQualityRule::getStatus, status)
                .orderByAsc(HisEmrQualityRule::getRuleType)
                .orderByAsc(HisEmrQualityRule::getId));
        return R.ok(list);
    }

    /** 创建规则 */
    public R<HisEmrQualityRule> createRule(EmrQualityRuleDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getRuleCode())) {
            throw new BizException(400, "规则编码不能为空");
        }
        if (!StringUtils.hasText(dto.getRuleName())) {
            throw new BizException(400, "规则名称不能为空");
        }
        String code = dto.getRuleCode().trim();
        boolean dup = !ruleMapper.selectList(Wrappers.<HisEmrQualityRule>lambdaQuery()
                .eq(HisEmrQualityRule::getRuleCode, code)).isEmpty();
        if (dup) {
            throw new BizException(400, "规则编码已存在: " + code);
        }
        HisEmrQualityRule r = new HisEmrQualityRule();
        r.setOrgId(guard.currentOrgId());
        r.setRuleCode(code);
        r.setRuleName(dto.getRuleName().trim());
        r.setRecordType(dto.getRecordType());
        r.setRuleType(dto.getRuleType());
        r.setRuleConfig(StringUtils.hasText(dto.getRuleConfig()) ? dto.getRuleConfig() : "{}");
        r.setDeductScore(dto.getDeductScore());
        r.setSeverity(dto.getSeverity() != null ? dto.getSeverity() : 2);
        r.setDescription(dto.getDescription());
        r.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        ruleMapper.insert(r);
        log.info("新建质控规则: id={}, code={}, name={}", r.getId(), code, r.getRuleName());
        return R.ok(ruleMapper.selectById(r.getId()));
    }

    /** 更新规则 */
    public R<Void> updateRule(Long id, EmrQualityRuleDTO dto) {
        HisEmrQualityRule exist = id == null ? null : ruleMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "质控规则不存在");
        }
        if (dto == null) {
            throw new BizException(400, "规则内容不能为空");
        }
        if (StringUtils.hasText(dto.getRuleCode())) {
            String code = dto.getRuleCode().trim();
            if (!code.equals(exist.getRuleCode())) {
                boolean dup = !ruleMapper.selectList(Wrappers.<HisEmrQualityRule>lambdaQuery()
                        .eq(HisEmrQualityRule::getRuleCode, code)).isEmpty();
                if (dup) {
                    throw new BizException(400, "规则编码已存在: " + code);
                }
                exist.setRuleCode(code);
            }
        }
        if (StringUtils.hasText(dto.getRuleName())) {
            exist.setRuleName(dto.getRuleName().trim());
        }
        if (dto.getRecordType() != null) {
            exist.setRecordType(dto.getRecordType());
        }
        if (dto.getRuleType() != null) {
            exist.setRuleType(dto.getRuleType());
        }
        if (StringUtils.hasText(dto.getRuleConfig())) {
            exist.setRuleConfig(dto.getRuleConfig());
        }
        if (dto.getDeductScore() != null) {
            exist.setDeductScore(dto.getDeductScore());
        }
        if (dto.getSeverity() != null) {
            exist.setSeverity(dto.getSeverity());
        }
        if (dto.getDescription() != null) {
            exist.setDescription(dto.getDescription());
        }
        if (dto.getStatus() != null) {
            exist.setStatus(dto.getStatus());
        }
        ruleMapper.updateById(exist);
        log.info("更新质控规则: id={}, code={}", id, exist.getRuleCode());
        return R.ok();
    }

    /** 删除规则(逻辑删除) */
    public R<Void> removeRule(Long id) {
        if (id == null || ruleMapper.selectById(id) == null) {
            throw new BizException(400, "质控规则不存在");
        }
        ruleMapper.deleteById(id);
        log.info("删除质控规则: id={}", id);
        return R.ok();
    }

    /* ================= 质控评分 ================= */

    /**
     * 对单份病历执行全量质控评分: 100 分起扣, 一票否决直接 0 分;
     * 结果持久化到 quality_score/quality_detail, 返回 {recordId, score, hasFatal, details}。
     */
    public Map<String, Object> evaluateQuality(Long recordId) {
        HisInpMedicalRecord rec = recordId == null ? null : recordMapper.selectById(recordId);
        if (rec == null) {
            throw new BizException(400, "病历记录不存在");
        }
        HisInpVisit visit = rec.getInpVisitId() == null ? null : visitMapper.selectById(rec.getInpVisitId());
        if (visit != null) {
            checkVisitAccess(visit);
        }
        Integer recCategory = resolveCategory(rec);
        List<HisEmrQualityRule> rules = ruleMapper.selectList(Wrappers.<HisEmrQualityRule>lambdaQuery()
                .eq(HisEmrQualityRule::getStatus, 1)
                .orderByAsc(HisEmrQualityRule::getRuleType)
                .orderByAsc(HisEmrQualityRule::getId));
        BigDecimal score = FULL_SCORE;
        boolean hasFatal = false;
        List<Map<String, Object>> details = new ArrayList<>();
        for (HisEmrQualityRule rule : rules) {
            JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
            String cfgType = cfg.getString("type");
            if (!StringUtils.hasText(cfgType)) {
                continue;
            }
            /* 匹配: 有 category 须等于记录类别; 无 category 按 record_type 过滤(null=全类型) */
            Integer cfgCategory = cfg.getInteger("category");
            if (cfgCategory != null) {
                if (!cfgCategory.equals(recCategory)) {
                    continue;
                }
            } else if (rule.getRecordType() != null && !rule.getRecordType().equals(rec.getRecordType())) {
                continue;
            }
            Integer severity = rule.getSeverity() == null ? 2 : rule.getSeverity();
            BigDecimal deduct = rule.getDeductScore() == null ? BigDecimal.ZERO : rule.getDeductScore();
            Boolean passed = null;
            String extra = "";
            if ("required".equals(cfgType)) {
                String field = cfg.getString("field");
                passed = StringUtils.hasText(fieldValue(rec, field));
            } else if ("maxLength".equals(cfgType)) {
                String field = cfg.getString("field");
                Integer max = cfg.getInteger("max");
                String v = fieldValue(rec, field);
                if (!StringUtils.hasText(v) || max == null) {
                    passed = true; // 空值由 required 规则负责, 此处不重复判错
                } else {
                    passed = v.length() <= max;
                    if (Boolean.FALSE.equals(passed)) {
                        extra = "(实际" + v.length() + "字, 上限" + max + "字)";
                    }
                }
            } else if ("deadline".equals(cfgType)) {
                LocalDateTime deadline = resolveDeadline(cfg, rec, visit);
                if (deadline == null) {
                    continue; // 无基准时间(结构数据/就诊日期/记录截止均缺失), 跳过
                }
                LocalDateTime actual = rec.getRecordTime() != null ? rec.getRecordTime() : rec.getCreateTime();
                if (actual == null) {
                    continue;
                }
                passed = !actual.isAfter(deadline);
                if (Boolean.FALSE.equals(passed)) {
                    extra = "(记录时间 " + actual.format(DT_FMT) + ", 截止 " + deadline.format(DT_FMT) + ")";
                }
            } else if ("pattern".equals(cfgType)) {
                /* 规范性: 字段值须匹配正则(空值交由 required 规则负责, 此处不重复判错) */
                String field = cfg.getString("field");
                String regex = cfg.getString("regex");
                String v = fieldValue(rec, field);
                if (!StringUtils.hasText(v) || !StringUtils.hasText(regex)) {
                    passed = true;
                } else {
                    try {
                        passed = v.matches(regex);
                    } catch (Exception rex) {
                        passed = true; // 非法正则不判错
                    }
                    if (Boolean.FALSE.equals(passed)) {
                        extra = "(" + field + " 格式不符合规范)";
                    }
                }
            } else if ("logic".equals(cfgType)) {
                /* 逻辑性: 两字段(或字段与常量)比较 eq/ne/gt/lt/ge/le/before/after/contains; 基准缺失跳过 */
                Boolean lp = evalLogic(cfg, rec);
                if (lp == null) {
                    continue;
                }
                passed = lp;
                if (Boolean.FALSE.equals(passed)) {
                    extra = "(" + cfg.getString("left") + " " + cfg.getString("op") + " "
                            + (cfg.containsKey("rightValue") ? cfg.getString("rightValue") : cfg.getString("right"))
                            + " 不成立)";
                }
            } else if ("term".equals(cfgType)) {
                /* 规范性: 字段值须命中允许值域(空/无值域配置不判错) */
                String field = cfg.getString("field");
                String v = fieldValue(rec, field);
                JSONArray allowed = cfg.getJSONArray("values");
                if (!StringUtils.hasText(v) || allowed == null || allowed.isEmpty()) {
                    passed = true;
                } else {
                    passed = containsValue(allowed, v);
                    if (Boolean.FALSE.equals(passed)) {
                        extra = "(值'" + v + "'不在允许值域内)";
                    }
                }
            } else {
                continue; // 未知检查类型, 跳过
            }
            BigDecimal actualDeduct = BigDecimal.ZERO;
            String message = null;
            if (Boolean.FALSE.equals(passed)) {
                String base = StringUtils.hasText(rule.getDescription()) ? rule.getDescription() : rule.getRuleName();
                if (severity == 3) {
                    hasFatal = true;
                    message = "【一票否决】" + base + extra;
                } else if (severity == 1) {
                    message = "【警告】" + base + extra;
                } else {
                    actualDeduct = deduct;
                    score = score.subtract(deduct);
                    message = "【扣分】" + base + extra;
                }
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("ruleId", rule.getId());
            d.put("ruleCode", rule.getRuleCode());
            d.put("ruleName", rule.getRuleName());
            d.put("ruleType", rule.getRuleType());
            d.put("severity", severity);
            d.put("passed", passed);
            d.put("deductScore", actualDeduct);
            if (message != null) {
                d.put("message", message);
            }
            details.add(d);
        }
        if (hasFatal) {
            score = BigDecimal.ZERO;
        }
        if (score.compareTo(BigDecimal.ZERO) < 0) {
            score = BigDecimal.ZERO;
        }
        score = score.setScale(1, RoundingMode.HALF_UP);
        rec.setQualityScore(score);
        /* 嵌套 JSON 须走 SafeJsonTool: details 内 ruleId 为雪花 Long, fastjson 会写成裸数字,
         * 前端对 quality_detail 字符串二次 JSON.parse 丢精度(T57) */
        rec.setQualityDetail(safeJsonTool.toJson(details));
        recordMapper.updateById(rec);
        log.info("病历质控评分完成: recordId={}, score={}, hasFatal={}, 明细{}条",
                rec.getId(), score, hasFatal, details.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", rec.getId());
        result.put("score", score);
        result.put("hasFatal", hasFatal);
        result.put("details", details);
        return result;
    }

    /* ================= 统计报告 ================= */

    /** 科室质控排名(按平均分降序, 含未评分记录数) */
    public R<List<Map<String, Object>>> getDeptQualityRanking(Long orgId, LocalDate startDate, LocalDate endDate) {
        Long scope = guard.scopeOrgId(orgId);
        List<HisInpMedicalRecord> records = queryRecords(scope, startDate, endDate);
        return R.ok(buildDeptRanking(records));
    }

    /** 质控报告: 总量/已评/均分/不合格率/一票否决 + 分数段分布 + 问题类型分布 + 科室排名 */
    public R<Map<String, Object>> getQualityReport(Long orgId, LocalDate startDate, LocalDate endDate) {
        Long scope = guard.scopeOrgId(orgId);
        List<HisInpMedicalRecord> records = queryRecords(scope, startDate, endDate);
        int total = records.size();
        int evaluated = 0;
        int unqualified = 0;
        int fatal = 0;
        BigDecimal sum = BigDecimal.ZERO;
        Map<String, Integer> dist = initScoreDistribution();
        Map<String, Integer> problems = initProblemDistribution();
        for (HisInpMedicalRecord rec : records) {
            JSONArray arr = parseArraySafe(rec.getQualityDetail());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (Boolean.FALSE.equals(o.getBoolean("passed"))) {
                    String name = ruleTypeName(o.getInteger("ruleType"));
                    problems.merge(name, 1, Integer::sum);
                    if (Integer.valueOf(3).equals(o.getInteger("severity"))) {
                        fatal++;
                        break; // 一份病历一票否决仅计一次
                    }
                }
            }
            if (rec.getQualityScore() != null) {
                evaluated++;
                sum = sum.add(rec.getQualityScore());
                if (rec.getQualityScore().compareTo(UNQUALIFIED_LINE) < 0) {
                    unqualified++;
                }
                bucketScore(dist, rec.getQualityScore());
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("orgId", scope);
        report.put("startDate", startDate == null ? null : startDate.toString());
        report.put("endDate", endDate == null ? null : endDate.toString());
        report.put("totalRecords", total);
        report.put("evaluatedCount", evaluated);
        report.put("avgScore", evaluated == 0 ? null
                : sum.divide(BigDecimal.valueOf(evaluated), 1, RoundingMode.HALF_UP));
        report.put("unqualifiedCount", unqualified);
        report.put("unqualifiedRate", evaluated == 0 ? null
                : BigDecimal.valueOf(unqualified * 100L).divide(BigDecimal.valueOf(evaluated), 2, RoundingMode.HALF_UP));
        report.put("fatalCount", fatal);
        report.put("scoreDistribution", dist);
        report.put("problemDistribution", problems);
        report.put("deptRanking", buildDeptRanking(records));
        return R.ok(report);
    }

    /* ================= 内部实现 ================= */

    /** 记录类别解析: 模板记录取 template_category, 经典记录按 record_type 回退映射 {1:1,2:2,3:3,4:4,6:5,7:6,8:7,9:8} */
    private Integer resolveCategory(HisInpMedicalRecord rec) {
        if (rec.getTemplateId() != null) {
            HisEmrTemplate t = templateMapper.selectById(rec.getTemplateId());
            if (t != null && t.getTemplateCategory() != null) {
                return t.getTemplateCategory();
            }
        }
        Integer rt = rec.getRecordType();
        if (rt == null) {
            return null;
        }
        switch (rt) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            case 4: return 4;
            case 6: return 5;
            case 7: return 6;
            case 8: return 7;
            case 9: return 8;
            default: return null; // 5术前小结等无对应质控类别
        }
    }

    /** 就诊访问校验(镜像 InpMedRecordService.requireVisit): 机构越权 403 */
    private void checkVisitAccess(HisInpVisit visit) {
        Long scope = guard.scopeOrgId(visit.getOrgId());
        if (scope == null || !scope.equals(visit.getOrgId())) {
            throw new BizException(403, "无权访问该住院就诊");
        }
    }

    /** 时限规则截止时间: structureData[fromField] → visit.admitDate/dischargeDate → 记录自带 deadlineTime(直接采用) */
    private LocalDateTime resolveDeadline(JSONObject cfg, HisInpMedicalRecord rec, HisInpVisit visit) {
        Integer hours = cfg.getInteger("hours");
        Integer days = cfg.getInteger("days");
        String fromField = cfg.getString("fromField");
        LocalDateTime base = null;
        if (StringUtils.hasText(fromField)) {
            base = toDateTime(parseObjectSafe(rec.getStructureData()).get(fromField));
            if (base == null && visit != null) {
                if ("admitDate".equals(fromField)) {
                    base = visit.getAdmitDate();
                } else if ("dischargeDate".equals(fromField)) {
                    base = visit.getDischargeDate();
                }
            }
        }
        if (base == null) {
            return rec.getDeadlineTime(); // 兜底: 记录自带截止时间(建档时按类型计算), 不再累加
        }
        if (hours != null) {
            return base.plusHours(hours);
        }
        if (days != null) {
            return base.plusDays(days);
        }
        return base;
    }

    /** 结构化字段取值: 特殊字段直取 + structureData 回退(content 空时回退结构数据非空值拼接) */
    private String fieldValue(HisInpMedicalRecord rec, String field) {
        if (!StringUtils.hasText(field)) {
            return "";
        }
        switch (field) {
            case "doctorId":
                return rec.getDoctorId() == null ? "" : String.valueOf(rec.getDoctorId());
            case "title":
                return rec.getTitle();
            case "recordTime":
                return rec.getRecordTime() == null ? "" : rec.getRecordTime().toString();
            case "content": {
                String c = rec.getContent();
                if (StringUtils.hasText(c) && !"{}".equals(c.trim()) && !"[]".equals(c.trim())) {
                    return c;
                }
                JSONObject sd = parseObjectSafe(rec.getStructureData());
                StringBuilder sb = new StringBuilder();
                for (String k : sd.keySet()) {
                    String v = text(sd.get(k));
                    if (StringUtils.hasText(v)) {
                        sb.append(v);
                    }
                }
                return sb.toString();
            }
            default: {
                JSONObject sd = parseObjectSafe(rec.getStructureData());
                return text(sd.get(field));
            }
        }
    }

    /** 逻辑性判定: 取 left / right(字段或 rightValue 常量) 字符串值按 op 比较; 基准缺失/无法解析返回 null(跳过) */
    private Boolean evalLogic(JSONObject cfg, HisInpMedicalRecord rec) {
        String op = cfg.getString("op");
        if (!StringUtils.hasText(op)) {
            return null;
        }
        String left = fieldValue(rec, cfg.getString("left"));
        String right = cfg.containsKey("rightValue")
                ? cfg.getString("rightValue") : fieldValue(rec, cfg.getString("right"));
        if (!StringUtils.hasText(left)) {
            return null;
        }
        switch (op) {
            case "eq":
                return left.equals(right);
            case "ne":
                return !left.equals(right);
            case "contains":
                return StringUtils.hasText(right) && left.contains(right);
            case "gt":
            case "lt":
            case "ge":
            case "le": {
                BigDecimal a = toNum(left);
                BigDecimal b = toNum(right);
                if (a == null || b == null) {
                    return null;
                }
                int c = a.compareTo(b);
                switch (op) {
                    case "gt": return c > 0;
                    case "lt": return c < 0;
                    case "ge": return c >= 0;
                    default: return c <= 0;
                }
            }
            case "before":
            case "after": {
                LocalDateTime a = toDateTime(left);
                LocalDateTime b = toDateTime(right);
                if (a == null || b == null) {
                    return null;
                }
                return "before".equals(op) ? a.isBefore(b) : a.isAfter(b);
            }
            default:
                return null;
        }
    }

    /** 值域命中: allowed(字符串数组) 是否含 value */
    private boolean containsValue(JSONArray allowed, String value) {
        for (int i = 0; i < allowed.size(); i++) {
            if (value.equals(allowed.getString(i))) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal toNum(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 记录时间范围查询(record_time ∈ [startDate 00:00, endDate+1 00:00)) */
    private List<HisInpMedicalRecord> queryRecords(Long orgId, LocalDate startDate, LocalDate endDate) {
        return recordMapper.selectList(Wrappers.<HisInpMedicalRecord>lambdaQuery()
                .eq(orgId != null, HisInpMedicalRecord::getOrgId, orgId)
                .ge(startDate != null, HisInpMedicalRecord::getRecordTime,
                        startDate == null ? null : startDate.atStartOfDay())
                .lt(endDate != null, HisInpMedicalRecord::getRecordTime,
                        endDate == null ? null : endDate.plusDays(1).atStartOfDay())
                .orderByAsc(HisInpMedicalRecord::getId));
    }

    /** 按就诊科室聚合质控评分, 生成排名列表(平均分降序, 未评分记录计 recordCount 不计均分) */
    private List<Map<String, Object>> buildDeptRanking(List<HisInpMedicalRecord> records) {
        Map<Long, Long> visitDept = loadVisitDeptMap(records);
        Map<Long, Integer> totalMap = new LinkedHashMap<>();
        Map<Long, Integer> evalMap = new LinkedHashMap<>();
        Map<Long, BigDecimal> sumMap = new LinkedHashMap<>();
        for (HisInpMedicalRecord rec : records) {
            Long deptId = rec.getInpVisitId() == null ? null : visitDept.get(rec.getInpVisitId());
            if (deptId == null) {
                continue;
            }
            totalMap.merge(deptId, 1, Integer::sum);
            if (rec.getQualityScore() != null) {
                evalMap.merge(deptId, 1, Integer::sum);
                sumMap.merge(deptId, rec.getQualityScore(), BigDecimal::add);
            }
        }
        Map<Long, String> deptNames = loadDeptNames(totalMap.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : totalMap.entrySet()) {
            Long deptId = e.getKey();
            int eval = evalMap.getOrDefault(deptId, 0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deptId", deptId);
            row.put("deptName", deptNames.getOrDefault(deptId, ""));
            row.put("recordCount", e.getValue());
            row.put("evaluatedCount", eval);
            row.put("avgScore", eval == 0 ? null
                    : sumMap.get(deptId).divide(BigDecimal.valueOf(eval), 1, RoundingMode.HALF_UP));
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (BigDecimal) r.get("avgScore"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).put("rank", i + 1);
        }
        return rows;
    }

    /** 批量解析 就诊ID → 科室ID */
    private Map<Long, Long> loadVisitDeptMap(List<HisInpMedicalRecord> records) {
        Set<Long> ids = new LinkedHashSet<>();
        for (HisInpMedicalRecord rec : records) {
            if (rec.getInpVisitId() != null) {
                ids.add(rec.getInpVisitId());
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> map = new LinkedHashMap<>();
        for (HisInpVisit v : visitMapper.selectBatchIds(ids)) {
            map.put(v.getId(), v.getDeptId());
        }
        return map;
    }

    /** 批量解析科室名称 */
    private Map<Long, String> loadDeptNames(Set<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new LinkedHashMap<>();
        for (HisDept d : deptMapper.selectBatchIds(deptIds)) {
            map.put(d.getId(), d.getDeptName());
        }
        return map;
    }

    private static Map<String, Integer> initScoreDistribution() {
        Map<String, Integer> dist = new LinkedHashMap<>();
        dist.put("90-100", 0);
        dist.put("80-89", 0);
        dist.put("60-79", 0);
        dist.put("60以下", 0);
        return dist;
    }

    private static void bucketScore(Map<String, Integer> dist, BigDecimal score) {
        String key;
        if (score.compareTo(new BigDecimal("90")) >= 0) {
            key = "90-100";
        } else if (score.compareTo(new BigDecimal("80")) >= 0) {
            key = "80-89";
        } else if (score.compareTo(UNQUALIFIED_LINE) >= 0) {
            key = "60-79";
        } else {
            key = "60以下";
        }
        dist.merge(key, 1, Integer::sum);
    }

    private static Map<String, Integer> initProblemDistribution() {
        Map<String, Integer> p = new LinkedHashMap<>();
        p.put("完整性", 0);
        p.put("时限性", 0);
        p.put("逻辑性", 0);
        p.put("规范性", 0);
        return p;
    }

    private static String ruleTypeName(Integer ruleType) {
        if (ruleType == null) {
            return "其他";
        }
        switch (ruleType) {
            case 1: return "完整性";
            case 2: return "时限性";
            case 3: return "逻辑性";
            case 4: return "规范性";
            default: return "其他";
        }
    }

    /** 日期时间柔性解析: 支持 LocalDateTime/Date/yyyy-MM-dd[ HH:mm[:ss]]/ISO 'T' 分隔 */
    private static LocalDateTime toDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime) {
            return (LocalDateTime) v;
        }
        if (v instanceof java.util.Date) {
            return LocalDateTime.ofInstant(((java.util.Date) v).toInstant(), ZoneId.systemDefault());
        }
        String s = String.valueOf(v).trim().replace('/', '-').replace('T', ' ');
        if (s.isEmpty()) {
            return null;
        }
        try {
            if (s.length() >= 19) {
                return LocalDateTime.parse(s.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            if (s.length() == 16) {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
            if (s.length() == 10) {
                return LocalDate.parse(s).atStartOfDay();
            }
        } catch (Exception ignore) {
            // 落空返回 null
        }
        return null;
    }

    private static JSONObject parseObjectSafe(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONObject();
        }
        try {
            JSONObject o = JSON.parseObject(json);
            return o == null ? new JSONObject() : o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONArray parseArraySafe(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONArray();
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static String text(Object v) {
        return v == null ? "" : (v instanceof String ? (String) v : String.valueOf(v));
    }

    /* ================= 种子配置构建 ================= */

    /** 必填检查配置: {"type":"required","field":..., ["category":N]} */
    private static JSONObject req(Integer category, String field) {
        JSONObject o = new JSONObject();
        o.put("type", "required");
        o.put("field", field);
        if (category != null) {
            o.put("category", category);
        }
        return o;
    }

    /** 长度检查配置: {"type":"maxLength","field":..., "max":N, ["category":N]} */
    private static JSONObject mlen(Integer category, String field, int max) {
        JSONObject o = new JSONObject();
        o.put("type", "maxLength");
        o.put("field", field);
        o.put("max", max);
        if (category != null) {
            o.put("category", category);
        }
        return o;
    }

    /** 时限检查配置: {"type":"deadline", ["hours":N|"days":N], "fromField":..., ["category":N]} */
    private static JSONObject dln(Integer category, Integer hours, Integer days, String fromField) {
        JSONObject o = new JSONObject();
        o.put("type", "deadline");
        if (hours != null) {
            o.put("hours", hours);
        }
        if (days != null) {
            o.put("days", days);
        }
        if (fromField != null) {
            o.put("fromField", fromField);
        }
        if (category != null) {
            o.put("category", category);
        }
        return o;
    }

    /** 正则(规范性)配置: {"type":"pattern","field":...,"regex":..., ["category":N]} */
    private static JSONObject pat(Integer category, String field, String regex) {
        JSONObject o = new JSONObject();
        o.put("type", "pattern");
        o.put("field", field);
        o.put("regex", regex);
        if (category != null) {
            o.put("category", category);
        }
        return o;
    }

    /** 逻辑性配置: {"type":"logic","op":eq/ne/gt/lt/ge/le/before/after/contains,"left":字段,["right":字段|"rightValue":常量],["category":N]} */
    private static JSONObject lgc(Integer category, String op, String left, String right) {
        JSONObject o = new JSONObject();
        o.put("type", "logic");
        o.put("op", op);
        o.put("left", left);
        if (right != null) {
            o.put("right", right);
        }
        if (category != null) {
            o.put("category", category);
        }
        return o;
    }

    /** 值域(规范性)配置: {"type":"term","field":...,"values":[...], ["category":N]} */
    private static JSONObject trm(Integer category, String field, String... values) {
        JSONObject o = new JSONObject();
        o.put("type", "term");
        o.put("field", field);
        JSONArray arr = new JSONArray();
        for (String v : values) {
            arr.add(v);
        }
        o.put("values", arr);
        if (category != null) {
            o.put("category", category);
        }
        return o;
    }
}
