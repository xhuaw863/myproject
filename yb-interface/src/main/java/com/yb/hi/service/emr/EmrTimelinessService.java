package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.emr.HisEmrQcDefect;
import com.yb.hi.entity.inpatient.HisEmrQualityRule;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.emr.HisEmrQcDefectMapper;
import com.yb.hi.mapper.inpatient.HisEmrQualityRuleMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病历时效质控引擎(病历P5a-2): 23项时限规则(rule_type=2, qc_stage=0通用)驱动的书写时限检查/预警/缺陷落库。
 *
 * 规则模型: rule_config = {"deadlineHours": N, "anchorEvent": "锚点事件", "direction": "after|before",
 * "condition": "admitSource=2"可选, "nursingLevel": N可选(日常病程按护理等级)}。
 * 锚点解析优先级: structureData 业务时间字段 → 就诊表时间(admitDate/dischargeDate) → 同就诊最近病程 → recordTime。
 *
 * 判定语义: 已完成病历按完成时刻(recordTime)与截止比较; 未完成(草稿)按当前时刻与截止比较 —
 * 已过 deadline=overdue, 距 deadline≤2h=warning, 否则=normal。
 * 运行机制: 单病历检查(checkTimeliness)/病区批量(batchCheckByWard)/超时与临近清单(JdbcTemplate 联查);
 * 每 30 分钟 autoNotify 定时扫描在院未签名病历, 2h 预警/1h 加急/超时推送 SSE 并自动落缺陷(去重);
 * @EventListener 监听病历事件自动回填 his_inp_medical_record.deadline_time(仅空值回填, 幂等)。
 * 定时任务无租户上下文, 遍历租户显式设置 TenantContext 后逐租户扫描, 单患者失败不阻断批量。
 */
@Slf4j
@Order(9)
@Service
public class EmrTimelinessService implements ApplicationRunner {

    /** 临近超时预警窗口(小时): 距截止 ≤2h 判定 warning */
    private static final int WARN_HOURS = 2;
    /** 加急预警窗口(小时): 距截止 ≤1h 推送加急 */
    private static final int URGENT_HOURS = 1;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** record_type 扩展标签(1-15 与 InpMedRecordService 口径一致, 16-19 为时效质控预留类型) */
    private static final Map<Integer, String> TYPE_LABELS = buildTypeLabels();

    private static Map<Integer, String> buildTypeLabels() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(1, "入院记录");
        m.put(2, "首次病程记录");
        m.put(3, "日常病程记录");
        m.put(4, "查房记录");
        m.put(5, "术前小结");
        m.put(6, "手术记录");
        m.put(7, "术后病程记录");
        m.put(8, "出院小结");
        m.put(9, "死亡记录");
        m.put(10, "病案首页");
        m.put(11, "交接班记录");
        m.put(12, "转科记录");
        m.put(13, "知情同意书");
        m.put(14, "讨论记录");
        m.put(15, "会诊记录");
        m.put(16, "麻醉记录");
        m.put(17, "死亡病例讨论");
        m.put(18, "抢救记录");
        m.put(19, "病危通知书");
        return Collections.unmodifiableMap(m);
    }

    /**
     * 23项时效规则种子: {规则编码, 规则名称, record_type, rule_config, 扣分, severity(1警告 2扣分 3一票否决), 说明}。
     * rule_config 约定: deadlineHours 时限小时数(0=即刻), anchorEvent 锚点, direction before=锚点前完成,
     * condition 准入条件("admitSource=2"/"admitSource!=2"), nursingLevel 适用护理等级(1特级 2一级 3二级 4三级)。
     */
    private static final Object[][] TIMELINESS_SEEDS = {
            {"TL_ADMI_24H", "入院记录24小时内完成", 1, cfg(24, "admission", "after", "admitSource!=2"), "20", 2,
                    "普通入院患者入院记录须在入院后24小时内完成"},
            {"TL_ADMI_8H_ER", "入院记录8小时内完成(急诊)", 1, cfg(8, "admission", "after", "admitSource=2"), "20", 2,
                    "急诊入院患者入院记录须在入院后8小时内完成"},
            {"TL_FIRST_8H", "首次病程8小时内完成", 2, cfg(8, "admission", "after", null), "20", 2,
                    "首次病程记录须在患者入院后8小时内完成"},
            {"TL_ROUND_48H", "上级医师查房48小时内完成", 4, cfg(48, "admission", "after", null), "50", 3,
                    "患者入院48小时内须完成首次上级医师查房, 超时一票否决"},
            {"TL_DAILY_LV4", "特级护理日常病程每日记录", 3, cfg(24, "lastRecord", "after", null, 1), "10", 2,
                    "特级护理患者日常病程记录须每日1次(间隔不超过24小时)"},
            {"TL_DAILY_LV1", "一级护理日常病程间隔不超过1天", 3, cfg(24, "lastRecord", "after", null, 2), "10", 2,
                    "一级护理患者日常病程记录间隔不得超过1天"},
            {"TL_DAILY_LV2", "二级护理日常病程间隔不超过3天", 3, cfg(72, "lastRecord", "after", null, 3), "10", 2,
                    "二级护理患者日常病程记录间隔不得超过3天"},
            {"TL_DAILY_LV3", "三级护理日常病程间隔不超过5天", 3, cfg(120, "lastRecord", "after", null, 4), "10", 2,
                    "三级护理患者日常病程记录间隔不得超过5天"},
            {"TL_PREOP_DISC", "术前讨论术前1工作日完成", 14, cfg(24, "operation", "before", null), "20", 2,
                    "术前讨论须在手术开始前1个工作日内完成"},
            {"TL_PREOP_SUM", "术前小结术前完成", 5, cfg(0, "operation", "before", null), "20", 2,
                    "术前小结须在手术开始前完成"},
            {"TL_SURGERY_24H", "手术记录术后24小时内完成", 6, cfg(24, "operation", "after", null), "20", 2,
                    "手术记录须在手术结束后24小时内完成"},
            {"TL_POSTOP_FIRST", "术后首次病程即刻完成", 7, cfg(0, "operation", "after", null), "10", 2,
                    "术后首次病程记录须在术后即刻完成"},
            {"TL_ANESTH_24H", "麻醉记录术后24小时内完成", 16, cfg(24, "operation", "after", null), "20", 2,
                    "麻醉记录须在手术结束后24小时内完成"},
            {"TL_DEATH_6H", "死亡记录6小时内完成", 9, cfg(6, "death", "after", null), "20", 2,
                    "死亡记录须在患者死亡后6小时内完成"},
            {"TL_DEATH_DISC_7D", "死亡病例讨论7天内完成", 17, cfg(168, "death", "after", null), "20", 2,
                    "死亡病例讨论须在患者死亡后7天内完成"},
            {"TL_DISCHARGE_3D", "出院小结3日内完成", 8, cfg(72, "discharge", "after", null), "20", 2,
                    "出院小结须在患者出院后3日内完成"},
            {"TL_RESCUE_6H", "抢救记录抢救后6小时内补记", 18, cfg(6, "rescue", "after", null), "20", 2,
                    "抢救记录须在抢救结束后6小时内据实补记"},
            {"TL_CONSULT_24H", "会诊记录24小时内完成", 15, cfg(24, "consultation", "after", null), "10", 2,
                    "会诊记录须在会诊结束后24小时内完成"},
            {"TL_TRANSFER_DAY", "转科记录转科当日完成", 12, cfg(24, "transfer", "after", null), "10", 2,
                    "转科记录须在转出当日完成"},
            {"TL_CRITICAL_NOW", "病危通知书即刻下达", 19, cfg(0, "critical", "after", null), "20", 2,
                    "病危通知书须在下达医嘱后即刻完成"},
            {"TL_CONSENT_PREOP", "知情同意书术前签署", 13, cfg(0, "operation", "before", null), "20", 2,
                    "知情同意书须在手术开始前完成签署"},
            {"TL_HOME_3D", "病案首页出院后3日内完成", 10, cfg(72, "discharge", "after", null), "20", 2,
                    "病案首页须在患者出院后3日内完成"},
            {"TL_HANDOVER_8H", "交接班记录当班8小时内完成", 11, cfg(8, "lastRecord", "after", null), "5", 2,
                    "交接班记录须在当班8小时内完成"}
    };

    /** 时效规则查询条件(定时任务联查用): 在院 + 草稿 + 有截止时间 */
    private static final String SCAN_SQL_BASE =
            "SELECT r.id AS recordId, r.record_type AS recordType, r.title, r.record_time AS recordTime, "
            + "r.deadline_time AS deadlineTime, r.doctor_id AS doctorId, r.create_time AS createTime, "
            + "v.id AS visitId, v.patient_id AS patientId, v.inp_no AS inpNo, v.ward_id AS wardId, "
            + "v.dept_id AS deptId, v.doctor_id AS visitDoctorId, p.name AS patientName "
            + "FROM his_inp_medical_record r "
            + "JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
            + "LEFT JOIN his_patient p ON v.patient_id = p.id "
            + "WHERE r.deleted = 0 AND r.status = 1 AND v.visit_status IN (2, 3) ";
    /** 超时清单排序: 截止时间升序(超时最久的排最前) */
    private static final String ORDER_OVERDUE = " ORDER BY r.deadline_time ASC";

    private final HisEmrQualityRuleMapper ruleMapper;
    private final HisInpMedicalRecordMapper recordMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisPatientMapper patientMapper;
    private final HisEmrQcDefectMapper defectMapper;
    private final OrgAccessGuard guard;
    private final SysTenantService tenantService;
    private final EmrEventPublisher emrEventPublisher;
    private final JdbcTemplate jdbcTemplate;

    public EmrTimelinessService(HisEmrQualityRuleMapper ruleMapper, HisInpMedicalRecordMapper recordMapper,
                                HisInpVisitMapper visitMapper, HisPatientMapper patientMapper,
                                HisEmrQcDefectMapper defectMapper, OrgAccessGuard guard,
                                SysTenantService tenantService, EmrEventPublisher emrEventPublisher,
                                JdbcTemplate jdbcTemplate) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.visitMapper = visitMapper;
        this.patientMapper = patientMapper;
        this.defectMapper = defectMapper;
        this.guard = guard;
        this.tenantService = tenantService;
        this.emrEventPublisher = emrEventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 启动播种 ================= */

    @Override
    public void run(ApplicationArguments args) {
        List<SysTenant> tenants;
        try {
            tenants = tenantService.listAll();
        } catch (Exception e) {
            log.warn("时效质控规则种子跳过(租户表未就绪): {}", e.getMessage());
            return;
        }
        if (tenants == null) {
            return;
        }
        tenants.sort(Comparator.comparing(SysTenant::getId));
        for (SysTenant t : tenants) {
            if (t == null || t.getId() == null
                    || SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                continue; // 平台运营方租户无医院业务数据
            }
            Long prev = TenantContext.get();
            try {
                TenantContext.set(t.getId());
                seedTimelinessRules();
            } catch (Exception e) {
                log.warn("租户[{}] 时效质控规则种子跳过(可能表未建): {}", t.getId(), e.getMessage());
            } finally {
                if (prev == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(prev);
                }
            }
        }
    }

    /**
     * 播种 23 项时效规则到 his_emr_quality_rule(rule_type=2, qc_stage=0通用, rule_category='calculation'),
     * 幂等: 逐码先查 rule_code 已存在则跳过(部分缺失可补种)。公开供运维/初始化复用。
     */
    public void seedTimelinessRules() {
        int added = 0;
        for (Object[] def : TIMELINESS_SEEDS) {
            String code = (String) def[0];
            boolean exists = !ruleMapper.selectList(Wrappers.<HisEmrQualityRule>lambdaQuery()
                    .eq(HisEmrQualityRule::getRuleCode, code)).isEmpty();
            if (exists) {
                continue;
            }
            Integer severity = (Integer) def[5];
            HisEmrQualityRule r = new HisEmrQualityRule();
            r.setOrgId(resolveLeadOrgId());
            r.setRuleCode(code);
            r.setRuleName((String) def[1]);
            r.setRecordType((Integer) def[2]);
            r.setRuleType(2);
            r.setRuleConfig(JSON.toJSONString(def[3]));
            r.setDeductScore(new BigDecimal((String) def[4]));
            r.setSeverity(severity);
            r.setDescription((String) def[6]);
            r.setStatus(1);
            r.setQcStage(0);
            r.setControlLevel(severity);
            r.setRuleCategory("calculation");
            ruleMapper.insert(r);
            added++;
        }
        if (added > 0) {
            log.info("时效质控规则播种完成(新增{}条/共{}条)", added, TIMELINESS_SEEDS.length);
        }
    }

    /** 解析牵头机构ID: 优先 is_lead=1, 兜底最小 id(规则 org_id 非空约束) */
    private Long resolveLeadOrgId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return null;
        }
        List<Long> lead = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 AND is_lead = 1 ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), tenantId);
        if (!lead.isEmpty()) {
            return lead.get(0);
        }
        List<Long> any = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 ORDER BY id LIMIT 1", (rs, i) -> rs.getLong(1), tenantId);
        return any.isEmpty() ? null : any.get(0);
    }

    /* ================= 核心检查 ================= */

    /**
     * 单病历时效检查: 按病历类型匹配启用时效规则, 解析锚点→计算截止→判定状态。
     * 已完成(已提交/已审核)按完成时刻判定, 未完成(草稿)按当前时刻判定。
     *
     * @return {recordId, recordType, typeLabel, status: overdue/warning/normal, deadlineTime,
     *         remainingMinutes, rules: [{ruleId, ruleCode, ruleName, status, deadlineTime, remainingMinutes, severity}]}
     */
    public Map<String, Object> checkTimeliness(Long recordId) {
        HisInpMedicalRecord rec = recordId == null ? null : recordMapper.selectById(recordId);
        if (rec == null) {
            throw new BizException(400, "病历记录不存在");
        }
        HisInpVisit visit = rec.getInpVisitId() == null ? null : visitMapper.selectById(rec.getInpVisitId());
        if (visit != null) {
            checkVisitAccess(visit);
        }
        return evaluateRecord(rec, visit);
    }

    /** 单病历评估(无守卫, 批量/定时复用): 匹配规则→逐条算截止→取最严(最早)截止为主判定 */
    private Map<String, Object> evaluateRecord(HisInpMedicalRecord rec, HisInpVisit visit) {
        LocalDateTime now = LocalDateTime.now();
        boolean finished = rec.getStatus() != null && rec.getStatus() >= 2;
        LocalDateTime finishTime = rec.getRecordTime() != null ? rec.getRecordTime() : rec.getCreateTime();
        List<Map<String, Object>> ruleResults = new ArrayList<>();
        LocalDateTime primaryDeadline = null;
        for (HisEmrQualityRule rule : matchRules(rec, visit)) {
            JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
            LocalDateTime anchor = resolveAnchor(rec, visit, cfg.getString("anchorEvent"));
            if (anchor == null) {
                continue; // 锚点缺失(未手术/未死亡等), 规则不适用
            }
            int hours = cfg.getIntValue("deadlineHours", 24);
            boolean before = "before".equals(cfg.getString("direction"));
            LocalDateTime deadline = before ? anchor.minusHours(hours) : anchor.plusHours(hours);
            boolean overdue = finished
                    ? (finishTime != null && finishTime.isAfter(deadline))
                    : now.isAfter(deadline);
            long remaining = Duration.between(now, deadline).toMinutes();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleId", rule.getId());
            item.put("ruleCode", rule.getRuleCode());
            item.put("ruleName", rule.getRuleName());
            item.put("severity", rule.getSeverity());
            item.put("anchorEvent", cfg.getString("anchorEvent"));
            item.put("deadlineTime", deadline);
            item.put("remainingMinutes", remaining);
            item.put("status", overdue ? "overdue" : (remaining >= 0 && remaining <= WARN_HOURS * 60L ? "warning" : "normal"));
            ruleResults.add(item);
            if (primaryDeadline == null || deadline.isBefore(primaryDeadline)) {
                primaryDeadline = deadline;
            }
        }
        String status = "normal";
        long remaining = -1;
        if (primaryDeadline != null) {
            boolean overdue = finished
                    ? (finishTime != null && finishTime.isAfter(primaryDeadline))
                    : now.isAfter(primaryDeadline);
            remaining = Duration.between(now, primaryDeadline).toMinutes();
            status = overdue ? "overdue" : (remaining >= 0 && remaining <= WARN_HOURS * 60L ? "warning" : "normal");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordId", rec.getId());
        out.put("visitId", rec.getInpVisitId());
        out.put("recordType", rec.getRecordType());
        out.put("typeLabel", TYPE_LABELS.getOrDefault(rec.getRecordType(), "病历"));
        out.put("title", rec.getTitle());
        out.put("recordStatus", rec.getStatus());
        out.put("status", primaryDeadline == null ? "unknown" : status);
        out.put("deadlineTime", primaryDeadline);
        out.put("remainingMinutes", remaining);
        out.put("rules", ruleResults);
        return out;
    }

    /** 时效规则匹配: rule_type=2 启用规则中按 record_type + condition(admitSource) + nursingLevel 过滤 */
    private List<HisEmrQualityRule> matchRules(HisInpMedicalRecord rec, HisInpVisit visit) {
        List<HisEmrQualityRule> matched = new ArrayList<>();
        List<HisEmrQualityRule> rules = ruleMapper.selectList(Wrappers.<HisEmrQualityRule>lambdaQuery()
                .eq(HisEmrQualityRule::getRuleType, 2)
                .eq(HisEmrQualityRule::getStatus, 1)
                .orderByAsc(HisEmrQualityRule::getId));
        int nursing = visit == null || visit.getNursingLevel() == null ? 2 : visit.getNursingLevel();
        for (HisEmrQualityRule rule : rules) {
            if (rule.getRecordType() != null && !rule.getRecordType().equals(rec.getRecordType())) {
                continue;
            }
            JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
            /* 准入条件: admitSource=2(仅急诊) / admitSource!=2(仅普通) */
            String condition = cfg.getString("condition");
            if (StringUtils.hasText(condition) && condition.startsWith("admitSource")) {
                int src = visit == null || visit.getAdmitSource() == null ? 0 : visit.getAdmitSource();
                if (condition.contains("!=")) {
                    int required = parseIntSafe(condition.split("!=")[1], 0);
                    if (src == required) {
                        continue;
                    }
                } else {
                    int required = parseIntSafe(condition.split("=")[1], 0);
                    if (src != required) {
                        continue;
                    }
                }
            }
            /* 护理等级条件(日常病程按护理等级): 就诊未维护护理等级时按一级兜底 */
            Integer nl = cfg.getInteger("nursingLevel");
            if (nl != null && nl != nursing) {
                continue;
            }
            matched.add(rule);
        }
        return matched;
    }

    /**
     * 锚点时间解析: admission→入院时间, discharge→出院时间, operation→手术时间(structureData.surgeryDate→recordTime),
     * death→死亡时间(structureData.deathTime→出院时间), rescue/consultation/transfer/critical→structureData 业务时间
     * →recordTime, lastRecord→同就诊最近一份日常病程(不含自身)→入院时间。
     */
    private LocalDateTime resolveAnchor(HisInpMedicalRecord rec, HisInpVisit visit, String anchorEvent) {
        JSONObject sd = parseObjectSafe(rec.getStructureData());
        if (anchorEvent == null) {
            anchorEvent = "admission";
        }
        switch (anchorEvent) {
            case "admission":
                return visit != null && visit.getAdmitDate() != null ? visit.getAdmitDate() : rec.getRecordTime();
            case "discharge":
                if (visit != null && visit.getDischargeDate() != null) {
                    return visit.getDischargeDate();
                }
                return rec.getRecordTime();
            case "operation":
                LocalDateTime op = toDateTime(sd.get("surgeryDate"));
                if (op != null) {
                    return op;
                }
                return rec.getRecordTime() != null ? rec.getRecordTime()
                        : (visit != null ? visit.getAdmitDate() : null);
            case "death":
                LocalDateTime death = toDateTime(sd.get("deathTime"));
                if (death != null) {
                    return death;
                }
                if (visit != null && visit.getDischargeDate() != null) {
                    return visit.getDischargeDate();
                }
                return rec.getRecordTime();
            case "rescue":
                LocalDateTime rescue = toDateTime(sd.get("rescueTime"));
                return rescue != null ? rescue
                        : (rec.getRecordTime() != null ? rec.getRecordTime()
                        : (visit != null ? visit.getAdmitDate() : null));
            case "consultation":
                LocalDateTime consult = toDateTime(sd.get("consultTime"));
                return consult != null ? consult : rec.getRecordTime();
            case "transfer":
                LocalDateTime transfer = toDateTime(sd.get("transferTime"));
                return transfer != null ? transfer : rec.getRecordTime();
            case "critical":
                LocalDateTime critical = toDateTime(sd.get("criticalTime"));
                return critical != null ? critical : rec.getRecordTime();
            case "lastRecord":
                HisInpMedicalRecord last = recordMapper.selectOne(Wrappers.<HisInpMedicalRecord>lambdaQuery()
                        .eq(HisInpMedicalRecord::getInpVisitId, rec.getInpVisitId())
                        .eq(HisInpMedicalRecord::getRecordType, 3)
                        .ne(rec.getId() != null, HisInpMedicalRecord::getId, rec.getId())
                        .isNotNull(HisInpMedicalRecord::getRecordTime)
                        .orderByDesc(HisInpMedicalRecord::getRecordTime)
                        .last("LIMIT 1"));
                if (last != null && last.getRecordTime() != null) {
                    return last.getRecordTime();
                }
                return visit != null ? visit.getAdmitDate() : rec.getCreateTime();
            default:
                return visit != null ? visit.getAdmitDate() : rec.getRecordTime();
        }
    }

    /** 按记录类型计算截止时间(取该类型第一条启用时效规则的 deadlineHours/direction, 无规则返回 null) */
    public LocalDateTime calcDeadline(int recordType, LocalDateTime anchorTime) {
        if (anchorTime == null) {
            return null;
        }
        HisEmrQualityRule rule = ruleMapper.selectOne(Wrappers.<HisEmrQualityRule>lambdaQuery()
                .eq(HisEmrQualityRule::getRuleType, 2)
                .eq(HisEmrQualityRule::getStatus, 1)
                .eq(HisEmrQualityRule::getRecordType, recordType)
                .orderByAsc(HisEmrQualityRule::getId)
                .last("LIMIT 1"));
        if (rule == null) {
            return null;
        }
        JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
        int hours = cfg.getIntValue("deadlineHours", 24);
        return "before".equals(cfg.getString("direction")) ? anchorTime.minusHours(hours) : anchorTime.plusHours(hours);
    }

    /* ================= 病区批量 ================= */

    /**
     * 按病区批量检查: 病区在院患者(在院/出院办理中)的未完成(草稿)病历逐一检查,
     * 返回待完成清单, 超时优先(remainingMinutes 升序: 超时最久→临近→宽裕)。
     *
     * @param date 可选, 仅统计该日期(yyyy-MM-dd)及之前创建的病历
     */
    public Map<String, Object> batchCheckByWard(Long wardId, String date) {
        List<HisInpVisit> visits = visitMapper.selectList(Wrappers.<HisInpVisit>lambdaQuery()
                .eq(wardId != null, HisInpVisit::getWardId, wardId)
                .in(HisInpVisit::getVisitStatus, 2, 3));
        if (visits.isEmpty()) {
            return summaryEnvelope(wardId, date, new ArrayList<>());
        }
        List<Long> visitIds = new ArrayList<>();
        for (HisInpVisit v : visits) {
            visitIds.add(v.getId());
        }
        LocalDateTime dateEnd = parseDateEnd(date);
        List<HisInpMedicalRecord> records = recordMapper.selectList(Wrappers.<HisInpMedicalRecord>lambdaQuery()
                .in(HisInpMedicalRecord::getInpVisitId, visitIds)
                .eq(HisInpMedicalRecord::getStatus, 1)
                .le(dateEnd != null, HisInpMedicalRecord::getCreateTime, dateEnd));
        List<Map<String, Object>> items = new ArrayList<>();
        for (HisInpMedicalRecord rec : records) {
            try {
                items.add(evaluateRecord(rec, findVisit(visits, rec.getInpVisitId())));
            } catch (Exception e) {
                log.warn("病区时效批量检查单条失败(不阻断): recordId={}, err={}", rec.getId(), e.getMessage());
            }
        }
        items.sort(Comparator.comparing(m -> ((Number) m.get("remainingMinutes")).longValue()));
        return summaryEnvelope(wardId, date, items);
    }

    /** 病区时效概况: 待完成总量/超时/临近(≤2h)/正常 计数与清单 */
    public Map<String, Object> wardSummary(Long wardId, String date) {
        return batchCheckByWard(wardId, date);
    }

    /** 批量结果信封: 概览计数 + items 清单 */
    private Map<String, Object> summaryEnvelope(Long wardId, String date, List<Map<String, Object>> items) {
        int overdue = 0;
        int warning = 0;
        int normal = 0;
        for (Map<String, Object> item : items) {
            switch (String.valueOf(item.get("status"))) {
                case "overdue":
                    overdue++;
                    break;
                case "warning":
                    warning++;
                    break;
                default:
                    normal++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wardId", wardId);
        out.put("date", date);
        out.put("total", items.size());
        out.put("overdueCount", overdue);
        out.put("warningCount", warning);
        out.put("normalCount", normal);
        out.put("items", items);
        return out;
    }

    /* ================= 超时/临近清单(JdbcTemplate 联查) ================= */

    /** 超时病历列表: 病区/科室/记录时间范围可选过滤, 含患者/病区/主管医生与超时分钟数 */
    public List<Map<String, Object>> overdueList(Long wardId, Long deptId, String startDate, String endDate) {
        StringBuilder sql = new StringBuilder(SCAN_SQL_BASE)
                .append(" AND r.deadline_time IS NOT NULL AND r.deadline_time < NOW() ");
        List<Object> params = new ArrayList<>();
        appendScopeAndFilters(sql, params, wardId, deptId, startDate, endDate);
        sql.append(ORDER_OVERDUE);
        return queryTimelinessRows(sql.toString(), params);
    }

    /** 临近超时预警列表: 距截止 withinHours 小时内(默认2h), 截止升序 */
    public List<Map<String, Object>> nearDeadlineList(Long wardId, int withinHours) {
        int hours = withinHours <= 0 ? WARN_HOURS : Math.min(withinHours, 72);
        StringBuilder sql = new StringBuilder(SCAN_SQL_BASE)
                .append(" AND r.deadline_time IS NOT NULL AND r.deadline_time BETWEEN NOW() AND DATE_ADD(NOW(), INTERVAL ? HOUR) ");
        List<Object> params = new ArrayList<>();
        params.add(hours);
        if (wardId != null) {
            sql.append(" AND v.ward_id = ? ");
            params.add(wardId);
        }
        sql.append(" AND r.tenant_id = ? ");
        params.add(TenantContext.get());
        sql.append(ORDER_OVERDUE);
        return queryTimelinessRows(sql.toString(), params);
    }

    /** 列表查询公共拼装: 机构范围 + 病区/科室/记录时间范围 */
    private void appendScopeAndFilters(StringBuilder sql, List<Object> params,
                                       Long wardId, Long deptId, String startDate, String endDate) {
        if (TenantContext.get() != null) {
            sql.append(" AND r.tenant_id = ? ");
            params.add(TenantContext.get());
        }
        if (wardId != null) {
            sql.append(" AND v.ward_id = ? ");
            params.add(wardId);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ? ");
            params.add(deptId);
        }
        LocalDate start = parseDate(startDate);
        if (start != null) {
            sql.append(" AND r.record_time >= ? ");
            params.add(start.atStartOfDay());
        }
        LocalDate end = parseDate(endDate);
        if (end != null) {
            sql.append(" AND r.record_time < ? ");
            params.add(end.plusDays(1).atStartOfDay());
        }
    }

    /** 行集→结果 Map(统一补 recordType 标签/状态/超时分钟数) */
    private List<Map<String, Object>> queryTimelinessRows(String sql, List<Object> params) {
        LocalDateTime now = LocalDateTime.now();
        return jdbcTemplate.query(sql, params.toArray(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            LocalDateTime deadline = toDateTime(rs.getTimestamp("deadlineTime"));
            long remaining = deadline == null ? 0 : Duration.between(now, deadline).toMinutes();
            int recordType = rs.getInt("recordType");
            m.put("recordId", rs.getLong("recordId"));
            m.put("visitId", rs.getLong("visitId"));
            m.put("patientId", rs.getLong("patientId"));
            m.put("patientName", rs.getString("patientName"));
            m.put("inpNo", rs.getString("inpNo"));
            m.put("wardId", rs.getObject("wardId"));
            m.put("deptId", rs.getObject("deptId"));
            m.put("recordType", recordType);
            m.put("typeLabel", TYPE_LABELS.getOrDefault(recordType, "病历"));
            m.put("title", rs.getString("title"));
            m.put("deadlineTime", deadline == null ? null : deadline.format(DT_FMT));
            m.put("remainingMinutes", remaining);
            m.put("overdue", remaining < 0);
            m.put("doctorId", rs.getObject("doctorId"));
            m.put("visitDoctorId", rs.getObject("visitDoctorId"));
            return m;
        });
    }

    /* ================= 定时扫描与自动通知 ================= */

    /**
     * 定时扫描(每30分钟): 遍历租户→在院未签名(草稿)病历逐条检查时效 —
     * 到期前2h 推送 EMR_QC_DEADLINE_WARN; 到期前1h 推送加急; 已超时推送 EMR_QC_OVERDUE 并自动落
     * his_emr_qc_defect(auto_generated=1, defect_type='时效', status=0, 同病历同规则未整改去重)。
     * 单患者 try-catch 包裹, 单个失败不阻断批量。
     */
    @Scheduled(fixedRate = 1_800_000)
    public void autoNotify() {
        List<SysTenant> tenants;
        try {
            tenants = tenantService.listAll();
        } catch (Exception e) {
            log.warn("时效定时扫描跳过(租户表未就绪): {}", e.getMessage());
            return;
        }
        if (tenants == null) {
            return;
        }
        for (SysTenant t : tenants) {
            if (t == null || t.getId() == null
                    || SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                continue;
            }
            Long prev = TenantContext.get();
            try {
                TenantContext.set(t.getId());
                scanTenant();
            } catch (Exception e) {
                log.warn("租户[{}] 时效定时扫描失败: {}", t.getId(), e.getMessage());
            } finally {
                if (prev == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(prev);
                }
            }
        }
    }

    /** 单租户扫描: 先补算缺失截止(自愈), 再逐行预警/超时处理 */
    private void scanTenant() {
        backfillMissingDeadlines();
        List<Map<String, Object>> rows = jdbcTemplate.query(
                SCAN_SQL_BASE + " AND r.deadline_time IS NOT NULL AND r.tenant_id = ? ",
                (rs, i) -> rowToMap(rs), TenantContext.get());
        int warned = 0;
        int overdueNotified = 0;
        for (Map<String, Object> row : rows) {
            try {
                LocalDateTime deadline = (LocalDateTime) row.get("deadlineTime");
                long remaining = Duration.between(LocalDateTime.now(), deadline).toMinutes();
                if (remaining < 0) {
                    notifyOverdue(row, -remaining);
                    overdueNotified++;
                } else if (remaining <= URGENT_HOURS * 60L) {
                    notifyDeadlineWarn(row, remaining, "urgent");
                    warned++;
                } else if (remaining <= WARN_HOURS * 60L) {
                    notifyDeadlineWarn(row, remaining, "normal");
                    warned++;
                }
            } catch (Exception e) {
                log.warn("时效扫描单病历处理失败(不阻断): recordId={}, err={}", row.get("recordId"), e.getMessage());
            }
        }
        if (warned > 0 || overdueNotified > 0) {
            log.info("时效定时扫描完成: 扫描{}条, 预警{}条, 超时通知{}条", rows.size(), warned, overdueNotified);
        }
    }

    /** 补算截止自愈: 在院草稿且 deadline_time 为空的病历按规则计算回填(逐条 try-catch) */
    private void backfillMissingDeadlines() {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                SCAN_SQL_BASE.replace("AND r.status = 1", "AND r.status = 1 AND r.deadline_time IS NULL")
                        + " AND r.tenant_id = ? ",
                (rs, i) -> rowToMap(rs), TenantContext.get());
        for (Map<String, Object> row : rows) {
            try {
                Long recordId = (Long) row.get("recordId");
                HisInpMedicalRecord rec = recordMapper.selectById(recordId);
                if (rec == null || rec.getDeadlineTime() != null) {
                    continue;
                }
                HisInpVisit visit = rec.getInpVisitId() == null ? null : visitMapper.selectById(rec.getInpVisitId());
                LocalDateTime deadline = resolvePrimaryDeadline(rec, visit);
                if (deadline != null) {
                    rec.setDeadlineTime(deadline);
                    recordMapper.updateById(rec);
                }
            } catch (Exception e) {
                log.debug("截止时间回填失败(跳过): recordId={}, err={}", row.get("recordId"), e.getMessage());
            }
        }
    }

    /** 主截止计算: 匹配规则中最早(最严)的截止时间 */
    private LocalDateTime resolvePrimaryDeadline(HisInpMedicalRecord rec, HisInpVisit visit) {
        LocalDateTime primary = null;
        for (HisEmrQualityRule rule : matchRules(rec, visit)) {
            JSONObject cfg = parseObjectSafe(rule.getRuleConfig());
            LocalDateTime anchor = resolveAnchor(rec, visit, cfg.getString("anchorEvent"));
            if (anchor == null) {
                continue;
            }
            int hours = cfg.getIntValue("deadlineHours", 24);
            LocalDateTime deadline = "before".equals(cfg.getString("direction"))
                    ? anchor.minusHours(hours) : anchor.plusHours(hours);
            if (primary == null || deadline.isBefore(primary)) {
                primary = deadline;
            }
        }
        return primary;
    }

    /** 临近预警推送: EMR_QC_DEADLINE_WARN 定向记录医生+主管医生(去重) */
    private void notifyDeadlineWarn(Map<String, Object> row, long remainingMinutes, String urgency) {
        Map<String, Object> data = envelopeOf(row);
        data.put("urgency", urgency);
        data.put("message", urgency.equals("urgent")
                ? "【加急】病历《" + row.get("title") + "》将于 " + remainingMinutes + " 分钟后超过书写时限, 请立即完成"
                : "病历《" + row.get("title") + "》距书写截止还有 " + (remainingMinutes / 60) + " 小时" + (remainingMinutes % 60) + " 分");
        publishToDoctors(row, EmrEventType.EMR_QC_DEADLINE_WARN, data);
    }

    /** 超时通知推送 + 自动落缺陷(同病历同规则未整改去重) */
    private void notifyOverdue(Map<String, Object> row, long overdueMinutes) {
        Map<String, Object> data = envelopeOf(row);
        data.put("urgency", "overdue");
        data.put("overdueMinutes", overdueMinutes);
        data.put("message", "病历《" + row.get("title") + "》已超过书写时限 " + overdueMinutes + " 分钟, 请立即整改");
        publishToDoctors(row, EmrEventType.EMR_QC_OVERDUE, data);
        createDefectIfAbsent(row, overdueMinutes);
    }

    /** 推送信封: 病历/患者/截止信息(SSE data 负载) */
    private Map<String, Object> envelopeOf(Map<String, Object> row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", String.valueOf(row.get("recordId")));
        data.put("visitId", row.get("visitId"));
        data.put("recordType", row.get("recordType"));
        data.put("typeLabel", TYPE_LABELS.getOrDefault((Integer) row.get("recordType"), "病历"));
        data.put("title", row.get("title"));
        data.put("patientName", row.get("patientName"));
        data.put("inpNo", row.get("inpNo"));
        data.put("wardId", row.get("wardId"));
        data.put("deadlineTime", row.get("deadlineTime") == null ? null
                : ((LocalDateTime) row.get("deadlineTime")).format(DT_FMT));
        return data;
    }

    /** 定向推送记录医生与主管医生(主管医生缺/重复时仅推一次) */
    private void publishToDoctors(Map<String, Object> row, EmrEventType eventType, Map<String, Object> data) {
        Long doctorId = toLong(row.get("doctorId"));
        Long visitDoctorId = toLong(row.get("visitDoctorId"));
        if (doctorId != null) {
            emrEventPublisher.publish(doctorId, eventType, data);
        }
        if (visitDoctorId != null && !visitDoctorId.equals(doctorId)) {
            emrEventPublisher.publish(visitDoctorId, eventType, data);
        }
    }

    /** 落时效缺陷: auto_generated=1, defect_type='时效', status=0; 同病历同规则存在未整改缺陷则跳过 */
    private void createDefectIfAbsent(Map<String, Object> row, long overdueMinutes) {
        Long recordId = toLong(row.get("recordId"));
        if (recordId == null) {
            return;
        }
        HisEmrQualityRule rule = primaryRuleOf(recordId);
        String ruleCode = rule == null ? "TL_UNKNOWN" : rule.getRuleCode();
        boolean duplicated = !defectMapper.selectList(Wrappers.<HisEmrQcDefect>lambdaQuery()
                .eq(HisEmrQcDefect::getRecordId, recordId)
                .eq(HisEmrQcDefect::getRuleCode, ruleCode)
                .eq(HisEmrQcDefect::getAutoGenerated, 1)
                .eq(HisEmrQcDefect::getStatus, 0)).isEmpty();
        if (duplicated) {
            return;
        }
        HisEmrQcDefect defect = new HisEmrQcDefect();
        defect.setRecordId(recordId);
        defect.setVisitId(toLong(row.get("visitId")));
        defect.setPatientId(toLong(row.get("patientId")));
        if (rule != null) {
            defect.setRuleId(rule.getId());
        }
        defect.setRuleCode(ruleCode);
        defect.setRuleName(rule == null ? "病历书写超时" : rule.getRuleName());
        defect.setDefectType("时效");
        defect.setDefectDesc("病历《" + row.get("title") + "》超过书写时限, 截止 "
                + (((LocalDateTime) row.get("deadlineTime")).format(DT_FMT)) + ", 已超时 " + overdueMinutes + " 分钟");
        defect.setDeductScore(rule == null || rule.getDeductScore() == null ? BigDecimal.ZERO : rule.getDeductScore());
        defect.setSeverity(rule == null || rule.getSeverity() == null ? 2 : rule.getSeverity());
        defect.setQcStage(1);
        defect.setAutoGenerated(1);
        defect.setStatus(0);
        defectMapper.insert(defect);
        log.info("时效缺陷自动落库: recordId={}, ruleCode={}, 超时{}分钟", recordId, ruleCode, overdueMinutes);
    }

    /** 记录对应的主时效规则(匹配第一条, 用于缺陷冗余字段) */
    private HisEmrQualityRule primaryRuleOf(Long recordId) {
        HisInpMedicalRecord rec = recordMapper.selectById(recordId);
        if (rec == null) {
            return null;
        }
        HisInpVisit visit = rec.getInpVisitId() == null ? null : visitMapper.selectById(rec.getInpVisitId());
        List<HisEmrQualityRule> matched = matchRules(rec, visit);
        return matched.isEmpty() ? null : matched.get(0);
    }

    /* ================= 事件监听(截止回填) ================= */

    /**
     * 监听病历事件(创建/保存等携带 recordId 的 EmrEvent): 自动按时效规则计算截止并回填
     * his_inp_medical_record.deadline_time(仅空值回填, 不覆盖模板驱动的已有值)。
     * 全程 try-catch, 异常仅 debug 记录, 不影响业务发布方与 SSE 推送主流程(EmrEventListener)。
     */
    @EventListener
    public void onEmrEvent(EmrEvent event) {
        try {
            if (event == null || event.getData() == null) {
                return;
            }
            Long recordId = toLong(event.getData().get("recordId"));
            if (recordId == null) {
                return;
            }
            HisInpMedicalRecord rec = recordMapper.selectById(recordId);
            if (rec == null || rec.getDeadlineTime() != null) {
                return; // 幂等: 已有截止(模板建档时计算)不覆盖
            }
            HisInpVisit visit = rec.getInpVisitId() == null ? null : visitMapper.selectById(rec.getInpVisitId());
            LocalDateTime deadline = resolvePrimaryDeadline(rec, visit);
            if (deadline != null) {
                rec.setDeadlineTime(deadline);
                recordMapper.updateById(rec);
                log.debug("事件驱动截止回填: recordId={}, deadline={}", recordId, deadline.format(DT_FMT));
            }
        } catch (Exception e) {
            log.debug("病历事件截止回填失败(忽略): {}", e.getMessage());
        }
    }

    /* ================= 私有辅助 ================= */

    /** 就诊机构访问校验(镜像 EmrQualityService.checkVisitAccess): 机构越权 403 */
    private void checkVisitAccess(HisInpVisit visit) {
        Long scope = guard.scopeOrgId(visit.getOrgId());
        if (scope == null || !scope.equals(visit.getOrgId())) {
            throw new BizException(403, "无权访问该住院就诊");
        }
    }

    /** 按就诊ID从已查列表中取就诊(避免批量场景重复查库) */
    private HisInpVisit findVisit(List<HisInpVisit> visits, Long visitId) {
        if (visitId == null) {
            return null;
        }
        for (HisInpVisit v : visits) {
            if (visitId.equals(v.getId())) {
                return v;
            }
        }
        return null;
    }

    /** JDBC 行→Map(时间列直接 LocalDateTime, 供后续统一处理) */
    private Map<String, Object> rowToMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordId", rs.getLong("recordId"));
        m.put("recordType", rs.getInt("recordType"));
        m.put("title", rs.getString("title"));
        Timestamp recordTime = rs.getTimestamp("recordTime");
        m.put("recordTime", recordTime == null ? null : recordTime.toLocalDateTime());
        Timestamp deadline = rs.getTimestamp("deadlineTime");
        m.put("deadlineTime", deadline == null ? null : deadline.toLocalDateTime());
        m.put("doctorId", rs.getObject("doctorId"));
        m.put("visitId", rs.getLong("visitId"));
        m.put("patientId", rs.getLong("patientId"));
        m.put("inpNo", rs.getString("inpNo"));
        m.put("wardId", rs.getObject("wardId"));
        m.put("deptId", rs.getObject("deptId"));
        m.put("visitDoctorId", rs.getObject("visitDoctorId"));
        m.put("patientName", rs.getString("patientName"));
        return m;
    }

    /** 日期解析(yyyy-MM-dd), 空/非法返回 null */
    private LocalDate parseDate(String date) {
        if (!StringUtils.hasText(date)) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 日期当日末尾(LocalDateTime), 用于"该日期及之前"过滤 */
    private LocalDateTime parseDateEnd(String date) {
        LocalDate d = parseDate(date);
        return d == null ? null : d.plusDays(1).atStartOfDay();
    }

    /** structureData 业务时间字段解析: 支持 yyyy-MM-dd HH:mm[:ss] / ISO / 毫秒时间戳 */
    private LocalDateTime toDateTime(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDateTime) {
            return (LocalDateTime) raw;
        }
        if (raw instanceof Timestamp) {
            return ((Timestamp) raw).toLocalDateTime();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            if (s.matches("\\d{13}")) {
                return new Timestamp(Long.parseLong(s)).toLocalDateTime();
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(:\\d{2})?")) {
                return s.length() == 16
                        ? LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        : LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return LocalDateTime.parse(s); // ISO-8601 兜底
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 安全解析: 空/非法返回空对象 */
    private JSONObject parseObjectSafe(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** int 安全解析(带默认值) */
    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** Object→Long(Number/数字字符串), 失败返回 null */
    private Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 种子 rule_config 构造(无护理等级) */
    private static JSONObject cfg(int deadlineHours, String anchorEvent, String direction, String condition) {
        return cfg(deadlineHours, anchorEvent, direction, condition, null);
    }

    /** 种子 rule_config 构造: 时效规则核心五要素 */
    private static JSONObject cfg(int deadlineHours, String anchorEvent, String direction, String condition, Integer nursingLevel) {
        JSONObject o = new JSONObject();
        o.put("deadlineHours", deadlineHours);
        o.put("anchorEvent", anchorEvent);
        o.put("direction", direction);
        if (condition != null) {
            o.put("condition", condition);
        }
        if (nursingLevel != null) {
            o.put("nursingLevel", nursingLevel);
        }
        return o;
    }
}
