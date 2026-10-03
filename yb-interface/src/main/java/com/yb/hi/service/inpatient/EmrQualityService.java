package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.EmrQualityRuleDTO;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.emr.HisEmrQcDefect;
import com.yb.hi.entity.emr.HisEmrQcNode;
import com.yb.hi.entity.emr.HisEmrScoreStandard;
import com.yb.hi.entity.inpatient.HisEmrQualityRule;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.framework.util.SafeJsonTool;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.emr.HisEmrQcDefectMapper;
import com.yb.hi.mapper.emr.HisEmrQcNodeMapper;
import com.yb.hi.mapper.emr.HisEmrScoreStandardMapper;
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
import org.springframework.dao.EmptyResultDataAccessException;
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
import java.util.HashSet;
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
    /* P5a-3 内涵质控/病案首页精准质控新增依赖 */
    private final HisEmrQcDefectMapper qcDefectMapper;
    private final HisEmrQcNodeMapper qcNodeMapper;
    private final HisEmrScoreStandardMapper scoreStandardMapper;

    public EmrQualityService(HisEmrQualityRuleMapper ruleMapper, HisInpMedicalRecordMapper recordMapper,
                             HisInpVisitMapper visitMapper, HisEmrTemplateMapper templateMapper,
                             HisDeptMapper deptMapper, OrgAccessGuard guard,
                             SysTenantService tenantService, JdbcTemplate jdbcTemplate,
                             SafeJsonTool safeJsonTool,
                             HisEmrQcDefectMapper qcDefectMapper, HisEmrQcNodeMapper qcNodeMapper,
                             HisEmrScoreStandardMapper scoreStandardMapper) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.visitMapper = visitMapper;
        this.templateMapper = templateMapper;
        this.deptMapper = deptMapper;
        this.guard = guard;
        this.tenantService = tenantService;
        this.jdbcTemplate = jdbcTemplate;
        this.safeJsonTool = safeJsonTool;
        this.qcDefectMapper = qcDefectMapper;
        this.qcNodeMapper = qcNodeMapper;
        this.scoreStandardMapper = scoreStandardMapper;
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

    /* ===================== P5a-3 内涵质控(2026-10) ===================== */

    /**
     * 内涵质控(按 stage 区分运行/归档): 查询 rule_type=5(内涵)且 qc_stage IN (0, stage) 的启用规则,
     * 按 rule_category 分发五大类评估(item_value/item_compare/disease/calculation/event),
     * 命中缺陷落 his_emr_qc_defect(auto_generated=1), 质控节点日志落 his_emr_qc_node, 返回缺陷列表。
     * 契约: InpMedRecordService.runSignQualityCheck 提交签名前调用(stage=1), 异常由调用方降级处理;
     * 内涵规则 rule_config.type 用新类型(itemValue 等), 旧 evaluateQuality 引擎遇未知类型自动跳过, 互不干扰。
     * 单条规则评估失败仅记日志不影响其他规则(try-catch 隔离)。
     */
    public List<HisEmrQcDefect> evaluateContent(Long recordId, int stage) {
        HisInpMedicalRecord record = recordId == null ? null : recordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(400, "病历记录不存在");
        }
        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        if (visit != null) {
            checkVisitAccess(visit);
        }
        final int qcStage = stage == 2 ? 2 : 1;
        Integer recCategory = resolveCategory(record);
        List<HisEmrQualityRule> rules = ruleMapper.selectList(Wrappers.<HisEmrQualityRule>lambdaQuery()
                .eq(HisEmrQualityRule::getStatus, 1)
                .eq(HisEmrQualityRule::getRuleType, 5)
                .in(HisEmrQualityRule::getQcStage, 0, qcStage)
                .orderByAsc(HisEmrQualityRule::getId));
        Map<String, List<HisEmrQualityRule>> byCategory = new LinkedHashMap<>();
        for (HisEmrQualityRule rule : rules) {
            try {
                JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
                Integer cfgCategory = cfg.getInteger("category");
                if (cfgCategory != null && !cfgCategory.equals(recCategory)) {
                    continue;
                }
                if (cfgCategory == null && rule.getRecordType() != null
                        && !rule.getRecordType().equals(record.getRecordType())) {
                    continue;
                }
                String cat = StringUtils.hasText(rule.getRuleCategory()) ? rule.getRuleCategory() : "item_value";
                byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(rule);
            } catch (Exception e) {
                log.warn("内涵规则[{}]分类失败跳过: {}", rule.getRuleCode(), e.getMessage());
            }
        }
        List<HisEmrQcDefect> defects = new ArrayList<>();
        defects.addAll(evaluateItemValue(record, visit,
                byCategory.getOrDefault("item_value", Collections.emptyList()), qcStage));
        defects.addAll(evaluateItemCompare(record, visit,
                byCategory.getOrDefault("item_compare", Collections.emptyList()), qcStage));
        defects.addAll(evaluateDisease(record, visit,
                byCategory.getOrDefault("disease", Collections.emptyList()), qcStage));
        defects.addAll(evaluateCalculation(record, visit,
                byCategory.getOrDefault("calculation", Collections.emptyList()), qcStage));
        defects.addAll(evaluateEvent(record, visit,
                byCategory.getOrDefault("event", Collections.emptyList()), qcStage));
        for (HisEmrQcDefect d : defects) {
            try {
                qcDefectMapper.insert(d);
            } catch (Exception e) {
                log.warn("内涵缺陷落库失败(不影响评估结果): ruleCode={}, {}", d.getRuleCode(), e.getMessage());
            }
        }
        /* 质控节点日志: 内涵质控不改总分(总分由 evaluateQuality 驱动), 前后分一致仅留痕 */
        try {
            HisEmrQcNode node = new HisEmrQcNode();
            node.setRecordId(record.getId());
            node.setVisitId(record.getInpVisitId());
            node.setNodeType("质控");
            node.setNodeDesc("内涵质控(" + (qcStage == 2 ? "归档" : "运行") + "): 评估规则" + rules.size()
                    + "条, 命中缺陷" + defects.size() + "条");
            node.setScoreBefore(record.getQualityScore());
            node.setScoreAfter(record.getQualityScore());
            LoginUser op = UserContext.get();
            node.setOperatorId(op != null ? op.getUserId() : null);
            node.setOperatorName(op != null && StringUtils.hasText(op.getRealName())
                    ? op.getRealName() : (op != null ? op.getUsername() : "system"));
            qcNodeMapper.insert(node);
        } catch (Exception e) {
            log.warn("内涵质控节点日志写入失败(不影响结果): recordId={}, {}", recordId, e.getMessage());
        }
        log.info("内涵质控完成: recordId={}, stage={}, 规则{}条, 缺陷{}条", recordId, qcStage, rules.size(), defects.size());
        return defects;
    }

    /** 内涵-项目取值质控: 必填/字数/值域/格式/枚举(structureData 字段, 空值基准缺失不判错原则) */
    private List<HisEmrQcDefect> evaluateItemValue(HisInpMedicalRecord record, HisInpVisit visit,
                                                   List<HisEmrQualityRule> rules, int qcStage) {
        List<HisEmrQcDefect> defects = new ArrayList<>();
        for (HisEmrQualityRule rule : rules) {
            try {
                JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
                String field = cfg.getString("field");
                String label = StringUtils.hasText(cfg.getString("label")) ? cfg.getString("label") : field;
                String mode = StringUtils.hasText(cfg.getString("mode")) ? cfg.getString("mode") : "required";
                String v = fieldValue(record, field);
                boolean failed = false;
                String extra = "";
                switch (mode) {
                    case "required":
                        failed = !StringUtils.hasText(v);
                        if (failed) {
                            extra = "(" + label + "为空)";
                        }
                        break;
                    case "maxLen":
                        if (StringUtils.hasText(v) && cfg.getInteger("max") != null) {
                            failed = v.length() > cfg.getInteger("max");
                            if (failed) {
                                extra = "(实际" + v.length() + "字, 上限" + cfg.getInteger("max") + "字)";
                            }
                        }
                        break;
                    case "minLen":
                        if (StringUtils.hasText(v) && cfg.getInteger("min") != null) {
                            failed = v.length() < cfg.getInteger("min");
                            if (failed) {
                                extra = "(实际" + v.length() + "字, 下限" + cfg.getInteger("min") + "字)";
                            }
                        }
                        break;
                    case "range": {
                        BigDecimal n = toNum(v);
                        if (n != null) {
                            BigDecimal min = cfg.getBigDecimal("min");
                            BigDecimal max = cfg.getBigDecimal("max");
                            failed = (min != null && n.compareTo(min) < 0)
                                    || (max != null && n.compareTo(max) > 0);
                            if (failed) {
                                extra = "(实际值" + n + ")";
                            }
                        }
                        break;
                    }
                    case "pattern":
                        if (StringUtils.hasText(v) && StringUtils.hasText(cfg.getString("regex"))) {
                            try {
                                failed = !v.matches(cfg.getString("regex"));
                            } catch (Exception rex) {
                                failed = false; // 非法正则不判错
                            }
                        }
                        break;
                    case "enum":
                        if (StringUtils.hasText(v)) {
                            JSONArray values = cfg.getJSONArray("values");
                            failed = values == null || !containsValue(values, v);
                            if (failed) {
                                extra = "(值'" + v + "'不在允许值域内)";
                            }
                        }
                        break;
                    case "notContains":
                        /* 禁用词检查: 字段值含禁用词即缺陷(空值跳过, 必填由 required 负责) */
                        if (StringUtils.hasText(v)) {
                            String banned = cfg.getString("banned");
                            failed = StringUtils.hasText(banned) && v.contains(banned);
                            if (failed) {
                                extra = "(含禁用表述'" + banned + "')";
                            }
                        }
                        break;
                    default:
                        break;
                }
                if (failed) {
                    defects.add(buildContentDefect(rule, record, visit, qcStage,
                            rule.getRuleName() + (StringUtils.hasText(extra) ? extra : "")));
                }
            } catch (Exception e) {
                log.warn("内涵取值规则[{}]评估异常跳过: {}", rule.getRuleCode(), e.getMessage());
            }
        }
        return defects;
    }

    /** 内涵-项目对比质控: 两结构化字段一致性/包含/数值比较(任一基准缺失跳过, 必填由 item_value 负责) */
    private List<HisEmrQcDefect> evaluateItemCompare(HisInpMedicalRecord record, HisInpVisit visit,
                                                     List<HisEmrQualityRule> rules, int qcStage) {
        List<HisEmrQcDefect> defects = new ArrayList<>();
        for (HisEmrQualityRule rule : rules) {
            try {
                JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
                String leftField = cfg.getString("leftField");
                String rightField = cfg.getString("rightField");
                String op = StringUtils.hasText(cfg.getString("op")) ? cfg.getString("op") : "consistent";
                String left = fieldValue(record, leftField);
                String right = StringUtils.hasText(rightField) ? fieldValue(record, rightField) : "";
                /* 右值支持常量: 未配置字段时取 rightValue 常量 */
                if (!StringUtils.hasText(right) && StringUtils.hasText(cfg.getString("rightValue"))) {
                    right = cfg.getString("rightValue");
                }
                if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
                    continue;
                }
                boolean failed = false;
                switch (op) {
                    case "consistent": /* 双向包含视为一致(主诉与现病史等文本一致性) */
                        failed = !left.contains(right) && !right.contains(left);
                        break;
                    case "contains": /* 右值须包含左值(现病史须覆盖主诉症状) */
                        failed = !right.contains(left);
                        break;
                    case "notContains": /* 禁忌描述不得出现 */
                        failed = right.contains(left);
                        break;
                    case "eq":
                    case "ne":
                    case "gt":
                    case "lt":
                    case "ge":
                    case "le": {
                        BigDecimal ln = toNum(left);
                        BigDecimal rn = toNum(right);
                        if (ln == null || rn == null) {
                            continue;
                        }
                        failed = !compareResult(op, ln.compareTo(rn));
                        break;
                    }
                    default:
                        break;
                }
                if (failed) {
                    defects.add(buildContentDefect(rule, record, visit, qcStage, rule.getRuleName()));
                }
            } catch (Exception e) {
                log.warn("内涵对比规则[{}]评估异常跳过: {}", rule.getRuleCode(), e.getMessage());
            }
        }
        return defects;
    }

    /** 数值比较结果: op 与 compareTo 结果 c(-1/0/1) 判定 */
    private static boolean compareResult(String op, int c) {
        switch (op) {
            case "eq": return c == 0;
            case "ne": return c != 0;
            case "gt": return c > 0;
            case "lt": return c < 0;
            case "ge": return c >= 0;
            case "le": return c <= 0;
            default: return true;
        }
    }

    /** 内涵-病种质控: 按住院诊断 ICD 码前缀激活规则, 检查必查项/禁忌项/伴随诊断(无诊断数据整类跳过) */
    private List<HisEmrQcDefect> evaluateDisease(HisInpMedicalRecord record, HisInpVisit visit,
                                                 List<HisEmrQualityRule> rules, int qcStage) {
        List<HisEmrQcDefect> defects = new ArrayList<>();
        if (rules.isEmpty() || visit == null || visit.getId() == null) {
            return defects; // 无就诊上下文无法取诊断
        }
        List<String[]> diags = loadVisitDiags(visit.getId());
        if (diags.isEmpty()) {
            return defects; // 无诊断数据不评估病种规则
        }
        String fullText = textContent(record);
        for (HisEmrQualityRule rule : rules) {
            try {
                JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
                String icdPrefix = cfg.getString("icdPrefix");
                String mode = StringUtils.hasText(cfg.getString("mode")) ? cfg.getString("mode") : "requiredItem";
                String itemText = cfg.getString("text");
                String itemField = cfg.getString("field");
                /* 规则激活: icdPrefix 匹配任一诊断码前缀(空=全病种通用) */
                boolean triggered = !StringUtils.hasText(icdPrefix);
                if (!triggered) {
                    for (String[] dg : diags) {
                        if (StringUtils.hasText(dg[0])
                                && dg[0].toUpperCase().startsWith(icdPrefix.toUpperCase())) {
                            triggered = true;
                            break;
                        }
                    }
                }
                if (!triggered) {
                    continue;
                }
                boolean failed = false;
                switch (mode) {
                    case "requiredItem": {
                        /* 病种必查项: 结构化字段有值(非'无') 或 全文含关键词(支持'|'分隔多关键词) */
                        String fv = fieldValue(record, itemField);
                        boolean hit = (StringUtils.hasText(fv) && !"无".equals(fv.trim()))
                                || textHitAny(fullText, itemText);
                        failed = !hit;
                        break;
                    }
                    case "forbiddenItem":
                        /* 病种禁忌项: 全文出现禁忌关键词(支持'|'分隔)即缺陷 */
                        failed = textHitAny(fullText, itemText);
                        break;
                    case "requiredDiag": {
                        /* 必备伴随诊断: 编码前缀或名称命中任一即满足 */
                        String needCode = cfg.getString("needDiagCode");
                        String needName = cfg.getString("needDiagName");
                        boolean has = false;
                        for (String[] dg : diags) {
                            if (StringUtils.hasText(needCode) && StringUtils.hasText(dg[0])
                                    && dg[0].toUpperCase().startsWith(needCode.toUpperCase())) {
                                has = true;
                            }
                            if (StringUtils.hasText(needName) && StringUtils.hasText(dg[1])
                                    && dg[1].contains(needName)) {
                                has = true;
                            }
                        }
                        failed = !has;
                        break;
                    }
                    default:
                        break;
                }
                if (failed) {
                    defects.add(buildContentDefect(rule, record, visit, qcStage, rule.getRuleName()));
                }
            } catch (Exception e) {
                log.warn("内涵病种规则[{}]评估异常跳过: {}", rule.getRuleCode(), e.getMessage());
            }
        }
        return defects;
    }

    /** 就诊诊断清单: [code, name] 列表(jdbcTemplate 显式 tenant_id, 逻辑删除过滤) */
    private List<String[]> loadVisitDiags(Long visitId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT diag_code, diag_name FROM his_inp_diagnosis WHERE tenant_id = ? AND inp_visit_id = ? AND deleted = 0",
                TenantContext.get(), visitId);
        List<String[]> diags = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            diags.add(new String[]{text(row.get("diag_code")), text(row.get("diag_name"))});
        }
        return diags;
    }

    /** 病历全文(content + structureData 原文拼接), 供关键词包含检查 */
    private String textContent(HisInpMedicalRecord rec) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(rec.getContent())) {
            sb.append(rec.getContent());
        }
        if (StringUtils.hasText(rec.getStructureData())) {
            sb.append(rec.getStructureData());
        }
        return sb.toString();
    }

    /** 多关键词任一命中(支持'|'分隔), 空关键词返回 false */
    private static boolean textHitAny(String fullText, String keywords) {
        if (!StringUtils.hasText(keywords)) {
            return false;
        }
        for (String kw : keywords.split("\\|")) {
            if (StringUtils.hasText(kw) && fullText.contains(kw.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 内涵-数值计算质控: BMI/出入量平衡/两字段差值/住院天数核对(计算因子缺失跳过) */
    private List<HisEmrQcDefect> evaluateCalculation(HisInpMedicalRecord record, HisInpVisit visit,
                                                     List<HisEmrQualityRule> rules, int qcStage) {
        List<HisEmrQcDefect> defects = new ArrayList<>();
        for (HisEmrQualityRule rule : rules) {
            try {
                JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
                String calc = StringUtils.hasText(cfg.getString("calc")) ? cfg.getString("calc") : "gap";
                BigDecimal min = cfg.getBigDecimal("min");
                BigDecimal max = cfg.getBigDecimal("max");
                BigDecimal value = null;
                String extra = "";
                switch (calc) {
                    case "bmi": {
                        BigDecimal weight = toNum(fieldValue(record, cfg.getString("weightField")));
                        BigDecimal height = toNum(fieldValue(record, cfg.getString("heightField")));
                        if (weight != null && height != null && height.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal hm = height.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
                            value = weight.divide(hm.multiply(hm), 1, RoundingMode.HALF_UP);
                            extra = "(BMI=" + value + ")";
                        }
                        break;
                    }
                    case "io": {
                        /* 出入量平衡: 摄入-排出差值 */
                        BigDecimal intake = toNum(fieldValue(record, cfg.getString("intakeField")));
                        BigDecimal output = toNum(fieldValue(record, cfg.getString("outputField")));
                        if (intake != null && output != null) {
                            value = intake.subtract(output);
                            extra = "(差值" + value + "ml)";
                        }
                        break;
                    }
                    case "gap": {
                        BigDecimal left = toNum(fieldValue(record, cfg.getString("leftField")));
                        BigDecimal right = toNum(fieldValue(record, cfg.getString("rightField")));
                        if (left != null && right != null) {
                            value = left.subtract(right).abs();
                            extra = "(差值" + value + ")";
                        }
                        break;
                    }
                    case "los": {
                        /* 住院天数核对: 就诊实际天数 vs 结构化字段填写值 */
                        if (visit != null && visit.getAdmitDate() != null) {
                            LocalDateTime end = visit.getDischargeDate() != null
                                    ? visit.getDischargeDate()
                                    : (record.getRecordTime() != null ? record.getRecordTime() : LocalDateTime.now());
                            long days = Math.abs(java.time.temporal.ChronoUnit.DAYS
                                    .between(visit.getAdmitDate(), end));
                            BigDecimal filled = toNum(fieldValue(record, cfg.getString("losField")));
                            if (filled != null) {
                                value = filled.subtract(new BigDecimal(days)).abs();
                                extra = "(填写" + filled + "天, 实际" + days + "天)";
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }
                if (value == null) {
                    continue; // 计算因子缺失不判错
                }
                boolean failed = (min != null && value.compareTo(min) < 0)
                        || (max != null && value.compareTo(max) > 0);
                if (failed) {
                    defects.add(buildContentDefect(rule, record, visit, qcStage, rule.getRuleName() + extra));
                }
            } catch (Exception e) {
                log.warn("内涵计算规则[{}]评估异常跳过: {}", rule.getRuleCode(), e.getMessage());
            }
        }
        return defects;
    }

    /** 内涵-业务事件质控: 事件关键词触发后检查关联文书存在性/必填字段/完成时限 */
    private List<HisEmrQcDefect> evaluateEvent(HisInpMedicalRecord record, HisInpVisit visit,
                                               List<HisEmrQualityRule> rules, int qcStage) {
        List<HisEmrQcDefect> defects = new ArrayList<>();
        String fullText = textContent(record);
        for (HisEmrQualityRule rule : rules) {
            try {
                JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
                String mode = StringUtils.hasText(cfg.getString("mode")) ? cfg.getString("mode") : "recordExists";
                String eventText = cfg.getString("eventText");
                /* 事件触发: 当前病历出现事件关键词(支持'|'分隔多关键词, 未配置=无条件触发) */
                boolean triggered = !StringUtils.hasText(eventText) || textHitAny(fullText, eventText);
                if (!triggered) {
                    continue;
                }
                boolean failed = false;
                switch (mode) {
                    case "recordExists": {
                        /* 关联文书存在性: 该就诊须存在指定类型病历 */
                        Integer needType = cfg.getInteger("requiredRecordType");
                        if (needType != null && record.getInpVisitId() != null) {
                            Long cnt = recordMapper.selectCount(Wrappers.<HisInpMedicalRecord>lambdaQuery()
                                    .eq(HisInpMedicalRecord::getInpVisitId, record.getInpVisitId())
                                    .eq(HisInpMedicalRecord::getRecordType, needType));
                            failed = cnt == null || cnt == 0;
                        }
                        break;
                    }
                    case "fieldExists": {
                        /* 事件必填字段: 如抢救记录须填写抢救经过 */
                        String field = cfg.getString("field");
                        failed = !StringUtils.hasText(fieldValue(record, field));
                        break;
                    }
                    case "timeLimit": {
                        /* 事件时限: 触发事件后指定小时内须完成关联文书(以该类型病历最早完成时间计) */
                        Integer hours = cfg.getInteger("hours");
                        Integer needType = cfg.getInteger("requiredRecordType");
                        if (hours != null && needType != null && record.getInpVisitId() != null) {
                            List<HisInpMedicalRecord> rel = recordMapper.selectList(
                                    Wrappers.<HisInpMedicalRecord>lambdaQuery()
                                            .eq(HisInpMedicalRecord::getInpVisitId, record.getInpVisitId())
                                            .eq(HisInpMedicalRecord::getRecordType, needType)
                                            .orderByAsc(HisInpMedicalRecord::getRecordTime));
                            if (rel.isEmpty()) {
                                failed = true;
                            } else {
                                HisInpMedicalRecord first = rel.get(0);
                                LocalDateTime done = first.getRecordTime() != null
                                        ? first.getRecordTime() : first.getCreateTime();
                                LocalDateTime base = record.getRecordTime() != null
                                        ? record.getRecordTime() : record.getCreateTime();
                                if (done != null && base != null && done.isAfter(base.plusHours(hours))) {
                                    failed = true;
                                }
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }
                if (failed) {
                    defects.add(buildContentDefect(rule, record, visit, qcStage, rule.getRuleName()));
                }
            } catch (Exception e) {
                log.warn("内涵事件规则[{}]评估异常跳过: {}", rule.getRuleCode(), e.getMessage());
            }
        }
        return defects;
    }

    /** 构建内涵缺陷实体(公共字段填充: 缺陷类型=内涵, 自动生成, 未整改) */
    private HisEmrQcDefect buildContentDefect(HisEmrQualityRule rule, HisInpMedicalRecord record,
                                              HisInpVisit visit, int qcStage, String desc) {
        HisEmrQcDefect d = new HisEmrQcDefect();
        d.setRecordId(record.getId());
        d.setVisitId(record.getInpVisitId());
        d.setPatientId(visit != null ? visit.getPatientId() : null);
        d.setRuleId(rule.getId());
        d.setRuleCode(rule.getRuleCode());
        d.setRuleName(rule.getRuleName());
        d.setDefectType("内涵");
        d.setDefectDesc(desc);
        d.setDeductScore(rule.getDeductScore());
        d.setSeverity(rule.getSeverity() == null ? 2 : rule.getSeverity());
        d.setQcStage(qcStage);
        d.setAutoGenerated(1);
        d.setStatus(0);
        return d;
    }

    /* ===================== P5a-3 病案首页精准质控(2026-10) ===================== */

    /**
     * 病案首页四维精准质控: 完整性(40%)/逻辑性(25%)/真实性(20%)/一致性(15%)独立评估,
     * 各维 100 分起扣, 加权总分 ≥90 甲 / 70-89 乙 / <70 丙。
     * 病案首页无 ORM 实体, 复用 jdbcTemplate 直查(显式 tenant_id + deleted=0);
     * 纯查询评估不落库, 可重复调用; 四维独立 try-catch, 单维异常不影响整体。
     */
    public Map<String, Object> evaluateHomepage(Long casePageId) {
        if (casePageId == null) {
            throw new BizException(400, "病案首页ID不能为空");
        }
        Map<String, Object> page;
        try {
            page = jdbcTemplate.queryForMap(
                    "SELECT id, visit_id, patient_id, admission_date, discharge_date, los_days,"
                            + " admission_diag_code, admission_diag_name, discharge_main_diag_code,"
                            + " discharge_main_diag_name, discharge_other_diags, pathology_diag,"
                            + " injury_poison_code, operation_records, blood_type, rh, allergy_drugs, autopsy,"
                            + " total_cost, drug_cost, exam_cost, treatment_cost, bed_cost, nursing_cost,"
                            + " material_cost, other_cost, self_pay, insurance_pay, status,"
                            + " doctor_sign_img, nurse_sign_img, qc_sign_img, qc_doctor_id"
                            + " FROM his_case_front_page WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    casePageId, TenantContext.get());
        } catch (EmptyResultDataAccessException e) {
            throw new BizException(400, "病案首页不存在");
        }
        List<Map<String, Object>> completenessDefects = new ArrayList<>();
        List<Map<String, Object>> logicDefects = new ArrayList<>();
        List<Map<String, Object>> truthDefects = new ArrayList<>();
        List<Map<String, Object>> consistencyDefects = new ArrayList<>();
        Map<String, Object> patient = loadPatientInfo(text(page.get("patient_id")));
        try {
            checkHomepageCompleteness(page, patient, completenessDefects);
        } catch (Exception e) {
            log.warn("首页完整性检查异常: casePageId={}, {}", casePageId, e.getMessage());
        }
        try {
            checkHomepageLogic(page, patient, logicDefects);
        } catch (Exception e) {
            log.warn("首页逻辑性检查异常: casePageId={}, {}", casePageId, e.getMessage());
        }
        try {
            checkHomepageTruth(page, truthDefects);
        } catch (Exception e) {
            log.warn("首页真实性检查异常: casePageId={}, {}", casePageId, e.getMessage());
        }
        try {
            checkHomepageConsistency(page, consistencyDefects);
        } catch (Exception e) {
            log.warn("首页一致性检查异常: casePageId={}, {}", casePageId, e.getMessage());
        }
        BigDecimal completenessScore = dimensionScore(completenessDefects);
        BigDecimal logicScore = dimensionScore(logicDefects);
        BigDecimal truthScore = dimensionScore(truthDefects);
        BigDecimal consistencyScore = dimensionScore(consistencyDefects);
        BigDecimal total = completenessScore.multiply(new BigDecimal("0.40"))
                .add(logicScore.multiply(new BigDecimal("0.25")))
                .add(truthScore.multiply(new BigDecimal("0.20")))
                .add(consistencyScore.multiply(new BigDecimal("0.15")))
                .setScale(1, RoundingMode.HALF_UP);
        String grade = total.compareTo(new BigDecimal("90")) >= 0 ? "甲"
                : (total.compareTo(new BigDecimal("70")) >= 0 ? "乙" : "丙");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("casePageId", casePageId);
        result.put("visitId", page.get("visit_id"));
        result.put("score", total);
        result.put("grade", grade);
        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("completeness", buildDimension(completenessScore, completenessDefects));
        dimensions.put("logic", buildDimension(logicScore, logicDefects));
        dimensions.put("truth", buildDimension(truthScore, truthDefects));
        dimensions.put("consistency", buildDimension(consistencyScore, consistencyDefects));
        result.put("dimensions", dimensions);
        log.info("病案首页四维质控: casePageId={}, score={}, grade={}, 缺陷分布 完整{}/逻辑{}/真实{}/一致{}",
                casePageId, total, grade, completenessDefects.size(), logicDefects.size(),
                truthDefects.size(), consistencyDefects.size());
        return result;
    }

    /** 首页-完整性: 基本信息(患者档案核验)/住院信息/诊断/手术/费用必填检查 */
    private void checkHomepageCompleteness(Map<String, Object> page, Map<String, Object> patient,
                                            List<Map<String, Object>> defects) {
        /* 基本信息(患者档案核验) */
        if (patient.isEmpty()) {
            addHomepageDefect(defects, "基本信息", "患者档案缺失, 无法核验姓名/性别/出生日期", "15", 2);
        } else {
            if (!StringUtils.hasText(text(patient.get("name")))) {
                addHomepageDefect(defects, "基本信息", "患者姓名缺失", "10", 2);
            }
            if (!StringUtils.hasText(text(patient.get("gender")))) {
                addHomepageDefect(defects, "基本信息", "患者性别缺失", "5", 2);
            }
            if (patient.get("birth_date") == null) {
                addHomepageDefect(defects, "基本信息", "患者出生日期缺失, 无法计算年龄", "5", 2);
            }
        }
        /* 住院信息 */
        if (page.get("admission_date") == null) {
            addHomepageDefect(defects, "住院信息", "入院日期为空", "10", 2);
        }
        if (page.get("discharge_date") == null) {
            addHomepageDefect(defects, "住院信息", "出院日期为空", "10", 2);
        }
        if (page.get("los_days") == null) {
            addHomepageDefect(defects, "住院信息", "实际住院天数为空", "5", 2);
        }
        /* 诊断信息 */
        if (!StringUtils.hasText(text(page.get("admission_diag_code")))
                || !StringUtils.hasText(text(page.get("admission_diag_name")))) {
            addHomepageDefect(defects, "诊断信息", "入院诊断(编码/名称)不完整", "10", 2);
        }
        if (!StringUtils.hasText(text(page.get("discharge_main_diag_code")))
                || !StringUtils.hasText(text(page.get("discharge_main_diag_name")))) {
            addHomepageDefect(defects, "诊断信息", "出院主诊断(编码/名称)不完整", "15", 3);
        }
        /* 其他诊断 JSON 格式校验(空数组合法, 格式非法为缺陷) */
        String otherDiagsRaw = text(page.get("discharge_other_diags"));
        if (StringUtils.hasText(otherDiagsRaw)) {
            try {
                JSON.parseArray(otherDiagsRaw);
            } catch (Exception e) {
                addHomepageDefect(defects, "诊断信息", "其他诊断 JSON 格式非法", "5", 2);
            }
        }
        /* 手术信息: 有手术记录时逐条校验名称/编码/术者/日期必填 */
        JSONArray ops = parseArraySafe(text(page.get("operation_records")));
        for (int i = 0; i < ops.size(); i++) {
            JSONObject op = ops.getJSONObject(i);
            if (!StringUtils.hasText(op.getString("name")) || !StringUtils.hasText(op.getString("code"))) {
                addHomepageDefect(defects, "手术信息", "第" + (i + 1) + "条手术名称/编码缺失", "10", 2);
            }
            if (!StringUtils.hasText(op.getString("surgeon"))) {
                addHomepageDefect(defects, "手术信息", "第" + (i + 1) + "条手术术者缺失", "5", 2);
            }
            if (op.get("date") == null) {
                addHomepageDefect(defects, "手术信息", "第" + (i + 1) + "条手术日期缺失", "5", 2);
            }
        }
        /* 费用信息 */
        BigDecimal totalCost = toNum(text(page.get("total_cost")));
        if (totalCost == null || totalCost.compareTo(BigDecimal.ZERO) == 0) {
            addHomepageDefect(defects, "费用信息", "总费用未填写或为零", "10", 2);
        }
        String[] costFields = {"drug_cost", "exam_cost", "treatment_cost", "bed_cost",
                "nursing_cost", "material_cost", "other_cost"};
        String[] costLabels = {"药品费", "检查费", "治疗费", "床位费", "护理费", "材料费", "其他费"};
        for (int i = 0; i < costFields.length; i++) {
            if (page.get(costFields[i]) == null) {
                addHomepageDefect(defects, "费用信息", costLabels[i] + "未填写", "2", 1);
            }
        }
        /* 血型 */
        if (!StringUtils.hasText(text(page.get("blood_type")))) {
            addHomepageDefect(defects, "基本信息", "血型未填写", "2", 1);
        }
    }

    /** 首页-逻辑性: 日期先后顺序/住院天数一致/诊断手术吻合/年龄疾病合理 */
    private void checkHomepageLogic(Map<String, Object> page, Map<String, Object> patient,
                                     List<Map<String, Object>> defects) {
        LocalDateTime admission = toDateTime(page.get("admission_date"));
        LocalDateTime discharge = toDateTime(page.get("discharge_date"));
        if (admission != null && discharge != null && discharge.isBefore(admission)) {
            addHomepageDefect(defects, "日期逻辑", "出院日期早于入院日期", "15", 3);
        }
        /* 手术日期须落在住院区间内 */
        JSONArray ops = parseArraySafe(text(page.get("operation_records")));
        for (int i = 0; i < ops.size(); i++) {
            JSONObject op = ops.getJSONObject(i);
            LocalDateTime opDate = toDateTime(op.get("date"));
            if (opDate != null && admission != null && discharge != null
                    && (opDate.isBefore(admission) || opDate.isAfter(discharge))) {
                addHomepageDefect(defects, "日期逻辑",
                        "第" + (i + 1) + "条手术日期不在住院区间内", "10", 2);
            }
        }
        /* 住院天数一致性(相差>1天为缺陷) */
        BigDecimal losDays = toNum(text(page.get("los_days")));
        if (losDays != null && admission != null && discharge != null) {
            long actual = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(admission, discharge));
            if (losDays.subtract(new BigDecimal(actual)).abs().compareTo(BigDecimal.ONE) > 0) {
                addHomepageDefect(defects, "日期逻辑",
                        "住院天数填写" + losDays + "天与实际" + actual + "天不符", "5", 2);
            }
        }
        /* 主诊断与手术吻合(提醒级): 有手术但主诊断为慢病编码 */
        String mainCode = text(page.get("discharge_main_diag_code")).toUpperCase();
        if (!ops.isEmpty() && StringUtils.hasText(mainCode)
                && (mainCode.startsWith("I10") || mainCode.startsWith("E11") || mainCode.startsWith("E78"))) {
            addHomepageDefect(defects, "诊断手术吻合",
                    "存在手术记录但主诊断为慢病编码(" + mainCode + "), 请核实手术适应证", "0", 1);
        }
        /* 年龄与疾病合理性 */
        LocalDateTime birth = toDateTime(patient.get("birth_date"));
        if (birth != null && admission != null && StringUtils.hasText(mainCode)) {
            long age = java.time.temporal.ChronoUnit.YEARS.between(birth, admission);
            if ((mainCode.startsWith("I10") || mainCode.startsWith("E11")) && age < 14) {
                addHomepageDefect(defects, "年龄疾病合理",
                        "主诊断" + mainCode + "与年龄" + age + "岁不匹配(高血压/糖尿病常见于成人)", "10", 2);
            }
            if (mainCode.startsWith("O80") && (age < 12 || age > 55)) {
                addHomepageDefect(defects, "年龄疾病合理",
                        "分娩主诊断与年龄" + age + "岁不匹配", "10", 2);
            }
        }
    }

    /** 首页-真实性: ICD-10 诊断码/ICD-9-CM-3 手术码格式/费用非负/总额分项吻合 */
    private void checkHomepageTruth(Map<String, Object> page, List<Map<String, Object>> defects) {
        /* ICD-10 格式: 字母+两位数字(可带小数位) */
        String icd10Regex = "^[A-Z][0-9]{2}(\\.[0-9]{1,3})?$";
        String admissionCode = text(page.get("admission_diag_code")).toUpperCase();
        if (StringUtils.hasText(admissionCode) && !admissionCode.matches(icd10Regex)) {
            addHomepageDefect(defects, "编码真实", "入院诊断编码" + admissionCode + "不符合ICD-10格式", "5", 2);
        }
        String mainCode = text(page.get("discharge_main_diag_code")).toUpperCase();
        if (StringUtils.hasText(mainCode) && !mainCode.matches(icd10Regex)) {
            addHomepageDefect(defects, "编码真实", "出院主诊断编码" + mainCode + "不符合ICD-10格式", "10", 3);
        }
        String injuryCode = text(page.get("injury_poison_code")).toUpperCase();
        if (StringUtils.hasText(injuryCode) && !injuryCode.matches(icd10Regex)) {
            addHomepageDefect(defects, "编码真实", "损伤中毒编码" + injuryCode + "不符合ICD-10格式", "5", 2);
        }
        /* ICD-9-CM-3 手术码格式: 两位数字(可带小数位) */
        String icd9Regex = "^[0-9]{2}(\\.[0-9]{1,2})?$";
        JSONArray ops = parseArraySafe(text(page.get("operation_records")));
        for (int i = 0; i < ops.size(); i++) {
            JSONObject op = ops.getJSONObject(i);
            String opCode = text(op.getString("code")).toUpperCase();
            if (StringUtils.hasText(opCode) && !opCode.matches(icd9Regex)) {
                addHomepageDefect(defects, "编码真实",
                        "第" + (i + 1) + "条手术编码" + opCode + "不符合ICD-9-CM-3格式", "5", 2);
            }
        }
        /* 费用非负 */
        String[] costFields = {"total_cost", "drug_cost", "exam_cost", "treatment_cost", "bed_cost",
                "nursing_cost", "material_cost", "other_cost", "self_pay", "insurance_pay"};
        for (String f : costFields) {
            BigDecimal c = toNum(text(page.get(f)));
            if (c != null && c.compareTo(BigDecimal.ZERO) < 0) {
                addHomepageDefect(defects, "费用真实", "费用项" + f + "为负数", "10", 3);
            }
        }
        /* 总额与分项之和吻合(差额>0.01为缺陷) */
        BigDecimal totalCost = toNum(text(page.get("total_cost")));
        if (totalCost != null) {
            BigDecimal sum = BigDecimal.ZERO;
            boolean allPresent = true;
            String[] parts = {"drug_cost", "exam_cost", "treatment_cost", "bed_cost",
                    "nursing_cost", "material_cost", "other_cost"};
            for (String f : parts) {
                BigDecimal c = toNum(text(page.get(f)));
                if (c == null) {
                    allPresent = false;
                    break;
                }
                sum = sum.add(c);
            }
            if (allPresent && totalCost.subtract(sum).abs().compareTo(new BigDecimal("0.01")) > 0) {
                addHomepageDefect(defects, "费用真实",
                        "总费用" + totalCost + "与分项合计" + sum + "不吻合", "10", 2);
            }
        }
        /* 住院天数合理区间 */
        BigDecimal losDays = toNum(text(page.get("los_days")));
        if (losDays != null && (losDays.compareTo(BigDecimal.ZERO) <= 0
                || losDays.compareTo(new BigDecimal("730")) > 0)) {
            addHomepageDefect(defects, "费用真实", "住院天数" + losDays + "不在合理区间(1-730)", "5", 2);
        }
    }

    /** 首页-一致性: 首页诊断vs病程诊断/首页手术vs手术记录文书/签名与状态匹配/出院小结存在 */
    private void checkHomepageConsistency(Map<String, Object> page, List<Map<String, Object>> defects) {
        Object visitObj = page.get("visit_id");
        Long visitId = visitObj == null ? null : Long.valueOf(text(visitObj));
        if (visitId == null) {
            addHomepageDefect(defects, "数据一致", "首页未关联住院就诊, 无法比对病程数据", "10", 2);
            return;
        }
        /* 1. 首页主诊断 vs 病程出院主诊断(his_inp_diagnosis diag_type=4) */
        List<Map<String, Object>> diags = jdbcTemplate.queryForList(
                "SELECT diag_code, diag_name, is_main FROM his_inp_diagnosis"
                        + " WHERE tenant_id = ? AND inp_visit_id = ? AND diag_type = 4 AND deleted = 0",
                TenantContext.get(), visitId);
        String mainInCourse = "";
        for (Map<String, Object> dg : diags) {
            if ("1".equals(text(dg.get("is_main")))) {
                mainInCourse = text(dg.get("diag_code"));
                break;
            }
        }
        String mainInPage = text(page.get("discharge_main_diag_code"));
        if (StringUtils.hasText(mainInCourse) && StringUtils.hasText(mainInPage)
                && !mainInPage.equalsIgnoreCase(mainInCourse)) {
            addHomepageDefect(defects, "诊断一致",
                    "首页主诊断(" + mainInPage + ")与病程出院主诊断(" + mainInCourse + ")不一致", "10", 2);
        }
        /* 2. 首页手术 vs 手术记录文书(record_type=6) */
        JSONArray ops = parseArraySafe(text(page.get("operation_records")));
        if (!ops.isEmpty()) {
            Long opRecordCnt = recordMapper.selectCount(Wrappers.<HisInpMedicalRecord>lambdaQuery()
                    .eq(HisInpMedicalRecord::getInpVisitId, visitId)
                    .eq(HisInpMedicalRecord::getRecordType, 6));
            if (opRecordCnt == null || opRecordCnt == 0) {
                addHomepageDefect(defects, "手术一致", "首页有" + ops.size() + "条手术但病历缺手术记录文书", "10", 2);
            } else if (opRecordCnt < ops.size()) {
                addHomepageDefect(defects, "手术一致",
                        "首页手术" + ops.size() + "条多于手术记录文书" + opRecordCnt + "份, 请核实", "0", 1);
            }
        }
        /* 3. 签名与状态匹配: 已提交须医师签名, 已审核须质控签名+质控医师 */
        int status = 1;
        try {
            status = Integer.parseInt(text(page.get("status")).trim());
        } catch (Exception ignore) {
            status = 1;
        }
        if (status >= 2 && !StringUtils.hasText(text(page.get("doctor_sign_img")))) {
            addHomepageDefect(defects, "签名一致", "首页已提交但缺医师签名", "10", 2);
        }
        if (status >= 2 && !StringUtils.hasText(text(page.get("nurse_sign_img")))) {
            addHomepageDefect(defects, "签名一致", "首页已提交但缺护士签名", "0", 1);
        }
        if (status == 3 && !StringUtils.hasText(text(page.get("qc_sign_img")))) {
            addHomepageDefect(defects, "签名一致", "首页已审核但缺质控签名", "5", 2);
        }
        if (status == 3 && page.get("qc_doctor_id") == null) {
            addHomepageDefect(defects, "签名一致", "首页已审核但缺质控医师", "5", 2);
        }
        /* 4. 出院小结存在性: 已提交首页须有出院小结文书(record_type=8) */
        if (status >= 2) {
            Long summaryCnt = recordMapper.selectCount(Wrappers.<HisInpMedicalRecord>lambdaQuery()
                    .eq(HisInpMedicalRecord::getInpVisitId, visitId)
                    .eq(HisInpMedicalRecord::getRecordType, 8));
            if (summaryCnt == null || summaryCnt == 0) {
                addHomepageDefect(defects, "文书一致", "首页已提交但缺出院小结文书", "10", 2);
            }
        }
    }

    /** 患者档案信息(name/gender/birth_date), 缺失返回空 Map */
    private Map<String, Object> loadPatientInfo(String patientId) {
        if (!StringUtils.hasText(patientId) || "null".equals(patientId)) {
            return Collections.emptyMap();
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT name, gender, birth_date FROM his_patient WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    Long.valueOf(patientId.trim()), TenantContext.get());
            return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** 追加首页缺陷项(扣分入池供维度计分) */
    private void addHomepageDefect(List<Map<String, Object>> defects, String item,
                                    String desc, String deduct, int severity) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("item", item);
        d.put("desc", desc);
        d.put("deductScore", new BigDecimal(deduct));
        d.put("severity", severity);
        defects.add(d);
    }

    /** 维度得分: 100 - 累计扣分, 下限 0 */
    private BigDecimal dimensionScore(List<Map<String, Object>> defects) {
        BigDecimal deduct = BigDecimal.ZERO;
        for (Map<String, Object> d : defects) {
            Object v = d.get("deductScore");
            if (v instanceof BigDecimal) {
                deduct = deduct.add((BigDecimal) v);
            }
        }
        return FULL_SCORE.subtract(deduct).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
    }

    /** 维度结果组装 */
    private Map<String, Object> buildDimension(BigDecimal score, List<Map<String, Object>> defects) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("score", score);
        d.put("defectCount", defects.size());
        d.put("defects", defects);
        return d;
    }

    /* ===================== P5a-3 甲乙丙等级评定与缺陷查询(2026-10) ===================== */

    /**
     * 甲乙丙等级评定: ≥90 甲 / 70-89 乙 / <70 丙; 存在一票否决缺陷(severity=3 且未整改/未豁免)直接丙。
     * 未评分(null)视为丙级, 评定节点留痕 his_emr_qc_node。
     */
    public Map<String, Object> gradeRecord(Long recordId) {
        HisInpMedicalRecord rec = recordId == null ? null : recordMapper.selectById(recordId);
        if (rec == null) {
            throw new BizException(400, "病历记录不存在");
        }
        if (rec.getInpVisitId() != null) {
            HisInpVisit visit = visitMapper.selectById(rec.getInpVisitId());
            if (visit != null) {
                checkVisitAccess(visit);
            }
        }
        /* 一票否决: severity=3 且未整改(1)/未豁免(4) */
        List<HisEmrQcDefect> vetoList = qcDefectMapper.selectList(Wrappers.<HisEmrQcDefect>lambdaQuery()
                .eq(HisEmrQcDefect::getRecordId, recordId)
                .eq(HisEmrQcDefect::getSeverity, 3)
                .notIn(HisEmrQcDefect::getStatus, 1, 4));
        Long defectCount = qcDefectMapper.selectCount(Wrappers.<HisEmrQcDefect>lambdaQuery()
                .eq(HisEmrQcDefect::getRecordId, recordId));
        BigDecimal score = rec.getQualityScore();
        boolean vetoApplied = !vetoList.isEmpty();
        String grade;
        if (vetoApplied) {
            grade = "丙";
        } else if (score == null) {
            grade = "丙"; // 未评分按丙级处理(须先执行质控评分)
        } else if (score.compareTo(new BigDecimal("90")) >= 0) {
            grade = "甲";
        } else if (score.compareTo(new BigDecimal("70")) >= 0) {
            grade = "乙";
        } else {
            grade = "丙";
        }
        /* 评定节点留痕(失败不影响评定结果) */
        try {
            HisEmrQcNode node = new HisEmrQcNode();
            node.setRecordId(recordId);
            node.setVisitId(rec.getInpVisitId());
            node.setNodeType("质控");
            node.setNodeDesc("甲乙丙等级评定: " + grade + (vetoApplied ? "(一票否决)" : "")
                    + ", 评分" + (score == null ? "未评" : score.toString()));
            node.setScoreBefore(score);
            node.setScoreAfter(score);
            LoginUser op = UserContext.get();
            node.setOperatorId(op != null ? op.getUserId() : null);
            node.setOperatorName(op != null && StringUtils.hasText(op.getRealName())
                    ? op.getRealName() : (op != null ? op.getUsername() : "system"));
            qcNodeMapper.insert(node);
        } catch (Exception e) {
            log.warn("等级评定节点日志写入失败(不影响结果): recordId={}, {}", recordId, e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", recordId);
        result.put("score", score);
        result.put("grade", grade);
        result.put("vetoApplied", vetoApplied);
        result.put("vetoCount", vetoList.size());
        result.put("defectCount", defectCount == null ? 0 : defectCount);
        return result;
    }

    /** 查询病历的所有缺陷(未整改在前, 新近在前) */
    public List<HisEmrQcDefect> listDefects(Long recordId) {
        HisInpMedicalRecord rec = recordId == null ? null : recordMapper.selectById(recordId);
        if (rec == null) {
            throw new BizException(400, "病历记录不存在");
        }
        if (rec.getInpVisitId() != null) {
            HisInpVisit visit = visitMapper.selectById(rec.getInpVisitId());
            if (visit != null) {
                checkVisitAccess(visit);
            }
        }
        return qcDefectMapper.selectList(Wrappers.<HisEmrQcDefect>lambdaQuery()
                .eq(HisEmrQcDefect::getRecordId, recordId)
                .orderByAsc(HisEmrQcDefect::getStatus)
                .orderByDesc(HisEmrQcDefect::getId));
    }

    /** 查询就诊的所有缺陷 */
    public List<HisEmrQcDefect> listDefectsByVisit(Long visitId) {
        HisInpVisit visit = visitId == null ? null : visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(400, "住院就诊不存在");
        }
        checkVisitAccess(visit);
        return qcDefectMapper.selectList(Wrappers.<HisEmrQcDefect>lambdaQuery()
                .eq(HisEmrQcDefect::getVisitId, visitId)
                .orderByAsc(HisEmrQcDefect::getStatus)
                .orderByDesc(HisEmrQcDefect::getId));
    }

    /**
     * 缺陷统计(按类型/严重度/科室): month 为 yyyy-MM(空取当月), deptId 为空时全院并附带科室分布前十。
     * jdbcTemplate 直查(his_emr_qc_defect LEFT JOIN his_inp_visit 取科室)。
     */
    public Map<String, Object> defectStats(Long deptId, String month) {
        LocalDate firstDay;
        if (StringUtils.hasText(month)) {
            try {
                firstDay = LocalDate.parse(month.trim() + "-01");
            } catch (Exception e) {
                throw new BizException(400, "月份格式须为 yyyy-MM");
            }
        } else {
            firstDay = LocalDate.now().withDayOfMonth(1);
        }
        LocalDateTime start = firstDay.atStartOfDay();
        LocalDateTime end = firstDay.plusMonths(1).atStartOfDay();
        StringBuilder where = new StringBuilder(
                " WHERE d.tenant_id = ? AND d.create_time >= ? AND d.create_time < ?");
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get());
        args.add(start);
        args.add(end);
        if (deptId != null) {
            where.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        /* 按类型+严重度聚合 */
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT d.defect_type AS defectType, d.severity AS severity, COUNT(*) AS cnt"
                        + " FROM his_emr_qc_defect d LEFT JOIN his_inp_visit v"
                        + " ON d.visit_id = v.id AND v.tenant_id = d.tenant_id AND v.deleted = 0"
                        + where + " GROUP BY d.defect_type, d.severity", args.toArray());
        Map<String, Long> byType = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : rows) {
            long cnt = ((Number) row.get("cnt")).longValue();
            total += cnt;
            String type = StringUtils.hasText(text(row.get("defectType")))
                    ? text(row.get("defectType")) : "未分类";
            byType.put(type, byType.getOrDefault(type, 0L) + cnt);
            String sev = text(row.get("severity"));
            String sevKey = "3".equals(sev) ? "禁止" : ("2".equals(sev) ? "拦截" : "提醒");
            bySeverity.put(sevKey, bySeverity.getOrDefault(sevKey, 0L) + cnt);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", firstDay.toString().substring(0, 7));
        result.put("deptId", deptId);
        result.put("total", total);
        result.put("byType", byType);
        result.put("bySeverity", bySeverity);
        /* 科室分布(全院视角时附带前十) */
        if (deptId == null) {
            List<Map<String, Object>> deptRows = jdbcTemplate.queryForList(
                    "SELECT v.dept_id AS deptId, COUNT(*) AS cnt FROM his_emr_qc_defect d"
                            + " LEFT JOIN his_inp_visit v"
                            + " ON d.visit_id = v.id AND v.tenant_id = d.tenant_id AND v.deleted = 0"
                            + where + " GROUP BY v.dept_id ORDER BY cnt DESC LIMIT 10", args.toArray());
            Set<Long> deptIds = new LinkedHashSet<>();
            for (Map<String, Object> row : deptRows) {
                Object dv = row.get("deptId");
                if (dv != null) {
                    deptIds.add(Long.valueOf(text(dv)));
                }
            }
            Map<Long, String> deptNames = deptIds.isEmpty()
                    ? Collections.emptyMap() : loadDeptNames(deptIds);
            List<Map<String, Object>> byDept = new ArrayList<>();
            for (Map<String, Object> row : deptRows) {
                Object dv = row.get("deptId");
                Map<String, Object> item = new LinkedHashMap<>();
                if (dv == null) {
                    item.put("deptId", null);
                    item.put("deptName", "未关联就诊");
                } else {
                    Long did = Long.valueOf(text(dv));
                    item.put("deptId", did);
                    item.put("deptName", deptNames.getOrDefault(did, "未知科室(" + did + ")"));
                }
                item.put("count", ((Number) row.get("cnt")).longValue());
                byDept.add(item);
            }
            result.put("byDept", byDept);
        }
        return result;
    }

    /** 评分标准列表(按分类+ID排序) */
    public List<HisEmrScoreStandard> listScoreStandards() {
        return scoreStandardMapper.selectList(Wrappers.<HisEmrScoreStandard>lambdaQuery()
                .orderByAsc(HisEmrScoreStandard::getCategory)
                .orderByAsc(HisEmrScoreStandard::getId));
    }

    /** 更新评分标准(白名单字段: 名称/基准分/权重/说明/状态) */
    public R<HisEmrScoreStandard> updateScoreStandard(Long id, Map<String, Object> body) {
        HisEmrScoreStandard exist = id == null ? null : scoreStandardMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "评分标准不存在");
        }
        if (body == null || body.isEmpty()) {
            throw new BizException(400, "更新内容不能为空");
        }
        if (StringUtils.hasText(text(body.get("standardName")))) {
            exist.setStandardName(text(body.get("standardName")).trim());
        }
        BigDecimal base = toNum(text(body.get("baseScore")));
        if (base != null) {
            if (base.compareTo(BigDecimal.ZERO) < 0 || base.compareTo(FULL_SCORE) > 0) {
                throw new BizException(400, "基准分须在0-100之间");
            }
            exist.setBaseScore(base);
        }
        BigDecimal weight = toNum(text(body.get("weight")));
        if (weight != null) {
            if (weight.compareTo(BigDecimal.ZERO) <= 0 || weight.compareTo(new BigDecimal("10")) > 0) {
                throw new BizException(400, "权重须在(0,10]之间");
            }
            exist.setWeight(weight);
        }
        if (body.containsKey("description")) {
            exist.setDescription(text(body.get("description")));
        }
        String st = text(body.get("status"));
        if (StringUtils.hasText(st)) {
            Integer status;
            try {
                status = Integer.valueOf(st.trim());
            } catch (NumberFormatException e) {
                throw new BizException(400, "状态仅允许 1启用 0停用");
            }
            if (status != 0 && status != 1) {
                throw new BizException(400, "状态仅允许 1启用 0停用");
            }
            exist.setStatus(status);
        }
        scoreStandardMapper.updateById(exist);
        log.info("更新评分标准: id={}, code={}", id, exist.getStandardCode());
        return R.ok(exist);
    }

    /* ===================== P5a-3 内涵规则种子(2026-10, 400+条) ===================== */

    /*
     * 内涵规则种子 A(通用40 + 入院33 + 首次25 + 日常30 + 查房16 + 术前15):
     * 列序 {规则编码, 规则名称, record_type(null=全类型), rule_category,
     *       rule_config(JSONObject), 扣分(String), severity, qc_stage(0通用/1运行/2归档), control_level, 说明}
     * rule_type 统一 5(内涵), config.type 用五大引擎类型(itemValue/itemCompare/disease/calculation/event),
     * 旧 evaluateQuality 引擎遇未知 type 自动跳过, 互不干扰。
     */
    private static final Object[][] CONTENT_RULE_SEEDS_A = {
            /* ---- 通用规范(全类型, 40) ---- */
            {"IV_GEN_TITLE_LEN", "标题不超过50字", null, "item_value", ivMax("title", 50), "2", 1, 0, 1, "病历标题应简明, 不超过50字"},
            {"IV_GEN_CONTENT_MIN", "病历内容不少于30字", null, "item_value", ivMin("content", 30), "2", 1, 0, 1, "病历内容过于简短, 应完整记录"},
            {"IV_GEN_ORAL_1", "禁用口语'拉肚子'(规范用语:腹泻)", null, "item_value", ivBan("content", "拉肚子"), "2", 1, 0, 2, "病历须使用规范医学术语"},
            {"IV_GEN_ORAL_2", "禁用口语'发烧'(规范用语:发热)", null, "item_value", ivBan("content", "发烧"), "1", 1, 0, 1, "病历须使用规范医学术语"},
            {"IV_GEN_ORAL_3", "禁用口语'打点滴'(规范用语:静脉输液)", null, "item_value", ivBan("content", "打点滴"), "1", 1, 0, 1, "病历须使用规范医学术语"},
            {"IV_GEN_ORAL_4", "禁用口语'开刀'(规范用语:手术)", null, "item_value", ivBan("content", "开刀"), "1", 1, 0, 1, "病历须使用规范医学术语"},
            {"IV_GEN_ORAL_5", "禁用口语'老毛病'", null, "item_value", ivBan("content", "老毛病"), "1", 1, 0, 1, "病历须使用规范医学术语"},
            {"IV_GEN_ORAL_6", "禁用口语'挂水'", null, "item_value", ivBan("content", "挂水"), "1", 1, 0, 1, "病历须使用规范医学术语"},
            {"IV_GEN_ORAL_7", "禁用口语'肚子疼'(规范用语:腹痛)", null, "item_value", ivBan("content", "肚子疼"), "1", 1, 0, 1, "病历须使用规范医学术语"},
            {"IV_GEN_ORAL_8", "禁用口语'心口疼'(规范用语:胸痛)", null, "item_value", ivBan("content", "心口疼"), "1", 1, 0, 1, "病历须使用规范医学术语"},
            {"IV_GEN_ORAL_9", "禁用口语'吐血'(呕血/咯血需鉴别)", null, "item_value", ivBan("content", "吐血"), "1", 1, 0, 1, "呕血与咯血须鉴别表述"},
            {"IV_GEN_ORAL_10", "禁用口语'拉血'(规范用语:便血)", null, "item_value", ivBan("content", "拉血"), "1", 1, 0, 1, "病历须使用规范医学术语"},
            {"IV_GEN_PH_1", "无占位符'待补充'", null, "item_value", ivBan("content", "待补充"), "3", 2, 0, 2, "病历定稿不得残留占位符"},
            {"IV_GEN_PH_2", "无占位符'待完善'", null, "item_value", ivBan("content", "待完善"), "3", 2, 0, 2, "病历定稿不得残留占位符"},
            {"IV_GEN_PH_3", "无占位符'XXX'", null, "item_value", ivBan("content", "XXX"), "3", 2, 0, 2, "病历定稿不得残留占位符"},
            {"IV_GEN_PH_4", "无省略表述'略'", null, "item_value", ivBan("content", "略"), "2", 2, 0, 2, "病历内容须完整, 不得省略"},
            {"IV_GEN_PH_5", "无省略表述'同上'", null, "item_value", ivBan("content", "同上"), "2", 2, 0, 2, "病历须自包含, 不得引用省略"},
            {"IV_GEN_PH_6", "无引用表述'详见'", null, "item_value", ivBan("content", "详见"), "1", 2, 0, 1, "病历须自包含关键信息"},
            {"IV_GEN_PH_7", "无未决问号'", null, "item_value", ivBan("content", "？"), "1", 2, 0, 1, "病历定稿不得残留待确认问号"},
            {"IV_GEN_PH_8", "无草稿标记'TODO'", null, "item_value", ivBan("content", "TODO"), "3", 2, 0, 2, "病历定稿不得残留草稿标记"},
            {"IV_GEN_PH_9", "无掩码残留'***'", null, "item_value", ivBan("content", "***"), "2", 2, 0, 2, "病历定稿不得残留掩码"},
            {"IV_GEN_PH_10", "不得使用'患者甲'代称", null, "item_value", ivBan("content", "患者甲"), "2", 2, 0, 2, "病历须使用患者真实信息"},
            {"IV_GEN_VITAL_T", "体温记录合理(35-42℃)", null, "item_value", ivRange("temperature", 35, 42), "3", 2, 0, 2, "体温记录须在生理合理区间"},
            {"IV_GEN_VITAL_P", "脉搏记录合理(30-220次/分)", null, "item_value", ivRange("pulse", 30, 220), "3", 2, 0, 2, "脉搏记录须在生理合理区间"},
            {"IV_GEN_VITAL_R", "呼吸记录合理(8-40次/分)", null, "item_value", ivRange("respiration", 8, 40), "3", 2, 0, 2, "呼吸记录须在生理合理区间"},
            {"IV_GEN_VITAL_SBP", "收缩压记录合理(60-260mmHg)", null, "item_value", ivRange("systolic", 60, 260), "3", 2, 0, 2, "收缩压记录须在生理合理区间"},
            {"IV_GEN_VITAL_DBP", "舒张压记录合理(30-180mmHg)", null, "item_value", ivRange("diastolic", 30, 180), "3", 2, 0, 2, "舒张压记录须在生理合理区间"},
            {"IV_GEN_SPO2", "血氧饱和度合理(50-100%)", null, "item_value", ivRange("spo2", 50, 100), "3", 2, 0, 2, "血氧饱和度记录须在合理区间"},
            {"IV_GEN_PAIN", "疼痛评分合理(0-10分)", null, "item_value", ivRange("painScore", 0, 10), "2", 2, 0, 2, "NRS疼痛评分须在0-10分"},
            {"IV_GEN_GLUCOSE", "血糖值合理(1-35mmol/L)", null, "item_value", ivRange("bloodSugar", 1, 35), "3", 2, 0, 2, "血糖记录须在生理合理区间"},
            {"IV_GEN_HEIGHT", "身高记录合理(30-250cm)", null, "item_value", ivRange("height", 30, 250), "2", 1, 0, 1, "身高记录须在合理区间"},
            {"IV_GEN_WEIGHT", "体重记录合理(2-300kg)", null, "item_value", ivRange("weight", 2, 300), "2", 1, 0, 1, "体重记录须在合理区间"},
            {"CALC_GEN_BMI", "BMI计算合理提示(18.5-27.9)", null, "calculation", calcBmi("weight", "height", 18.5, 27.9), "0", 1, 0, 1, "体重身高齐备时自动核算BMI并提示合理区间"},
            {"CMP_GEN_BP_S_D", "收缩压须大于舒张压", null, "item_compare", cmp("gt", "systolic", "diastolic"), "3", 2, 0, 2, "血压记录收缩压/舒张压逻辑校验"},
            {"EVT_GEN_CONSENT", "有创操作须有知情同意书", null, "event", evtRecord("穿刺|活检|介入|有创", 13), "5", 2, 1, 2, "提及有创操作时该就诊须存在知情同意文书"},
            {"EVT_GEN_TRANSFUSION_CONSENT", "输血须有输血治疗同意书", null, "event", evtRecord("输血", 13), "5", 2, 1, 2, "提及输血时该就诊须存在知情同意文书"},
            {"EVT_GEN_ANESTHESIA_CONSENT", "麻醉须有麻醉同意书", null, "event", evtRecord("麻醉", 13), "3", 2, 1, 2, "提及麻醉时该就诊须存在知情同意文书"},
            {"EVT_GEN_CRITICAL_VALUE", "危急值须记录并处置", null, "event", evtField("危急值", "criticalValueHandle"), "5", 2, 1, 2, "出现危急值须记录处置情况"},
            {"EVT_GEN_ADR", "药物不良反应须上报", null, "event", evtField("不良反应|副作用", "adrReport"), "3", 2, 1, 2, "药物不良反应须上报并记录"},
            {"EVT_GEN_INFORM", "病情恶化须告知家属并记录", null, "event", evtField("恶化|加重|突变", "familyNotice"), "5", 2, 1, 2, "病情变化须履行告知义务并留痕"},
            /* ---- 入院记录(record_type=1, 33) ---- */
            {"IV_ADMIT_CC_MAX", "主诉不超过200字", 1, "item_value", ivMax("chiefComplaint", 200), "2", 1, 1, 1, "主诉应简明扼要"},
            {"IV_ADMIT_CC_MIN", "主诉不少于8字", 1, "item_value", ivMin("chiefComplaint", 8), "2", 2, 1, 2, "主诉须包含症状与时间要素"},
            {"IV_ADMIT_CC_TIME", "主诉须含症状持续时间", 1, "item_value", ivPat("chiefComplaint", "(?s).*\\d+\\s*(年|月|周|日|天|小时|时|分).*"), "2", 1, 1, 1, "主诉应记录症状持续时间"},
            {"IV_ADMIT_CC_SYMPTOM", "主诉须以症状为主要表述", 1, "item_value", ivPat("chiefComplaint", "(?s).*(痛|咳|热|肿|闷|悸|晕|吐|泻|血|麻|疹|黄|乏|痒|抽|汗|聋|哑|胀|频繁|不适).*"), "2", 1, 1, 1, "主诉应围绕主要症状描述"},
            {"IV_ADMIT_PI_MIN", "现病史不少于100字", 1, "item_value", ivMin("presentIllness", 100), "3", 2, 1, 2, "现病史应完整记录起病与演变"},
            {"IV_ADMIT_PI_TREAT", "现病史须记载院外诊疗经过", 1, "item_value", ivPat("presentIllness", "(?s).*(治疗|就诊|服药|院外|外院|自服).*"), "3", 1, 1, 1, "现病史应记录入院前诊治情况"},
            {"IV_ADMIT_PI_COURSE", "现病史须记载病情演变", 1, "item_value", ivPat("presentIllness", "(?s).*(加重|好转|反复|进展|缓解|变化).*"), "3", 1, 1, 1, "现病史应体现病情演变过程"},
            {"IV_ADMIT_FAMILY", "家族史必填", 1, "item_value", ivReq("familyHistory"), "3", 2, 1, 2, "家族史不得为空(无特殊须注明'无')"},
            {"IV_ADMIT_PERSONAL", "个人史必填", 1, "item_value", ivReq("personalHistory"), "3", 2, 1, 2, "个人史不得为空(无特殊须注明'无')"},
            {"IV_ADMIT_MARRIAGE", "婚育史必填", 1, "item_value", ivReq("marriageHistory"), "2", 1, 1, 1, "婚育史不得为空(无特殊须注明'无')"},
            {"IV_ADMIT_ALLERGY_MARK", "过敏史须明确标注", 1, "item_value", ivPat("allergyHistory", "(?s).*(无|否认|过敏|青霉素|头孢|磺胺|阿司匹林|海鲜|花粉|药物|食物).*"), "3", 2, 1, 2, "过敏史须明确过敏原或注明'无'"},
            {"IV_ADMIT_EXAM_MIN", "体格检查不少于50字", 1, "item_value", ivMin("physicalExam", 50), "3", 2, 1, 2, "体格检查应完整记录"},
            {"IV_ADMIT_EXAM_VITAL", "体格检查须含生命体征", 1, "item_value", ivPat("physicalExam", "(?s).*(体温|T\\s*[:：]).*(脉搏|P\\s*[:：]).*(呼吸|R\\s*[:：]).*(血压|BP\\s*[:：]).*"), "3", 2, 1, 2, "体格检查须记录体温/脉搏/呼吸/血压"},
            {"IV_ADMIT_EXAM_HEART", "心肺听诊检查必含", 1, "item_value", ivPat("physicalExam", "(?s).*(心|肺).{0,20}(音|律|清|啰).*"), "3", 1, 1, 1, "体格检查须含心肺听诊描述"},
            {"IV_ADMIT_EXAM_SPEC", "专科情况必填", 1, "item_value", ivReq("specialistExam"), "5", 2, 1, 2, "入院记录须记录专科检查情况"},
            {"IV_ADMIT_DIAG_REQ", "初步诊断必填", 1, "item_value", ivReq("admitDiagnosis"), "5", 2, 1, 2, "入院记录须有初步诊断"},
            {"IV_ADMIT_PLAN_MIN", "诊疗计划不少于30字", 1, "item_value", ivMin("treatPlan", 30), "3", 2, 1, 2, "诊疗计划应具体可行"},
            {"IV_ADMIT_PLAN_DETAIL", "诊疗计划须分条列明", 1, "item_value", ivPat("treatPlan", "(?s).*(1|一|①).*(2|二|②).*"), "2", 1, 1, 1, "诊疗计划应分条目列明"},
            {"IV_ADMIT_ASSIST", "入院前辅助检查结果须记录", 1, "item_value", ivPat("content", "(?s).*(检查|检验|化验|CT|X光|B超|心电图).*"), "2", 1, 1, 1, "入院前辅助检查结果应记录"},
            {"CMP_ADMIT_CC_PI", "主诉须在现病史中体现", 1, "item_compare", cmp("contains", "chiefComplaint", "presentIllness"), "3", 2, 1, 2, "现病史应围绕主诉展开"},
            {"CMP_ADMIT_DIAG_PLAN", "诊疗计划须覆盖主要诊断", 1, "item_compare", cmp("contains", "admitDiagnosis", "treatPlan"), "3", 1, 1, 1, "诊疗计划应针对初步诊断制定"},
            {"CMP_ADMIT_EXAM_DIAG", "体检发现须与诊断呼应", 1, "item_compare", cmp("consistent", "specialistExam", "admitDiagnosis"), "2", 1, 1, 1, "阳性体征应支撑初步诊断"},
            {"DIS_ADMIT_HT_BP", "高血压入院须记录血压", 1, "disease", disNeed("I10", "bloodPressure", "血压"), "3", 2, 1, 2, "高血压患者入院须记录血压值"},
            {"DIS_ADMIT_DM_SUGAR", "糖尿病入院须查血糖", 1, "disease", disNeed("E11", "bloodSugar", "血糖"), "3", 2, 1, 2, "糖尿病患者入院须有血糖结果"},
            {"DIS_ADMIT_PNEU_TEMP", "肺炎入院须记录体温", 1, "disease", disNeed("J18", "temperature", "体温"), "3", 2, 1, 2, "肺炎患者入院须记录体温"},
            {"DIS_ADMIT_MI_ECG", "心梗入院须查心电图", 1, "disease", disNeed("I21", "ecg", "心电图"), "5", 2, 1, 2, "心梗患者入院须有心电图"},
            {"CALC_ADMIT_BMI", "入院须评估BMI", 1, "calculation", calcBmi("weight", "height", 18.5, 27.9), "0", 1, 1, 1, "体重身高齐备时自动核算BMI"},
            {"EVT_ADMIT_CONSENT", "入院提及手术/特殊检查须有知情同意", 1, "event", evtRecord("手术|特殊检查|输血|造影", 13), "3", 2, 1, 2, "涉及手术/特检须有知情同意文书"},
            {"EVT_ADMIT_CRITICAL", "危重患者入院须告病危并记录", 1, "event", evtField("病危|危重|休克", "criticalNotice"), "5", 2, 1, 2, "危重患者须下达病危通知并记录"},
            {"IV_ADMIT_PI_CC_REL", "现病史发病时间与主诉一致", 1, "item_compare", cmp("consistent", "chiefComplaint", "presentIllness"), "2", 1, 1, 1, "起病时间表述应前后一致"},
            {"DIS_ADMIT_I63_CT", "脑梗入院须有头颅影像", 1, "disease", disNeed("I63", "ct", "CT|磁共振"), "5", 2, 1, 2, "脑梗患者入院须有头颅CT/MRI"},
            {"EVT_ADMIT_VITAL_FREQ", "入院须记录生命体征基线", 1, "item_value", ivReq("vitalSigns"), "3", 2, 1, 2, "入院记录须有生命体征基线数据"},
            /* ---- 首次病程(record_type=2, 25) ---- */
            {"IV_FIRST_CASE_MIN", "病例特点不少于50字", 2, "item_value", ivMin("caseFeatures", 50), "3", 2, 1, 2, "病例特点应完整概括"},
            {"IV_FIRST_BASIS", "诊断依据必填", 2, "item_value", ivReq("diagnosticBasis"), "5", 2, 1, 2, "首次病程须列明诊断依据"},
            {"IV_FIRST_DIFF_MIN", "鉴别诊断不少于30字", 2, "item_value", ivMin("diffDiag", 30), "3", 2, 1, 2, "鉴别诊断应具体分析"},
            {"IV_FIRST_DIFF_NUM", "鉴别诊断须不少于2个", 2, "item_value", ivPat("diffDiag", "(?s).*(、|/|，|与|;|；|2|二|两).*"), "3", 1, 1, 1, "鉴别诊断应至少考虑两个疾病"},
            {"IV_FIRST_DIFF_REASON", "鉴别诊断须有排除依据", 2, "item_value", ivPat("diffDiag", "(?s).*(不支持|排除|依据).*"), "3", 1, 1, 1, "鉴别诊断应有排除依据分析"},
            {"IV_FIRST_PLAN_MIN", "诊疗计划不少于30字", 2, "item_value", ivMin("treatPlan", 30), "3", 2, 1, 2, "诊疗计划应具体可行"},
            {"IV_FIRST_PLAN_DETAIL", "计划须体现具体措施", 2, "item_value", ivPat("treatPlan", "(?s).*(检查|治疗|用药|监测|手术|护理).*"), "3", 1, 1, 1, "诊疗计划应含具体诊疗措施"},
            {"IV_FIRST_PROG", "须有预后评估", 2, "item_value", ivReq("prognosis"), "3", 1, 1, 1, "首次病程应评估预后"},
            {"IV_FIRST_RISK", "须有病情危险分层", 2, "item_value", ivReq("riskStratify"), "3", 1, 1, 1, "首次病程应评估病情风险"},
            {"IV_FIRST_ASSESS", "病情评估结论必填", 2, "item_value", ivReq("assessment"), "5", 2, 1, 2, "首次病程须有病情评估"},
            {"IV_FIRST_ASSESS_LEN", "病情评估不少于20字", 2, "item_value", ivMin("assessment", 20), "2", 2, 1, 2, "病情评估应具体"},
            {"IV_FIRST_EVIDENCE", "诊疗决策须有循证依据表述", 2, "item_value", ivPat("content", "(?s).*(指南|循证|依据|推荐).*"), "2", 1, 1, 1, "诊疗决策应体现循证依据"},
            {"IV_FIRST_MONITOR", "须有病情监测计划", 2, "item_value", ivReq("monitorPlan"), "2", 1, 1, 1, "首次病程应制定监测计划"},
            {"CMP_FIRST_CC_CASE", "主诉须在病例特点中体现", 2, "item_compare", cmp("contains", "chiefComplaint", "caseFeatures"), "3", 2, 1, 2, "病例特点应涵盖主诉"},
            {"CMP_FIRST_DIAG_PLAN", "计划须针对初步诊断", 2, "item_compare", cmp("contains", "admitDiagnosis", "treatPlan"), "3", 1, 1, 1, "诊疗计划应针对主要诊断"},
            {"CMP_FIRST_DIFF_DIAG", "鉴别诊断须围绕主要诊断", 2, "item_compare", cmp("consistent", "admitDiagnosis", "diffDiag"), "2", 1, 1, 1, "鉴别诊断应与主要诊断相关"},
            {"CMP_FIRST_ASSESS_CASE", "病情评估须基于病例特点", 2, "item_compare", cmp("contains", "caseFeatures", "assessment"), "2", 1, 1, 1, "评估应基于病例特点展开"},
            {"DIS_FIRST_HT_TARGET", "高血压首次病程须有血压监测计划", 2, "disease", disNeed("I10", "bloodPressure", "血压"), "3", 2, 1, 2, "高血压须制定血压监测计划"},
            {"DIS_FIRST_DM_SUGAR", "糖尿病首次病程须有血糖管理计划", 2, "disease", disNeed("E11", "bloodSugar", "血糖"), "3", 2, 1, 2, "糖尿病须制定血糖管理计划"},
            {"DIS_FIRST_MI_ECG", "心梗首次病程须有心电图描述", 2, "disease", disNeed("I21", "ecg", "心电图"), "5", 2, 1, 2, "心梗须结合心电图分析"},
            {"DIS_FIRST_PNEU_XRAY", "肺炎首次病程须有影像结果", 2, "disease", disNeed("J18", "xray", "胸片|CT"), "3", 2, 1, 2, "肺炎须结合影像学分析"},
            {"DIS_FIRST_I63_CT", "脑梗首次病程须有头颅影像", 2, "disease", disNeed("I63", "ct", "CT|磁共振"), "5", 2, 1, 2, "脑梗须结合头颅影像分析"},
            {"CALC_FIRST_BMI", "首次病程须评估BMI", 2, "calculation", calcBmi("weight", "height", 18.5, 27.9), "0", 1, 1, 1, "体重身高齐备时自动核算BMI"},
            {"EVT_FIRST_ROUND", "入院48小时内须有上级查房记录", 2, "event", evtRecord(null, 4), "0", 1, 1, 1, "上级医师首次查房须在48小时内完成"},
            {"EVT_FIRST_CONSENT", "涉及有创诊疗须有知情同意", 2, "event", evtRecord("穿刺|活检|介入", 13), "3", 2, 1, 2, "有创诊疗须有知情同意文书"},
            /* ---- 日常病程(record_type=3, 30) ---- */
            {"IV_DAILY_S_LEN", "主观资料不少于10字", 3, "item_value", ivMin("subjective", 10), "2", 2, 1, 2, "主观资料(S)应具体"},
            {"IV_DAILY_O_LEN", "客观资料不少于10字", 3, "item_value", ivMin("objective", 10), "2", 2, 1, 2, "客观资料(O)应具体"},
            {"IV_DAILY_A_LEN", "评估不少于15字", 3, "item_value", ivMin("assessment", 15), "3", 2, 1, 2, "评估(A)应具体"},
            {"IV_DAILY_P_LEN", "计划不少于15字", 3, "item_value", ivMin("plan", 15), "3", 2, 1, 2, "计划(P)应具体"},
            {"IV_DAILY_O_VITAL", "客观资料须含体征或检查结果", 3, "item_value", ivPat("objective", "(?s).*(体温|血压|脉搏|呼吸|血氧|血糖|检验|检查|体征).*"), "3", 1, 1, 1, "客观资料应记录体征/检查结果"},
            {"IV_DAILY_A_REASON", "评估须有病情变化分析", 3, "item_value", ivPat("assessment", "(?s).*(好转|加重|稳定|平稳|改善|变化).*"), "3", 1, 1, 1, "评估应分析病情变化趋势"},
            {"IV_DAILY_P_ADJ", "计划须含处置措施", 3, "item_value", ivPat("plan", "(?s).*(继续|调整|停用|加用|复查|观察|监测).*"), "3", 1, 1, 1, "计划应明确处置措施"},
            {"IV_DAILY_O_COMPARE", "复查异常指标须对比记录", 3, "item_value", ivPat("objective", "(?s).*(复查|较前|对比|变化).*"), "2", 1, 1, 1, "复查结果应与前期对比"},
            {"IV_DAILY_O_DRUG", "用药后反应观察", 3, "item_value", ivPat("content", "(?s).*(用药|给药|输注).{0,40}(反应|耐受|不适|缓解).*"), "2", 1, 1, 1, "用药后应观察记录反应"},
            {"IV_DAILY_VITAL_T", "体温记录合理", 3, "item_value", ivRange("temperature", 35, 42), "3", 2, 1, 2, "体温记录须在合理区间"},
            {"IV_DAILY_IO_REQ", "出入量须每日记录", 3, "item_value", ivReq("intake"), "2", 1, 1, 1, "危重/留置患者须记录出入量"},
            {"CMP_DAILY_SO_O", "主观变化与客观记录一致", 3, "item_compare", cmp("consistent", "subjective", "objective"), "2", 1, 1, 1, "主客观资料应相互印证"},
            {"CMP_DAILY_AO_O", "评估须基于客观资料", 3, "item_compare", cmp("contains", "objective", "assessment"), "2", 1, 1, 1, "评估应引用客观依据"},
            {"CMP_DAILY_A_P", "计划须与评估衔接", 3, "item_compare", cmp("consistent", "assessment", "plan"), "2", 1, 1, 1, "处置计划应基于评估结论"},
            {"CMP_DAILY_S_A", "主诉与评估变化一致", 3, "item_compare", cmp("consistent", "subjective", "assessment"), "2", 1, 1, 1, "症状变化与评估应一致"},
            {"DIS_DAILY_HT_BP", "高血压病程须监测血压", 3, "disease", disNeed("I10", "bloodPressure", "血压"), "3", 2, 1, 2, "高血压病程须记录血压监测"},
            {"DIS_DAILY_DM_SUGAR", "糖尿病病程须监测血糖", 3, "disease", disNeed("E11", "bloodSugar", "血糖"), "3", 2, 1, 2, "糖尿病病程须记录血糖监测"},
            {"DIS_DAILY_MI_VITAL", "心梗病程须心电监护记录", 3, "disease", disNeed("I21", "monitor", "监护"), "3", 2, 1, 2, "心梗病程须有监护记录"},
            {"DIS_DAILY_HEART_FAIL", "心衰病程须监测体重与出入量", 3, "disease", disNeed("I50", "weight", "体重"), "3", 2, 1, 2, "心衰须每日监测体重"},
            {"DIS_DAILY_PNEU_SPO2", "肺炎病程须监测血氧", 3, "disease", disNeed("J18", "spo2", "血氧"), "3", 2, 1, 2, "肺炎须监测血氧饱和度"},
            {"DIS_DAILY_COPD_SPO2", "COPD病程须监测血氧", 3, "disease", disNeed("J44", "spo2", "血氧"), "3", 2, 1, 2, "COPD须监测血氧饱和度"},
            {"DIS_DAILY_LIVER", "肝硬化病程须监测凝血与出血", 3, "disease", disNeed("K74", "coagulation", "凝血"), "3", 2, 1, 2, "肝硬化须监测凝血功能"},
            {"CALC_DAILY_IO", "出入量平衡(24h差≤1500ml)", 3, "calculation", calcIo("intake", "output", 1500), "3", 2, 1, 2, "24小时出入量差值须在合理范围"},
            {"EVT_DAILY_CRISIS", "危重变化须即时记录处置", 3, "event", evtField("危重|恶化|病危", "crisisNote"), "5", 2, 1, 2, "危重变化须即时记录"},
            {"EVT_DAILY_PREOP", "择期手术前须有术前小结", 3, "event", evtRecord("手术", 5), "3", 1, 1, 1, "提及手术须有术前小结文书"},
            {"EVT_DAILY_OP_RECORD", "提及手术须有手术记录文书", 3, "event", evtRecord("手术", 6), "3", 2, 1, 2, "提及手术须有手术记录文书"},
            {"EVT_DAILY_INFECT", "感染征象须监测感染指标", 3, "event", evtField("感染|白细胞升高", "infectionMonitor"), "3", 1, 1, 1, "感染征象须监测感染指标"},
            {"EVT_DAILY_TRANSFUSION", "输血须有输血记录", 3, "event", evtField("输血", "transfusionRecord"), "5", 2, 1, 2, "输血须有输血记录"},
            {"EVT_DAILY_ALLERGY", "新发现过敏须记录", 3, "event", evtField("过敏", "allergyFinding"), "3", 2, 1, 1, "新发现药物过敏须记录"},
            {"EVT_DAILY_ORDER_EXEC", "危重医嘱须记录执行反馈", 3, "event", evtField("病危|特级护理", "orderFeedback"), "3", 1, 1, 1, "危重医嘱须记录执行反馈"},
            /* ---- 查房记录(record_type=4, 16) ---- */
            {"IV_ROUND_ANALYSIS", "查房须有病情分析", 4, "item_value", ivReq("analysis"), "5", 2, 1, 2, "查房记录须有病情分析"},
            {"IV_ROUND_OPINION_LEN", "上级医师意见不少于20字", 4, "item_value", ivMin("attendingOpinion", 20), "3", 2, 1, 2, "上级意见应具体明确"},
            {"IV_ROUND_PLAN_ADJ", "查房须有诊疗计划调整意见", 4, "item_value", ivReq("treatPlan"), "3", 2, 1, 2, "查房须明确诊疗计划"},
            {"IV_ROUND_LEVEL", "查房级别标注规范", 4, "item_value", ivEnum("roundLevel", "主治医师查房", "副主任医师查房", "主任医师查房", "三级查房"), "2", 1, 1, 1, "查房级别应规范标注"},
            {"IV_ROUND_STAGE", "病情分期/分级记录", 4, "item_value", ivReq("staging"), "2", 1, 1, 1, "查房应记录病情分级"},
            {"IV_ROUND_NEXT", "下一步诊疗计划明确", 4, "item_value", ivReq("nextPlan"), "2", 1, 1, 1, "查房须明确下一步计划"},
            {"CMP_ROUND_SO_O", "上级意见须基于客观依据", 4, "item_compare", cmp("contains", "objective", "attendingOpinion"), "2", 1, 1, 1, "上级意见应结合客观依据"},
            {"CMP_ROUND_ANALYSIS_OP", "意见须与病情分析一致", 4, "item_compare", cmp("consistent", "analysis", "attendingOpinion"), "2", 1, 1, 1, "上级意见应基于病情分析"},
            {"CMP_ROUND_A_P", "计划调整须与评估一致", 4, "item_compare", cmp("consistent", "analysis", "treatPlan"), "2", 1, 1, 1, "计划应基于评估结论"},
            {"DIS_ROUND_HT_BP", "高血压查房须记录血压", 4, "disease", disNeed("I10", "bloodPressure", "血压"), "3", 2, 1, 2, "高血压查房须记录血压"},
            {"DIS_ROUND_DM_GLUCOSE", "糖尿病查房须记录血糖", 4, "disease", disNeed("E11", "bloodSugar", "血糖"), "3", 2, 1, 2, "糖尿病查房须记录血糖"},
            {"DIS_ROUND_MI_MONITOR", "心梗查房须评估监护结果", 4, "disease", disNeed("I21", "monitor", "监护"), "3", 2, 1, 2, "心梗查房须评估监护结果"},
            {"DIS_ROUND_TUMOR", "肿瘤查房须评估治疗方案", 4, "disease", disNeed("C34", "treatment", "化疗|放疗|手术|靶向"), "3", 2, 1, 2, "肿瘤查房须评估治疗方案"},
            {"DIS_ROUND_HEART_FAIL", "心衰查房须记录体重与尿量", 4, "disease", disNeed("I50", "weight", "体重"), "3", 2, 1, 2, "心衰查房须记录体重"},
            {"EVT_ROUND_ADJUST", "治疗方案调整须说明依据", 4, "event", evtField("调整|变更", "adjustReason"), "3", 2, 1, 2, "方案调整须说明依据"},
            {"EVT_ROUND_INFORM", "查房发现病情变化须告知", 4, "event", evtField("加重|恶化|新发", "familyNotice"), "3", 1, 1, 1, "病情变化须告知并留痕"},
            /* ---- 术前小结(record_type=5, 15) ---- */
            {"IV_PREOP_DIAG", "术前诊断明确", 5, "item_value", ivReq("preopDiag"), "5", 2, 1, 2, "术前小结须明确术前诊断"},
            {"IV_PREOP_INDICATION", "手术指征必填", 5, "item_value", ivReq("indication"), "5", 2, 1, 2, "术前小结须列明手术指征"},
            {"IV_PREOP_CONTRA", "手术禁忌评估必填", 5, "item_value", ivReq("contraindication"), "3", 2, 1, 2, "术前须评估手术禁忌"},
            {"IV_PREOP_PLAN", "拟施手术名称必填", 5, "item_value", ivReq("opName"), "5", 2, 1, 2, "术前小结须明确拟施手术"},
            {"IV_PREOP_ANESTH", "拟麻醉方式必填", 5, "item_value", ivReq("anesthesia"), "3", 2, 1, 2, "术前小结须明确麻醉方式"},
            {"IV_PREOP_RISK", "术前风险评估必填", 5, "item_value", ivReq("riskAssess"), "5", 2, 1, 2, "术前须评估手术风险"},
            {"IV_PREOP_ASA", "ASA分级评估规范", 5, "item_value", ivEnum("asaGrade", "I级", "II级", "III级", "IV级", "V级", "VI级"), "2", 1, 1, 1, "ASA分级应规范标注"},
            {"IV_PREOP_BLOOD", "备血记录(中大型手术)", 5, "item_value", ivReq("bloodPrepare"), "2", 1, 1, 1, "中大型手术须记录备血"},
            {"IV_PREOP_CONSENT_SIGNED", "知情同意书已签署", 5, "item_value", ivReq("consentSigned"), "5", 2, 1, 2, "术前须签署知情同意书"},
            {"CMP_PREOP_DIAG_IND", "指征须与术前诊断呼应", 5, "item_compare", cmp("contains", "preopDiag", "indication"), "3", 2, 1, 2, "手术指征应基于术前诊断"},
            {"CMP_PREOP_RISK_CONTRA", "风险评估须覆盖禁忌排查", 5, "item_compare", cmp("contains", "contraindication", "riskAssess"), "2", 1, 1, 1, "风险评估应含禁忌排查"},
            {"EVT_PREOP_EXAM", "术前须完成必要检查", 5, "event", evtField(null, "preopExam"), "3", 2, 1, 2, "术前须完成必要检查并记录"},
            {"EVT_PREOP_CONSENT_RECORD", "手术须有知情同意书文书", 5, "event", evtRecord("手术", 13), "5", 2, 1, 2, "该就诊须存在知情同意文书"},
            {"DIS_PREOP_HT_BP", "高血压术前血压控制评估", 5, "disease", disNeed("I10", "bloodPressure", "血压"), "3", 2, 1, 2, "高血压术前须评估血压控制"},
            {"DIS_PREOP_DM_SUGAR", "糖尿病术前血糖控制评估", 5, "disease", disNeed("E11", "bloodSugar", "血糖"), "3", 2, 1, 2, "糖尿病术前须评估血糖控制"}
    };

    /* ---------- P5a-3 内涵规则配置构建器(与五大评估引擎 mode 一一对应) ---------- */

    /** 项目取值-必填 */
    private static JSONObject ivReq(String field) {
        JSONObject o = new JSONObject();
        o.put("type", "itemValue");
        o.put("field", field);
        o.put("mode", "required");
        return o;
    }

    /** 项目取值-最大长度 */
    private static JSONObject ivMax(String field, int max) {
        JSONObject o = new JSONObject();
        o.put("type", "itemValue");
        o.put("field", field);
        o.put("mode", "maxLen");
        o.put("max", max);
        return o;
    }

    /** 项目取值-最小长度 */
    private static JSONObject ivMin(String field, int min) {
        JSONObject o = new JSONObject();
        o.put("type", "itemValue");
        o.put("field", field);
        o.put("mode", "minLen");
        o.put("min", min);
        return o;
    }

    /** 项目取值-数值区间 */
    private static JSONObject ivRange(String field, double min, double max) {
        JSONObject o = new JSONObject();
        o.put("type", "itemValue");
        o.put("field", field);
        o.put("mode", "range");
        o.put("min", min);
        o.put("max", max);
        return o;
    }

    /** 项目取值-正则匹配 */
    private static JSONObject ivPat(String field, String regex) {
        JSONObject o = new JSONObject();
        o.put("type", "itemValue");
        o.put("field", field);
        o.put("mode", "pattern");
        o.put("regex", regex);
        return o;
    }

    /** 项目取值-枚举值域 */
    private static JSONObject ivEnum(String field, String... values) {
        JSONObject o = new JSONObject();
        o.put("type", "itemValue");
        o.put("field", field);
        o.put("mode", "enum");
        JSONArray arr = new JSONArray();
        for (String v : values) {
            arr.add(v);
        }
        o.put("values", arr);
        return o;
    }

    /** 项目取值-禁用词检查 */
    private static JSONObject ivBan(String field, String banned) {
        JSONObject o = new JSONObject();
        o.put("type", "itemValue");
        o.put("field", field);
        o.put("mode", "notContains");
        o.put("banned", banned);
        return o;
    }

    /** 项目对比-两字段比较(consistent/contains/notContains/eq/ne/gt/lt/ge/le) */
    private static JSONObject cmp(String op, String leftField, String rightField) {
        JSONObject o = new JSONObject();
        o.put("type", "itemCompare");
        o.put("op", op);
        o.put("leftField", leftField);
        o.put("rightField", rightField);
        return o;
    }

    /** 病种-必查项(icdPrefix 为空=全病种; field 结构化字段, text 关键词'|'分隔) */
    private static JSONObject disNeed(String icdPrefix, String field, String text) {
        JSONObject o = new JSONObject();
        o.put("type", "disease");
        if (icdPrefix != null) {
            o.put("icdPrefix", icdPrefix);
        }
        o.put("mode", "requiredItem");
        o.put("field", field);
        if (text != null) {
            o.put("text", text);
        }
        return o;
    }

    /** 计算-BMI(体重kg/身高cm, 区间[min,max]外提醒) */
    private static JSONObject calcBmi(String weightField, String heightField, double min, double max) {
        JSONObject o = new JSONObject();
        o.put("type", "calculation");
        o.put("calc", "bmi");
        o.put("weightField", weightField);
        o.put("heightField", heightField);
        o.put("min", min);
        o.put("max", max);
        return o;
    }

    /** 计算-出入量平衡(差值绝对值超 max 为缺陷) */
    private static JSONObject calcIo(String intakeField, String outputField, double max) {
        JSONObject o = new JSONObject();
        o.put("type", "calculation");
        o.put("calc", "io");
        o.put("intakeField", intakeField);
        o.put("outputField", outputField);
        o.put("max", max);
        return o;
    }

    /** 计算-两字段差值(绝对值超 max 为缺陷) */
    private static JSONObject calcGap(String leftField, String rightField, double max) {
        JSONObject o = new JSONObject();
        o.put("type", "calculation");
        o.put("calc", "gap");
        o.put("leftField", leftField);
        o.put("rightField", rightField);
        o.put("max", max);
        return o;
    }

    /** 计算-住院天数核对(填写值与实际差超 max 天为缺陷) */
    private static JSONObject calcLos(String losField, double max) {
        JSONObject o = new JSONObject();
        o.put("type", "calculation");
        o.put("calc", "los");
        o.put("losField", losField);
        o.put("max", max);
        return o;
    }

    /** 事件-关联文书存在性(eventText 关键词'|'分隔, null=无条件) */
    private static JSONObject evtRecord(String eventText, Integer requiredRecordType) {
        JSONObject o = new JSONObject();
        o.put("type", "event");
        o.put("mode", "recordExists");
        if (eventText != null) {
            o.put("eventText", eventText);
        }
        o.put("requiredRecordType", requiredRecordType);
        return o;
    }

    /** 事件-必填字段(eventText 关键词'|'分隔, null=无条件) */
    private static JSONObject evtField(String eventText, String field) {
        JSONObject o = new JSONObject();
        o.put("type", "event");
        o.put("mode", "fieldExists");
        if (eventText != null) {
            o.put("eventText", eventText);
        }
        o.put("field", field);
        return o;
    }

    /** 事件-完成时限(触发后 hours 小时内须存在 requiredRecordType 文书) */
    private static JSONObject evtTime(String eventText, Integer requiredRecordType, int hours) {
        JSONObject o = new JSONObject();
        o.put("type", "event");
        o.put("mode", "timeLimit");
        if (eventText != null) {
            o.put("eventText", eventText);
        }
        o.put("requiredRecordType", requiredRecordType);
        o.put("hours", hours);
        return o;
    }

    /*
     * 内涵规则种子 B(手术36 + 术后19 + 出院26 + 死亡18 + 首页25 + 其他类型24 + 病种专项93):
     * 病种专项按 ICD-10 前缀触发(诊断码命中即激活必查项检查), 覆盖高频病种。
     */
    private static final Object[][] CONTENT_RULE_SEEDS_B = {
            /* ---- 手术记录(record_type=6, 36) ---- */
            {"IV_OP_NAME", "手术名称必填", 6, "item_value", ivReq("opName"), "5", 2, 1, 2, "手术记录须明确手术名称"},
            {"IV_OP_DATE", "手术日期时间必填", 6, "item_value", ivReq("opDate"), "5", 2, 1, 2, "手术记录须明确手术日期时间"},
            {"IV_OP_SURGEON", "术者必填", 6, "item_value", ivReq("surgeon"), "5", 2, 1, 2, "手术记录须明确术者"},
            {"IV_OP_ASSIST", "助手必填", 6, "item_value", ivReq("assistant"), "3", 1, 1, 1, "手术记录须记录助手"},
            {"IV_OP_ANESTH", "麻醉方式必填", 6, "item_value", ivReq("anesthesia"), "3", 2, 1, 2, "手术记录须明确麻醉方式"},
            {"IV_OP_ANESTHIST", "麻醉师必填", 6, "item_value", ivReq("anesthetist"), "3", 1, 1, 1, "手术记录须记录麻醉师"},
            {"IV_OP_PROCESS", "手术经过不少于100字", 6, "item_value", ivMin("opProcess", 100), "5", 2, 1, 2, "手术经过应详细记录"},
            {"IV_OP_PROCESS_DETAIL", "经过须含步骤描述", 6, "item_value", ivPat("opProcess", "(?s).*(切开|分离|止血|缝合|置入|切除).*"), "3", 1, 1, 1, "手术经过应含关键操作步骤"},
            {"IV_OP_FINDINGS", "术中所见必填", 6, "item_value", ivReq("findings"), "5", 2, 1, 2, "手术记录须描述术中所见"},
            {"IV_OP_BLEED", "术中出血量必填", 6, "item_value", ivReq("bloodLoss"), "3", 2, 1, 2, "手术记录须记录出血量"},
            {"IV_OP_BLEED_RANGE", "出血量合理(0-5000ml)", 6, "item_value", ivRange("bloodLoss", 0, 5000), "3", 2, 1, 2, "出血量记录须在合理区间"},
            {"IV_OP_TRANSFUSION", "输血记录(有输血必填)", 6, "item_value", ivReq("transfusion"), "3", 1, 1, 1, "输血情况须记录(无输血须注明'无')"},
            {"IV_OP_SPECIMEN", "标本送检情况必填", 6, "item_value", ivReq("specimen"), "3", 1, 1, 1, "标本须记录送检情况"},
            {"IV_OP_PATHO_SPEC", "标本处理记录", 6, "item_value", ivReq("specimenHandle"), "2", 1, 1, 1, "标本处理方式须记录"},
            {"IV_OP_INCISION", "切口描述必填", 6, "item_value", ivReq("incision"), "3", 1, 1, 1, "手术切口须描述"},
            {"IV_OP_IMPLANT", "植入物须记录", 6, "item_value", ivReq("implant"), "3", 1, 1, 1, "植入物须记录(无植入须注明'无')"},
            {"IV_OP_DRAIN", "引流情况必填", 6, "item_value", ivReq("drainage"), "3", 1, 1, 1, "引流情况须记录"},
            {"IV_OP_DURATION", "手术时长必填", 6, "item_value", ivReq("duration"), "3", 2, 1, 2, "手术时长须记录"},
            {"IV_OP_DURATION_RANGE", "手术时长合理(0.5-12h)", 6, "item_value", ivRange("duration", 0.5, 12), "3", 1, 1, 1, "手术时长须在合理区间"},
            {"IV_OP_ANESTH_ENUM", "麻醉方式规范", 6, "item_value", ivEnum("anesthesia", "全身麻醉", "椎管内麻醉", "局部麻醉", "神经阻滞", "表面麻醉", "基础麻醉", "复合麻醉"), "2", 1, 1, 1, "麻醉方式应规范表述"},
            {"IV_OP_ANESTH_DRUG", "麻醉用药记录", 6, "item_value", ivReq("anesthesiaDrug"), "2", 1, 1, 1, "麻醉用药须记录"},
            {"IV_OP_POSITION", "手术体位记录", 6, "item_value", ivReq("position"), "2", 1, 1, 1, "手术体位须记录"},
            {"IV_OP_COUNT", "器械敷料清点记录", 6, "item_value", ivReq("countCheck"), "3", 2, 1, 2, "器械敷料清点须核对记录"},
            {"IV_OP_HEMO", "输血前须查血型交叉", 6, "item_value", ivReq("bloodCross"), "3", 1, 1, 1, "输血前须查血型与交叉配血"},
            {"IV_OP_TIMER", "三方核查记录", 6, "item_value", ivReq("surgicalCheck"), "3", 1, 1, 1, "手术安全三方核查须记录"},
            {"IV_OP_POSTOP_DIAG", "术后诊断必填", 6, "item_value", ivReq("postopDiag"), "5", 2, 1, 2, "手术记录须明确术后诊断"},
            {"CMP_OP_PREOP_POSTOP", "术前术后诊断一致", 6, "item_compare", cmp("consistent", "preopDiag", "postopDiag"), "3", 1, 1, 1, "诊断变更须在术中记录说明"},
            {"CMP_OP_ANESTH_PROCESS", "麻醉方式与经过一致", 6, "item_compare", cmp("consistent", "anesthesia", "opProcess"), "2", 1, 1, 1, "麻醉描述应与经过呼应"},
            {"CMP_OP_FIND_PROCESS", "术中所见与经过一致", 6, "item_compare", cmp("consistent", "findings", "opProcess"), "2", 1, 1, 1, "术中所见应与经过呼应"},
            {"CMP_OP_BLEED_TRANSFUSE", "出血量与输血量匹配", 6, "calculation", calcGap("bloodLoss", "transfusion", 3000), "2", 1, 1, 1, "出血量与输血量差值异常须核实"},
            {"DIS_OP_ANTIBIO", "手术须记录抗生素使用", 6, "disease", disNeed(null, "antibiotic", "抗生素"), "3", 1, 1, 1, "预防用抗生素须记录"},
            {"DIS_OP_HT_BP", "高血压术中血压记录", 6, "disease", disNeed("I10", "bloodPressure", "血压"), "3", 2, 1, 2, "高血压术中须监测血压"},
            {"DIS_OP_DM_SUGAR", "糖尿病术中血糖监测", 6, "disease", disNeed("E11", "bloodSugar", "血糖"), "3", 2, 1, 2, "糖尿病术中须监测血糖"},
            {"EVT_OP_POSTOP_FOLLOW", "术后须有术后病程记录", 6, "event", evtRecord(null, 7), "3", 1, 1, 1, "该就诊须存在术后病程文书"},
            {"EVT_OP_SPECIMEN_SEND", "标本采集须送病理", 6, "event", evtField("标本", "specimenSend"), "3", 1, 1, 1, "标本采集须记录送检去向"},
            {"EVT_OP_COMPLICATION", "术中并发症须记录处置", 6, "event", evtField("损伤|大出血|意外", "complicationHandle"), "5", 2, 1, 2, "术中意外须记录处置过程"},
            /* ---- 术后病程(record_type=7, 19) ---- */
            {"IV_POSTOP_VITAL", "术后须记录生命体征", 7, "item_value", ivReq("vitalSigns"), "5", 2, 1, 2, "术后病程须记录生命体征"},
            {"IV_POSTOP_VITAL_T", "术后体温合理", 7, "item_value", ivRange("temperature", 35, 42), "3", 2, 1, 2, "术后体温须在合理区间"},
            {"IV_POSTOP_BLEED", "术后出血观察", 7, "item_value", ivReq("bleeding"), "3", 1, 1, 1, "术后出血情况须观察记录"},
            {"IV_POSTOP_DRAIN", "术后引流量必填", 7, "item_value", ivReq("drainage"), "3", 2, 1, 2, "术后引流量须记录"},
            {"IV_POSTOP_DRAIN_RANGE", "引流量合理(0-2000ml)", 7, "item_value", ivRange("drainage", 0, 2000), "3", 2, 1, 2, "引流量须在合理区间"},
            {"IV_POSTOP_PAIN", "术后疼痛评估", 7, "item_value", ivReq("painScore"), "3", 1, 1, 1, "术后须评估疼痛"},
            {"IV_POSTOP_DIET", "术后饮食医嘱", 7, "item_value", ivReq("diet"), "3", 1, 1, 1, "术后饮食须医嘱明确"},
            {"IV_POSTOP_COMP", "术后并发症观察必填", 7, "item_value", ivReq("complication"), "5", 2, 1, 2, "术后须观察记录并发症"},
            {"IV_POSTOP_WOUND", "伤口情况必填", 7, "item_value", ivReq("wound"), "3", 2, 1, 2, "术后伤口情况须记录"},
            {"IV_POSTOP_MED", "术后用药记录", 7, "item_value", ivReq("medication"), "3", 1, 1, 1, "术后用药须记录"},
            {"IV_POSTOP_ANALGESIA", "术后镇痛评估", 7, "item_value", ivReq("analgesia"), "2", 1, 1, 1, "术后镇痛效果须评估"},
            {"CMP_POSTOP_WOUND_DRAIN", "伤口与引流观察一致", 7, "item_compare", cmp("consistent", "wound", "drainage"), "2", 1, 1, 1, "伤口与引流情况应相互印证"},
            {"CMP_POSTOP_VITAL_COMP", "生命体征与并发症观察一致", 7, "item_compare", cmp("consistent", "vitalSigns", "complication"), "2", 1, 1, 1, "体征与并发症观察应一致"},
            {"DIS_POSTOP_TEMP", "术后须观察体温", 7, "disease", disNeed(null, "temperature", "体温"), "3", 2, 1, 2, "术后须监测体温变化"},
            {"DIS_POSTOP_HT_BP", "高血压术后血压监测", 7, "disease", disNeed("I10", "bloodPressure", "血压"), "3", 2, 1, 2, "高血压术后须监测血压"},
            {"DIS_POSTOP_INFECT_CTRL", "感染防控措施记录", 7, "disease", disNeed(null, "antibiotic", "抗生素"), "2", 1, 1, 1, "术后感染防控须记录"},
            {"CALC_POSTOP_IO", "术后出入量平衡", 7, "calculation", calcIo("intake", "output", 1500), "3", 2, 1, 2, "术后出入量差值须在合理范围"},
            {"EVT_POSTOP_SEPSIS", "感染征象须及时处理", 7, "event", evtField("感染|脓毒", "sepsisNote"), "5", 2, 1, 2, "术后感染征象须记录处置"},
            {"EVT_POSTOP_REOPERATE", "再次手术须记录原因", 7, "event", evtField("再次手术|二次手术", "reopReason"), "5", 2, 1, 2, "再次手术须记录决策原因"},
            /* ---- 出院小结(record_type=8, 26) ---- */
            {"IV_OUT_ADMIT_COND", "入院情况必填", 8, "item_value", ivReq("admissionCondition"), "5", 2, 1, 2, "出院小结须概述入院情况"},
            {"IV_OUT_ADMIT_DIAG", "入院诊断必填", 8, "item_value", ivReq("admissionDiag"), "5", 2, 1, 2, "出院小结须记录入院诊断"},
            {"IV_OUT_PROC", "诊疗经过不少于100字", 8, "item_value", ivMin("treatProcess", 100), "5", 2, 1, 2, "诊疗经过应完整概述"},
            {"IV_OUT_DISCHARGE_DIAG", "出院诊断必填", 8, "item_value", ivReq("dischargeDiag"), "5", 2, 1, 2, "出院小结须记录出院诊断"},
            {"IV_OUT_DISCHARGE_STATE", "出院情况必填", 8, "item_value", ivReq("dischargeState"), "5", 2, 1, 2, "出院小结须描述出院情况"},
            {"IV_OUT_RESULT", "治疗结果必填", 8, "item_value", ivEnum("treatResult", "治愈", "好转", "未愈", "死亡", "自动出院", "转院", "其他"), "5", 2, 1, 2, "治疗结果应规范填写"},
            {"IV_OUT_ORDER", "出院医嘱必填", 8, "item_value", ivReq("dischargeOrder"), "5", 2, 1, 2, "出院医嘱不得为空"},
            {"IV_OUT_DRUG", "出院带药必填", 8, "item_value", ivReq("dischargeDrugs"), "3", 2, 1, 2, "出院带药须记录(不带药须注明'无')"},
            {"IV_OUT_REEXAM", "复诊安排必填", 8, "item_value", ivReq("reexamination"), "3", 1, 1, 1, "出院须安排复诊"},
            {"IV_OUT_FU", "电话随访安排", 8, "item_value", ivReq("followUp"), "2", 1, 1, 1, "出院须安排随访"},
            {"IV_OUT_MED_GUID", "用药指导必填", 8, "item_value", ivReq("medicationGuide"), "3", 1, 1, 1, "出院带药须有用药指导"},
            {"IV_OUT_ACTIVITY", "康复活动指导", 8, "item_value", ivReq("activityGuide"), "2", 1, 1, 1, "出院须有康复指导"},
            {"IV_OUT_SPECIALTY", "专科情况必填", 8, "item_value", ivReq("specialtyCond"), "2", 1, 1, 1, "出院小结须记录专科情况"},
            {"IV_OUT_NURSE", "护理指导", 8, "item_value", ivReq("nursingGuide"), "2", 1, 1, 1, "出院须有护理指导"},
            {"IV_OUT_WOUND", "伤口愈合情况", 8, "item_value", ivReq("woundHeal"), "2", 1, 1, 1, "手术患者须记录伤口愈合"},
            {"CMP_OUT_DIAG_IN_OUT", "出入诊断呼应(变更须说明)", 8, "item_compare", cmp("consistent", "admissionDiag", "dischargeDiag"), "3", 1, 1, 1, "诊断变更须在经过中说明"},
            {"CMP_OUT_STATE_ORDER", "出院情况与医嘱一致", 8, "item_compare", cmp("contains", "dischargeState", "dischargeOrder"), "2", 1, 1, 1, "医嘱应与出院情况匹配"},
            {"CMP_OUT_DRUG_ORDER", "带药须与医嘱一致", 8, "item_compare", cmp("consistent", "dischargeDrugs", "dischargeOrder"), "2", 1, 1, 1, "带药须在医嘱中体现"},
            {"CMP_OUT_STATE_RESULT", "出院情况与治疗结果一致", 8, "item_compare", cmp("consistent", "treatResult", "dischargeState"), "2", 1, 1, 1, "结果与情况应相互印证"},
            {"DIS_OUT_HT_EDU", "高血压出院须血压监测宣教", 8, "disease", disNeed("I10", "bloodPressure", "血压监测"), "3", 1, 1, 1, "高血压出院须宣教血压监测"},
            {"DIS_OUT_DM_EDU", "糖尿病出院须血糖与饮食宣教", 8, "disease", disNeed("E11", "bloodSugar", "血糖"), "3", 1, 1, 1, "糖尿病出院须宣教血糖管理"},
            {"DIS_OUT_MI_DRUG", "心梗出院须抗血小板用药", 8, "disease", disNeed("I21", "antiplatelet", "阿司匹林|氯吡格雷"), "5", 2, 1, 2, "心梗出院须带抗血小板药物"},
            {"CALC_OUT_LOS", "住院天数核对", 8, "calculation", calcLos("losDays", 1), "3", 2, 1, 2, "住院天数填写须与实际一致"},
            {"EVT_OUT_REFUSE", "自动出院须签字确认", 8, "event", evtField("自动出院|拒绝", "refuseSign"), "5", 2, 1, 2, "自动出院须患者或家属签字"},
            {"EVT_OUT_DEATH_EXPIRE", "死亡须有死亡记录", 8, "event", evtRecord("死亡", 9), "5", 2, 1, 2, "死亡病例须有死亡记录文书"},
            {"EVT_OUT_APPOINTMENT", "复诊预约", 8, "event", evtField(null, "appointment"), "2", 1, 1, 1, "出院须预约复诊"},
            /* ---- 死亡记录(record_type=9, 18) ---- */
            {"IV_DEATH_TIME", "死亡时间必填", 9, "item_value", ivReq("deathTime"), "5", 2, 1, 2, "死亡记录须明确死亡时间"},
            {"IV_DEATH_CAUSE", "死亡原因必填", 9, "item_value", ivReq("deathCause"), "10", 3, 1, 3, "死亡原因不得为空"},
            {"IV_DEATH_DIAG", "死亡诊断必填", 9, "item_value", ivReq("deathDiag"), "5", 2, 1, 2, "死亡记录须有死亡诊断"},
            {"IV_DEATH_PROCESS", "死亡前抢救经过必填", 9, "item_value", ivReq("rescueProcess"), "5", 2, 1, 2, "死亡前抢救经过须记录"},
            {"IV_DEATH_PARTICIP", "参加抢救人员必填", 9, "item_value", ivReq("participants"), "3", 1, 1, 1, "参加抢救人员须记录"},
            {"IV_DEATH_AUTOPSY", "尸检意见必填", 9, "item_value", ivReq("autopsy"), "3", 2, 1, 2, "尸检意见须明确(同意/不同意)"},
            {"IV_DEATH_PLACE", "死亡地点必填", 9, "item_value", ivReq("deathPlace"), "3", 1, 1, 1, "死亡地点须记录"},
            {"IV_DEATH_FAMILY", "家属知情记录", 9, "item_value", ivReq("familyInformed"), "3", 1, 1, 1, "家属知情情况须记录"},
            {"IV_DEATH_LEN", "死亡记录不少于100字", 9, "item_value", ivMin("content", 100), "3", 2, 1, 2, "死亡记录应完整"},
            {"IV_DEATH_RESUME", "死亡前病情摘要必填", 9, "item_value", ivReq("conditionSummary"), "3", 1, 1, 1, "死亡前病情须摘要"},
            {"CMP_DEATH_CAUSE_DIAG", "死亡原因与诊断一致", 9, "item_compare", cmp("consistent", "deathCause", "deathDiag"), "3", 1, 1, 1, "死亡原因应与诊断呼应"},
            {"CMP_DEATH_CAUSE_PROCESS", "死亡原因与抢救经过一致", 9, "item_compare", cmp("consistent", "deathCause", "rescueProcess"), "3", 1, 1, 1, "死因应与抢救经过印证"},
            {"DIS_DEATH_MI_ECG", "心梗死亡须结合心电图", 9, "disease", disNeed("I21", "ecg", "心电图"), "3", 1, 1, 1, "心梗死亡须结合心电图分析"},
            {"CALC_DEATH_LOS", "住院天数核对", 9, "calculation", calcLos("losDays", 1), "2", 1, 1, 1, "住院天数填写须与实际一致"},
            {"EVT_DEATH_DISCUSSION", "死亡病例须有讨论记录", 9, "event", evtRecord(null, 14), "3", 1, 1, 1, "死亡病例一周内须讨论"},
            {"EVT_DEATH_NOTIFY", "须通知家属并记录", 9, "event", evtField(null, "familyNotify"), "3", 1, 1, 1, "须履行告知义务并留痕"},
            {"EVT_DEATH_REPORT", "死亡须按规定上报", 9, "event", evtField(null, "deathReport"), "5", 2, 1, 2, "死亡病例须上报"},
            {"EVT_DEATH_CERTIFICATE", "死亡证明须记录", 9, "event", evtField(null, "deathCertificate"), "3", 1, 1, 1, "死亡医学证明须记录"},
            /* ---- 病案首页(record_type=10, 25, 归档级) ---- */
            {"IV_HP_LOS", "住院天数必填", 10, "item_value", ivReq("losDays"), "5", 2, 2, 2, "病案首页须填写住院天数"},
            {"IV_HP_COST", "费用信息必填", 10, "item_value", ivReq("totalCost"), "5", 2, 2, 2, "病案首页须填写总费用"},
            {"IV_HP_BLOOD", "血型必填", 10, "item_value", ivReq("bloodType"), "2", 1, 2, 1, "病案首页须填写血型"},
            {"IV_HP_ALLERGY", "过敏史必填", 10, "item_value", ivReq("allergy"), "3", 2, 2, 2, "病案首页须填写过敏史"},
            {"IV_HP_CODE_FMT", "诊断编码ICD-10格式", 10, "item_value", ivPat("diagCode", "^[A-Z]\\d{2}(\\.\\d{1,3})?$"), "5", 3, 2, 3, "诊断编码须符合ICD-10"},
            {"IV_HP_OP_FMT", "手术编码ICD-9格式", 10, "item_value", ivPat("opCode", "^\\d{2}(\\.\\d{1,2})?$"), "3", 2, 2, 2, "手术编码须符合ICD-9-CM-3"},
            {"IV_HP_INSU_TYPE", "医保类型必填", 10, "item_value", ivReq("insuType"), "2", 1, 2, 1, "病案首页须填写医保类型"},
            {"IV_HP_RESIDENCE", "户籍地址规范", 10, "item_value", ivReq("residence"), "2", 1, 2, 1, "病案首页须填写户籍地址"},
            {"IV_HP_OCCUPATION", "职业必填", 10, "item_value", ivReq("occupation"), "2", 1, 2, 1, "病案首页须填写职业"},
            {"IV_HP_MARRIAGE_HP", "婚姻状况必填", 10, "item_value", ivReq("marriage"), "2", 1, 2, 1, "病案首页须填写婚姻状况"},
            {"IV_HP_OP_NAME", "手术名称规范", 10, "item_value", ivReq("opName"), "3", 1, 2, 1, "手术名称须规范填写"},
            {"IV_HP_COST_PARTS", "费用分项齐全", 10, "item_value", ivReq("costParts"), "3", 1, 2, 1, "费用分项须完整填写"},
            {"IV_HP_MAIN_DIAG_NAME", "主诊断名称必填", 10, "item_value", ivReq("diagName"), "5", 2, 2, 2, "出院主诊断名称不得为空"},
            {"IV_HP_MAIN_DIAG_CODE", "主诊断编码必填", 10, "item_value", ivReq("diagCode"), "5", 2, 2, 2, "出院主诊断编码不得为空"},
            {"IV_HP_ADMIT_DATE", "入院日期必填", 10, "item_value", ivReq("admitDate"), "5", 2, 2, 2, "入院日期不得为空"},
            {"IV_HP_DISCHARGE_DATE", "出院日期必填", 10, "item_value", ivReq("dischargeDate"), "5", 2, 2, 2, "出院日期不得为空"},
            {"IV_HP_OP_SURGEON", "术者必填(有手术时)", 10, "item_value", ivReq("opSurgeon"), "3", 1, 2, 1, "手术术者须填写"},
            {"IV_HP_OP_DATE_HP", "手术日期必填(有手术时)", 10, "item_value", ivReq("opDate"), "3", 1, 2, 1, "手术日期须填写"},
            {"IV_HP_QC_DOCTOR", "质控医师必填", 10, "item_value", ivReq("qcDoctor"), "2", 1, 2, 1, "首页质控医师须填写"},
            {"CMP_HP_DIAG_IN_OUT", "出入诊断一致(变更须说明)", 10, "item_compare", cmp("consistent", "admitDiag", "dischargeDiag"), "3", 1, 2, 1, "诊断变更须有依据"},
            {"CMP_HP_COST_SUM", "费用分项与总额一致", 10, "calculation", calcGap("totalCost", "costSum", 1), "3", 2, 2, 2, "分项合计须与总额一致"},
            {"DIS_HP_MI_ECG", "心梗首页须有心电图", 10, "disease", disNeed("I21", "ecg", "心电图"), "3", 2, 2, 2, "心梗首页须有心电图结果"},
            {"DIS_HP_PNEU_XRAY", "肺炎首页须有影像", 10, "disease", disNeed("J18", "xray", "胸片|CT"), "3", 2, 2, 2, "肺炎首页须有影像结果"},
            {"CALC_HP_LOS_OK", "首页住院天数与实际一致", 10, "calculation", calcLos("losDays", 1), "3", 2, 2, 2, "住院天数须与实际一致"},
            {"EVT_HP_SUBMIT_QC", "首页提交须质控", 10, "event", evtField(null, "qcResult"), "3", 1, 2, 1, "首页提交前须完成质控"},
            /* ---- 其他类型(11-15, 24) ---- */
            {"IV_SHIFT_SUMMARY", "交班患者病情摘要必填", 11, "item_value", ivReq("handoverSummary"), "3", 2, 1, 2, "交班记录须有病情摘要"},
            {"IV_SHIFT_FOCUS", "接班须确认重点患者", 11, "item_value", ivReq("focusPatients"), "3", 2, 1, 2, "接班须确认重点患者"},
            {"IV_SHIFT_PENDING", "待办事项交接必填", 11, "item_value", ivReq("pendingItems"), "3", 1, 1, 1, "待办事项须交接"},
            {"IV_SHIFT_SIGN", "交接双方签名确认", 11, "item_value", ivReq("signConfirm"), "3", 2, 1, 2, "交接双方须签名"},
            {"IV_SHIFT_VITAL", "重点患者生命体征必填", 11, "item_value", ivReq("vitalSigns"), "2", 1, 1, 1, "重点患者须记录生命体征"},
            {"IV_TRANSFER_REASON", "转科原因必填", 12, "item_value", ivReq("transferReason"), "5", 2, 1, 2, "转科记录须说明转科原因"},
            {"IV_TRANSFER_SUMMARY", "转出前病情摘要必填", 12, "item_value", ivReq("summary"), "3", 2, 1, 2, "转科须有病情摘要"},
            {"IV_TRANSFER_RECEIVE", "转入接收记录必填", 12, "item_value", ivReq("receiveNote"), "3", 2, 1, 2, "转入须有接收记录"},
            {"IV_TRANSFER_MED", "转科用药交接必填", 12, "item_value", ivReq("medHandover"), "3", 1, 1, 1, "转科须交接在用药品"},
            {"CMP_TRANSFER_REASON_DIAG", "转科原因与诊断呼应", 12, "item_compare", cmp("contains", "diagnosis", "transferReason"), "2", 1, 1, 1, "转科原因应与诊断相关"},
            {"IV_CONSENT_ITEM", "同意书须列明项目名称", 13, "item_value", ivReq("itemName"), "5", 2, 1, 2, "知情同意须列明诊疗项目"},
            {"IV_CONSENT_RISK", "风险告知完整", 13, "item_value", ivReq("riskDesc"), "5", 2, 1, 2, "风险告知须完整"},
            {"IV_CONSENT_ALT", "替代方案告知", 13, "item_value", ivReq("alternative"), "3", 1, 1, 1, "须告知替代诊疗方案"},
            {"IV_CONSENT_SIGN", "患者或家属签字必填", 13, "item_value", ivReq("patientSign"), "5", 2, 1, 2, "知情同意须签字确认"},
            {"IV_DISCUSS_TOPIC", "讨论主题必填", 14, "item_value", ivReq("topic"), "5", 2, 1, 2, "讨论记录须明确主题"},
            {"IV_DISCUSS_OPINION", "参加人员意见必填", 14, "item_value", ivReq("opinions"), "5", 2, 1, 2, "讨论须记录参加人员意见"},
            {"IV_DISCUSS_CONCLUSION", "讨论结论必填", 14, "item_value", ivReq("conclusion"), "5", 2, 1, 2, "讨论须形成结论"},
            {"IV_DISCUSS_HOST", "主持人必填", 14, "item_value", ivReq("host"), "3", 1, 1, 1, "讨论须记录主持人"},
            {"IV_DISCUSS_DEATH_ALL", "死亡讨论须全员发言", 14, "item_value", ivReq("allSpeak"), "2", 1, 1, 1, "死亡讨论应记录全员意见"},
            {"IV_CONSULT_REASON", "会诊目的必填", 15, "item_value", ivReq("consultReason"), "5", 2, 1, 2, "会诊记录须说明会诊目的"},
            {"IV_CONSULT_DEPT", "会诊科室/医师必填", 15, "item_value", ivReq("consultDept"), "3", 2, 1, 2, "会诊须记录科室与医师"},
            {"IV_CONSULT_OPINION", "会诊意见必填", 15, "item_value", ivReq("consultOpinion"), "5", 2, 1, 2, "会诊须形成会诊意见"},
            {"IV_CONSULT_TIME", "会诊完成时间必填", 15, "item_value", ivReq("completionTime"), "3", 1, 1, 1, "会诊须记录完成时间"},
            {"IV_CONSULT_URGENT", "急会诊须标注时限", 15, "item_value", ivReq("urgentFlag"), "2", 1, 1, 1, "急会诊须标注完成时限"},
            /* ---- 病种专项(全类型, 93): ICD-10 前缀触发 ---- */
            {"DIS_I10_BP", "高血压须监测血压", null, "disease", disNeed("I10", "bloodPressure", "血压"), "3", 2, 0, 2, "高血压患者须记录血压监测"},
            {"DIS_I10_HEART", "高血压须查心电图", null, "disease", disNeed("I10", "ecg", "心电图"), "3", 2, 0, 2, "高血压须检查心电图"},
            {"DIS_I10_RENAL", "高血压须查肾功能", null, "disease", disNeed("I10", "renal", "肾功能|肌酐"), "3", 2, 0, 2, "高血压须检查肾功能"},
            {"DIS_I10_EYE", "高血压须查眼底", null, "disease", disNeed("I10", "fundus", "眼底"), "2", 1, 0, 1, "高血压须检查眼底"},
            {"DIS_I10_LIPID", "高血压须查血脂", null, "disease", disNeed("I10", "lipid", "血脂"), "2", 1, 0, 1, "高血压须检查血脂"},
            {"DIS_I10_DRUG", "高血压须用降压药", null, "disease", disNeed("I10", "antihypertensive", "降压药|硝苯地平|氨氯地平|缬沙坦"), "3", 2, 0, 2, "高血压须有降压治疗方案"},
            {"DIS_I10_EDU", "高血压须生活方式宣教", null, "disease", disNeed("I10", "healthEdu", "戒烟|限盐|运动"), "2", 1, 0, 1, "高血压须生活方式干预宣教"},
            {"DIS_I10_COMP", "高血压须评估靶器官损害", null, "disease", disNeed("I10", "targetOrgan", "靶器官|心脏|肾脏|眼底"), "2", 1, 0, 1, "高血压须评估靶器官损害"},
            {"DIS_I10_FOLLOW", "高血压须制定随访计划", null, "disease", disNeed("I10", "followUp", "随访"), "2", 1, 0, 1, "高血压须制定随访计划"},
            {"DIS_I10_SODIUM", "高血压须限盐指导", null, "disease", disNeed("I10", "saltLimit", "限盐"), "1", 1, 0, 1, "高血压须限盐指导"},
            {"DIS_E11_FBS", "糖尿病须查空腹血糖", null, "disease", disNeed("E11", "fastingGlucose", "空腹血糖"), "3", 2, 0, 2, "糖尿病须检查空腹血糖"},
            {"DIS_E11_HBA1C", "糖尿病须查糖化血红蛋白", null, "disease", disNeed("E11", "hba1c", "糖化血红蛋白"), "5", 2, 0, 2, "糖尿病须检查糖化血红蛋白"},
            {"DIS_E11_EYE", "糖尿病须查眼底", null, "disease", disNeed("E11", "fundus", "眼底"), "3", 2, 0, 2, "糖尿病须检查眼底"},
            {"DIS_E11_FOOT", "糖尿病须查足部", null, "disease", disNeed("E11", "foot", "足部"), "2", 1, 0, 1, "糖尿病须检查足部"},
            {"DIS_E11_RENAL", "糖尿病须查肾功能(尿蛋白)", null, "disease", disNeed("E11", "renal", "尿蛋白|肾功能"), "3", 2, 0, 2, "糖尿病须筛查糖尿病肾病"},
            {"DIS_E11_DRUG", "糖尿病须降糖方案", null, "disease", disNeed("E11", "hypoglycemic", "胰岛素|二甲双胍|降糖"), "3", 2, 0, 2, "糖尿病须有降糖治疗方案"},
            {"DIS_E11_DIET", "糖尿病须饮食控制医嘱", null, "disease", disNeed("E11", "diet", "饮食控制|饮食"), "2", 1, 0, 1, "糖尿病须饮食控制医嘱"},
            {"DIS_E11_EDU", "糖尿病须低血糖宣教", null, "disease", disNeed("E11", "education", "低血糖"), "2", 1, 0, 1, "糖尿病须低血糖防治宣教"},
            {"DIS_E11_COMP", "糖尿病须评估并发症", null, "disease", disNeed("E11", "complication", "并发症|视网膜|肾病|神经病变"), "2", 1, 0, 1, "糖尿病须评估慢性并发症"},
            {"DIS_E11_SMBG", "糖尿病须自我血糖监测宣教", null, "disease", disNeed("E11", "smbg", "自我监测"), "1", 1, 0, 1, "糖尿病须自我血糖监测宣教"},
            {"DIS_J18_XRAY", "肺炎须有影像学检查", null, "disease", disNeed("J18", "xray", "胸片|CT"), "3", 2, 0, 2, "肺炎须影像学检查"},
            {"DIS_J18_WBC", "肺炎须查血常规", null, "disease", disNeed("J18", "bloodRoutine", "血常规|白细胞"), "3", 2, 0, 2, "肺炎须检查血常规"},
            {"DIS_J18_SPUTUM", "肺炎须查病原学", null, "disease", disNeed("J18", "sputum", "痰培养|病原"), "2", 1, 0, 1, "肺炎须病原学检查"},
            {"DIS_J18_AB", "肺炎须抗感染治疗", null, "disease", disNeed("J18", "antibiotic", "抗生素|抗感染"), "3", 2, 0, 2, "肺炎须抗感染治疗"},
            {"DIS_J18_TEMP", "肺炎须监测体温", null, "disease", disNeed("J18", "temperature", "体温"), "3", 2, 0, 2, "肺炎须监测体温"},
            {"DIS_J18_SPO2", "肺炎须监测血氧", null, "disease", disNeed("J18", "spo2", "血氧"), "2", 1, 0, 1, "肺炎须监测血氧饱和度"},
            {"DIS_I21_ECG", "心梗须查心电图", null, "disease", disNeed("I21", "ecg", "心电图"), "5", 2, 0, 2, "心梗须检查心电图"},
            {"DIS_I21_TRO", "心梗须查肌钙蛋白", null, "disease", disNeed("I21", "troponin", "肌钙蛋白"), "5", 2, 0, 2, "心梗须检查肌钙蛋白"},
            {"DIS_I21_CK", "心梗须查心肌酶", null, "disease", disNeed("I21", "ck", "心肌酶|CK-MB"), "3", 2, 0, 2, "心梗须检查心肌酶谱"},
            {"DIS_I21_REPERF", "心梗须再灌注治疗", null, "disease", disNeed("I21", "reperfusion", "溶栓|介入|PCI|支架"), "5", 2, 0, 2, "心梗须再灌注治疗"},
            {"DIS_I21_ASP", "心梗须抗血小板治疗", null, "disease", disNeed("I21", "antiplatelet", "阿司匹林|氯吡格雷|替格瑞洛"), "5", 2, 0, 2, "心梗须抗血小板治疗"},
            {"DIS_I21_STATIN", "心梗须他汀治疗", null, "disease", disNeed("I21", "statin", "他汀|阿托伐他汀|瑞舒伐他汀"), "3", 2, 0, 2, "心梗须他汀类治疗"},
            {"DIS_I21_ECHO", "心梗须查心脏超声", null, "disease", disNeed("I21", "echo", "心脏彩超|超声心动"), "2", 1, 0, 1, "心梗须检查心脏超声"},
            {"DIS_I21_MONITOR", "心梗须心电监护", null, "disease", disNeed("I21", "monitor", "监护"), "3", 2, 0, 2, "心梗须心电监护"},
            {"DIS_I63_CT", "脑梗须查头颅CT/MRI", null, "disease", disNeed("I63", "ct", "CT|磁共振|MRI"), "5", 2, 0, 2, "脑梗须头颅影像检查"},
            {"DIS_I63_NIHSS", "脑梗须NIHSS评分", null, "disease", disNeed("I63", "nihss", "NIHSS"), "2", 1, 0, 1, "脑梗须NIHSS神经功能评分"},
            {"DIS_I63_SWALLOW", "脑梗须吞咽功能评估", null, "disease", disNeed("I63", "swallow", "吞咽"), "2", 1, 0, 1, "脑梗须吞咽功能筛查"},
            {"DIS_I63_ANTITHROM", "脑梗须抗栓治疗", null, "disease", disNeed("I63", "antithrombotic", "阿司匹林|氯吡格雷|抗凝"), "3", 2, 0, 2, "脑梗须抗栓治疗"},
            {"DIS_I63_LIPID", "脑梗须查血脂", null, "disease", disNeed("I63", "lipid", "血脂"), "2", 1, 0, 1, "脑梗须检查血脂"},
            {"DIS_I63_REHAB", "脑梗须早期康复评估", null, "disease", disNeed("I63", "rehab", "康复"), "2", 1, 0, 1, "脑梗须早期康复评估"},
            {"DIS_C34_PATHO", "肺癌须有病理诊断", null, "disease", disNeed("C34", "pathology", "病理"), "5", 2, 0, 2, "肺癌须病理确诊"},
            {"DIS_C34_CT", "肺癌须影像学检查", null, "disease", disNeed("C34", "ct", "CT"), "3", 2, 0, 2, "肺癌须影像学检查"},
            {"DIS_C34_STAGING", "肺癌须全身分期评估", null, "disease", disNeed("C34", "pet", "PET|骨扫描|全身"), "2", 1, 0, 1, "肺癌须全身分期评估"},
            {"DIS_C34_LUNG_FUNC", "肺癌术前须查肺功能", null, "disease", disNeed("C34", "lungFunc", "肺功能"), "2", 1, 0, 1, "肺癌术前须评估肺功能"},
            {"DIS_C34_TNM", "肺癌须TNM分期", null, "disease", disNeed("C34", "tnm", "分期"), "2", 1, 0, 1, "肺癌须TNM分期"},
            {"DIS_C34_MDT", "肺癌须多学科讨论", null, "disease", disNeed("C34", "mdt", "多学科|MDT"), "2", 1, 0, 1, "肺癌治疗须多学科讨论"},
            {"DIS_I50_BNP", "心衰须查BNP/NT-proBNP", null, "disease", disNeed("I50", "bnp", "BNP|利钠肽"), "3", 2, 0, 2, "心衰须检查BNP/NT-proBNP"},
            {"DIS_I50_ECHO", "心衰须查心脏超声", null, "disease", disNeed("I50", "echo", "超声"), "3", 2, 0, 2, "心衰须检查心脏超声"},
            {"DIS_I50_ECG", "心衰须查心电图", null, "disease", disNeed("I50", "ecg", "心电图"), "3", 2, 0, 2, "心衰须检查心电图"},
            {"DIS_I50_DIURETIC", "心衰须利尿治疗", null, "disease", disNeed("I50", "diuretic", "呋塞米|利尿"), "3", 2, 0, 2, "心衰须利尿治疗"},
            {"DIS_I50_WEIGHT", "心衰须监测体重", null, "disease", disNeed("I50", "weight", "体重"), "2", 1, 0, 1, "心衰须每日监测体重"},
            {"DIS_K35_WBC", "阑尾炎须查血常规", null, "disease", disNeed("K35", "bloodRoutine", "血常规|白细胞"), "3", 2, 0, 2, "阑尾炎须检查血常规"},
            {"DIS_K35_US", "阑尾炎须查超声/CT", null, "disease", disNeed("K35", "ultrasound", "超声|CT"), "3", 2, 0, 2, "阑尾炎须影像学检查"},
            {"DIS_K35_TREAT", "阑尾炎须手术或抗感染", null, "disease", disNeed("K35", "treatment", "阑尾切除|抗生素"), "3", 2, 0, 2, "急性阑尾炎须手术或抗感染治疗"},
            {"DIS_K35_PATHO", "阑尾标本须送病理", null, "disease", disNeed("K35", "pathology", "病理"), "2", 1, 0, 1, "阑尾切除标本须送病理"},
            {"DIS_K29_ENDO", "胃病须胃镜检查", null, "disease", disNeed("K29", "endoscopy", "胃镜"), "3", 2, 0, 2, "胃病须胃镜检查"},
            {"DIS_K29_HP", "胃病须查幽门螺杆菌", null, "disease", disNeed("K29", "hp", "幽门螺杆菌|Hp"), "2", 1, 0, 1, "胃病须幽门螺杆菌检测"},
            {"DIS_K29_PPI", "胃病须抑酸治疗", null, "disease", disNeed("K29", "ppi", "奥美拉唑|泮托拉唑|质子泵"), "3", 2, 0, 2, "胃病须抑酸治疗"},
            {"DIS_K29_BLOOD", "胃病须查血常规", null, "disease", disNeed("K29", "bloodRoutine", "血常规"), "2", 1, 0, 1, "胃病须筛查贫血"},
            {"DIS_N20_US", "结石须查超声/CT", null, "disease", disNeed("N20", "ultrasound", "超声|CT"), "3", 2, 0, 2, "尿路结石须影像学检查"},
            {"DIS_N20_URINE", "结石须查尿常规", null, "disease", disNeed("N20", "urineRoutine", "尿常规"), "3", 2, 0, 2, "尿路结石须检查尿常规"},
            {"DIS_N20_RENAL", "结石须查肾功能", null, "disease", disNeed("N20", "renal", "肾功能"), "3", 2, 0, 2, "尿路结石须检查肾功能"},
            {"DIS_N20_PAIN", "结石须镇痛解痉", null, "disease", disNeed("N20", "analgesic", "曲马多|止痛|解痉"), "2", 1, 0, 1, "肾绞痛须镇痛解痉治疗"},
            {"DIS_O80_LABOR", "分娩须有产程记录", null, "disease", disNeed("O80", "labor", "产程"), "3", 2, 0, 2, "分娩须产程记录"},
            {"DIS_O80_APGAR", "分娩须Apgar评分", null, "disease", disNeed("O80", "apgar", "Apgar"), "3", 2, 0, 2, "新生儿须Apgar评分"},
            {"DIS_O80_NEWBORN", "分娩须记录新生儿情况", null, "disease", disNeed("O80", "newborn", "新生儿"), "3", 2, 0, 2, "分娩须记录新生儿情况"},
            {"DIS_O80_PLACENTA", "分娩须检查胎盘", null, "disease", disNeed("O80", "placenta", "胎盘"), "3", 2, 0, 2, "分娩须检查胎盘胎膜"},
            {"DIS_O80_BREASTFEED", "分娩须母乳喂养宣教", null, "disease", disNeed("O80", "breastfeeding", "母乳"), "2", 1, 0, 1, "分娩须母乳喂养宣教"},
            {"DIS_E78_LIPID", "高血脂须有血脂结果", null, "disease", disNeed("E78", "lipid", "血脂"), "3", 2, 0, 2, "高血脂须有血脂检验结果"},
            {"DIS_E78_LIFESTYLE", "高血脂须生活方式干预", null, "disease", disNeed("E78", "lifestyle", "运动|饮食"), "2", 1, 0, 1, "高血脂须生活方式干预"},
            {"DIS_E78_STATIN", "高危须他汀治疗", null, "disease", disNeed("E78", "statin", "他汀"), "2", 1, 0, 1, "高危高血脂须他汀治疗"},
            {"DIS_J44_LUNG_FUNC", "COPD须查肺功能", null, "disease", disNeed("J44", "lungFunc", "肺功能"), "3", 2, 0, 2, "COPD须肺功能检查"},
            {"DIS_J44_XRAY", "COPD须查胸部影像", null, "disease", disNeed("J44", "xray", "胸片|CT"), "3", 2, 0, 2, "COPD须胸部影像检查"},
            {"DIS_J44_O2", "COPD须氧疗评估", null, "disease", disNeed("J44", "oxygen", "吸氧|氧疗"), "3", 2, 0, 2, "COPD须氧疗评估"},
            {"DIS_J44_SMOKE", "COPD须戒烟宣教", null, "disease", disNeed("J44", "smoking", "戒烟"), "2", 1, 0, 1, "COPD须戒烟宣教"},
            {"DIS_N18_EGFR", "CKD须评估eGFR", null, "disease", disNeed("N18", "egfr", "eGFR|肾小球滤过"), "3", 2, 0, 2, "慢性肾病须评估eGFR"},
            {"DIS_N18_URINE", "CKD须查尿蛋白", null, "disease", disNeed("N18", "urineProtein", "尿蛋白"), "3", 2, 0, 2, "慢性肾病须检查尿蛋白"},
            {"DIS_N18_HEMO", "CKD须查血红蛋白", null, "disease", disNeed("N18", "hemoglobin", "血红蛋白"), "3", 2, 0, 2, "慢性肾病须筛查肾性贫血"},
            {"DIS_N18_PHOS", "CKD须查钙磷代谢", null, "disease", disNeed("N18", "calcium", "钙磷"), "2", 1, 0, 1, "慢性肾病须检查钙磷代谢"},
            {"DIS_J45_LUNG_FUNC", "哮喘须查肺功能", null, "disease", disNeed("J45", "lungFunc", "肺功能|峰流速"), "3", 2, 0, 2, "哮喘须肺功能检查"},
            {"DIS_J45_INHALED", "哮喘须吸入治疗", null, "disease", disNeed("J45", "inhaled", "吸入|沙丁胺醇|布地奈德"), "3", 2, 0, 2, "哮喘须吸入药物治疗"},
            {"DIS_K80_US", "胆石症须查超声", null, "disease", disNeed("K80", "ultrasound", "超声"), "3", 2, 0, 2, "胆石症须超声检查"},
            {"DIS_K80_LFT", "胆石症须查肝功能", null, "disease", disNeed("K80", "lft", "肝功能"), "3", 2, 0, 2, "胆石症须肝功能检查"},
            {"DIS_A16_SPUTUM", "结核须查痰抗酸杆菌", null, "disease", disNeed("A16", "sputum", "痰抗酸|抗酸杆菌"), "3", 2, 0, 2, "肺结核须痰抗酸杆菌检查"},
            {"DIS_A16_ISOLATION", "结核须隔离标识", null, "disease", disNeed("A16", "isolation", "隔离"), "3", 2, 0, 2, "肺结核须隔离标识"},
            {"DIS_B15_LFT", "肝炎须查肝功能", null, "disease", disNeed("B15", "lft", "肝功能"), "3", 2, 0, 2, "肝炎须肝功能检查"},
            {"DIS_B15_COAG", "肝炎须查凝血功能", null, "disease", disNeed("B15", "coagulation", "凝血"), "3", 2, 0, 2, "肝炎须凝血功能检查"},
            {"DIS_D50_IRON", "贫血须查铁代谢", null, "disease", disNeed("D50", "iron", "铁蛋白|血清铁"), "2", 1, 0, 1, "贫血须铁代谢检查"},
            {"DIS_D50_GI", "贫血须查消化道(病因筛查)", null, "disease", disNeed("D50", "gastroscopy", "胃镜|肠镜|消化道"), "2", 1, 0, 1, "贫血须病因学筛查"},
            {"DIS_I25_ECG", "冠心病须查心电图", null, "disease", disNeed("I25", "ecg", "心电图"), "3", 2, 0, 2, "冠心病须心电图检查"},
            {"DIS_I25_MED", "冠心病须二级预防用药", null, "disease", disNeed("I25", "prevention", "阿司匹林|他汀|β受体"), "3", 2, 0, 2, "冠心病须二级预防用药"},
            {"DIS_N39_CULTURE", "尿感须查尿培养", null, "disease", disNeed("N39", "urineCulture", "尿培养"), "2", 1, 0, 1, "尿路感染须尿培养检查"},
            {"DIS_N39_MED", "尿感须抗感染治疗", null, "disease", disNeed("N39", "antibiotic", "抗生素|抗感染"), "3", 2, 0, 2, "尿路感染须抗感染治疗"}
    };

    /**
     * 内涵规则种子播种(P5a-3, 400+条): 按 rule_code 全量判存幂等(含墓碑, 与 seedScoreStandards 同口径),
     * 已有编码跳过保持手工修改, 新增编码补种; rule_type=5(内涵)与旧引擎(rule_type 1-4)互不干扰。
     * 由 DemoDataInitializer 按租户循环调用(TenantContext 已设为该租户), 机构未建时跳过下次启动补种。
     */
    public void seedContentRules(Long tenantId) {
        Long orgId = resolveLeadOrgId(tenantId);
        if (orgId == null) {
            return; // 机构未建(RBAC初始化未执行), 下次启动补种
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT rule_code AS rc FROM his_emr_quality_rule WHERE tenant_id = ?", tenantId);
        Set<String> existCodes = new HashSet<>();
        for (Map<String, Object> row : rows) {
            existCodes.add(text(row.get("rc")));
        }
        int added = insertContentRules(CONTENT_RULE_SEEDS_A, existCodes, orgId);
        added += insertContentRules(CONTENT_RULE_SEEDS_B, existCodes, orgId);
        if (added > 0) {
            log.info("租户[{}] 内涵质控规则种子: 补种 {} 条(五大类型矩阵, 已有保持不动)", tenantId, added);
        }
    }

    /** 批量插入内涵规则(逐条判存, 单条失败不中断) */
    private int insertContentRules(Object[][] seeds, Set<String> existCodes, Long orgId) {
        int added = 0;
        for (Object[] s : seeds) {
            String code = (String) s[0];
            if (existCodes.contains(code)) {
                continue;
            }
            try {
                HisEmrQualityRule r = new HisEmrQualityRule();
                r.setOrgId(orgId);
                r.setRuleCode(code);
                r.setRuleName((String) s[1]);
                r.setRecordType((Integer) s[2]);
                r.setRuleType(5);
                r.setRuleCategory((String) s[3]);
                r.setRuleConfig(((JSONObject) s[4]).toJSONString());
                r.setDeductScore(new BigDecimal((String) s[5]));
                r.setSeverity((Integer) s[6]);
                r.setQcStage((Integer) s[7]);
                r.setControlLevel((Integer) s[8]);
                r.setDescription((String) s[9]);
                r.setStatus(1);
                ruleMapper.insert(r);
                existCodes.add(code);
                added++;
            } catch (Exception e) {
                log.warn("内涵规则[{}]播种失败跳过: {}", code, e.getMessage());
            }
        }
        return added;
    }
}
