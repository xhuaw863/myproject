package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.NursingPlanDTO;
import com.yb.hi.dto.inpatient.NursingPlanInstanceDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisNursingPlanInstance;
import com.yb.hi.entity.inpatient.HisNursingPlanTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisNursingPlanInstanceMapper;
import com.yb.hi.mapper.inpatient.HisNursingPlanTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 护理计划服务: 计划模板管理(按量表分值区间触发) + 患者计划实例的创建/执行/评价/关闭。
 * 说明:
 * 1) 模板与实例均走 MyBatis-Plus 正常租户隔离(tenant_id 由租户插件自动注入/过滤),
 *    实例归属患者就诊机构(org_id 取 his_inp_visit.org_id), 模板为租户级配置不做机构归属校验;
 * 2) 触发分值区间 trigger_score_range 支持 "min-max"/"min~max"(含端点)、">=x"/">x"/"<=x"/"<x"、精确值 "x";
 * 3) 评估自动建计划(autoCreateFromAssessment, 被 NursingScaleService 调用): 匹配启用模板
 *    (trigger_scale_code 相等 + 评分命中区间), 复制模板的诊断/目标/措施;
 *    同一模板对同一患者仅保留一个执行中实例, 重复评估不重复建计划;
 * 4) 实例是模板的快照(创建时复制诊断/目标/措施), 模板后续修改/删除不影响已生成实例;
 * 5) actual_interventions 为 JSON 数组, recordIntervention 追加措施记录(缺 time 字段自动补记录时间)。
 */
@Slf4j
@Service
public class NursingPlanService {

    /** 计划实例状态: 1执行中 2已评价 3已关闭 */
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_EVALUATED = 2;
    public static final int STATUS_CLOSED = 3;

    /** 模板状态: 1启用 0停用 */
    public static final int TEMPLATE_ENABLED = 1;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisNursingPlanTemplateMapper templateMapper;
    private final HisNursingPlanInstanceMapper instanceMapper;
    private final HisInpVisitMapper visitMapper;

    public NursingPlanService(HisNursingPlanTemplateMapper templateMapper,
                              HisNursingPlanInstanceMapper instanceMapper,
                              HisInpVisitMapper visitMapper) {
        this.templateMapper = templateMapper;
        this.instanceMapper = instanceMapper;
        this.visitMapper = visitMapper;
    }

    /* ================= 模板管理 ================= */

    /** 模板列表: 可按触发量表编码/科室筛选, id 倒序(新建在前, 含停用模板由前端按状态展示)。 */
    public List<HisNursingPlanTemplate> listTemplates(String scaleCode, Long deptId) {
        return templateMapper.selectList(Wrappers.<HisNursingPlanTemplate>lambdaQuery()
                .eq(StringUtils.hasText(scaleCode), HisNursingPlanTemplate::getTriggerScaleCode, scaleCode)
                .eq(deptId != null, HisNursingPlanTemplate::getDeptId, deptId)
                .orderByDesc(HisNursingPlanTemplate::getId));
    }

    /** 创建模板: 计划名称必填; 配置触发分值区间时必须指定触发量表编码且格式合法; 措施须为 JSON 数组。 */
    public HisNursingPlanTemplate createTemplate(NursingPlanDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (!StringUtils.hasText(dto.getPlanName())) {
            throw new BizException(400, "计划名称不能为空");
        }
        validateTriggerConfig(dto.getTriggerScaleCode(), dto.getTriggerScoreRange());
        if (StringUtils.hasText(dto.getInterventions())) {
            validateJsonArray(dto.getInterventions(), "护理措施必须为合法 JSON 数组");
        }
        validateStatus(dto.getStatus());
        LoginUser u = requireLogin();

        HisNursingPlanTemplate t = new HisNursingPlanTemplate();
        t.setOrgId(u.getOrgId() != null ? u.getOrgId() : 0L);
        t.setPlanName(dto.getPlanName().trim());
        t.setTriggerScaleCode(trimToNull(dto.getTriggerScaleCode()));
        t.setTriggerScoreRange(trimToNull(dto.getTriggerScoreRange()));
        t.setNursingDiagnosis(dto.getNursingDiagnosis());
        t.setNursingGoal(dto.getNursingGoal());
        t.setInterventions(dto.getInterventions());
        t.setEvaluationCriteria(dto.getEvaluationCriteria());
        t.setDeptId(dto.getDeptId());
        t.setStatus(dto.getStatus() != null ? dto.getStatus() : TEMPLATE_ENABLED);
        templateMapper.insert(t);
        return t;
    }

    /** 更新模板: 仅覆盖 DTO 中提供的字段(未提供的保持原值), 触发配置/措施格式校验同创建。 */
    public void updateTemplate(Long id, NursingPlanDTO dto) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        HisNursingPlanTemplate t = requireTemplate(id);

        String planName = StringUtils.hasText(dto.getPlanName()) ? dto.getPlanName().trim() : t.getPlanName();
        String triggerScaleCode = StringUtils.hasText(dto.getTriggerScaleCode())
                ? dto.getTriggerScaleCode().trim() : t.getTriggerScaleCode();
        String triggerScoreRange = StringUtils.hasText(dto.getTriggerScoreRange())
                ? dto.getTriggerScoreRange().trim() : t.getTriggerScoreRange();
        validateTriggerConfig(triggerScaleCode, triggerScoreRange);
        String interventions = StringUtils.hasText(dto.getInterventions()) ? dto.getInterventions() : t.getInterventions();
        if (StringUtils.hasText(interventions)) {
            validateJsonArray(interventions, "护理措施必须为合法 JSON 数组");
        }
        Integer status = dto.getStatus() != null ? dto.getStatus() : t.getStatus();
        validateStatus(status);

        int n = templateMapper.update(null, Wrappers.<HisNursingPlanTemplate>lambdaUpdate()
                .eq(HisNursingPlanTemplate::getId, t.getId())
                .set(HisNursingPlanTemplate::getPlanName, planName)
                .set(HisNursingPlanTemplate::getTriggerScaleCode, triggerScaleCode)
                .set(HisNursingPlanTemplate::getTriggerScoreRange, triggerScoreRange)
                .set(HisNursingPlanTemplate::getNursingDiagnosis,
                        StringUtils.hasText(dto.getNursingDiagnosis()) ? dto.getNursingDiagnosis() : t.getNursingDiagnosis())
                .set(HisNursingPlanTemplate::getNursingGoal,
                        StringUtils.hasText(dto.getNursingGoal()) ? dto.getNursingGoal() : t.getNursingGoal())
                .set(HisNursingPlanTemplate::getInterventions, interventions)
                .set(HisNursingPlanTemplate::getEvaluationCriteria,
                        StringUtils.hasText(dto.getEvaluationCriteria()) ? dto.getEvaluationCriteria() : t.getEvaluationCriteria())
                .set(HisNursingPlanTemplate::getDeptId, dto.getDeptId() != null ? dto.getDeptId() : t.getDeptId())
                .set(HisNursingPlanTemplate::getStatus, status)
                .set(HisNursingPlanTemplate::getUpdateBy, currentUserName()));
        if (n == 0) {
            throw new BizException(409, "模板已变更或不存在, 请刷新后重试");
        }
    }

    /** 删除模板(逻辑删除): 已生成的计划实例是模板快照, 不受删除影响。 */
    public void removeTemplate(Long id) {
        requireTemplate(id);
        templateMapper.deleteById(id);
    }

    /* ================= 计划实例 ================= */

    /**
     * 手工创建计划实例(status=1执行中): 可携带模板ID, DTO 未提供的诊断/目标/措施回退取模板内容。
     */
    public HisNursingPlanInstance createInstance(NursingPlanInstanceDTO dto) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        HisNursingPlanTemplate template = null;
        if (dto.getTemplateId() != null) {
            template = templateMapper.selectById(dto.getTemplateId());
            if (template == null) {
                throw new BizException(404, "护理计划模板不存在");
            }
        }
        HisNursingPlanInstance inst = new HisNursingPlanInstance();
        inst.setOrgId(visit.getOrgId());
        inst.setInpVisitId(visit.getId());
        inst.setTemplateId(dto.getTemplateId());
        inst.setScaleRecordId(dto.getScaleRecordId());
        inst.setNursingDiagnosis(pick(dto.getNursingDiagnosis(),
                template == null ? null : template.getNursingDiagnosis()));
        inst.setNursingGoal(pick(dto.getNursingGoal(),
                template == null ? null : template.getNursingGoal()));
        inst.setPlannedInterventions(pick(dto.getPlannedInterventions(),
                template == null ? null : template.getInterventions()));
        inst.setActualInterventions(dto.getActualInterventions());
        inst.setStartTime(dto.getStartTime() != null ? dto.getStartTime() : LocalDateTime.now());
        inst.setStatus(STATUS_ACTIVE);
        inst.setNurseId(dto.getNurseId() != null ? dto.getNurseId() : currentNurseId());
        instanceMapper.insert(inst);
        return inst;
    }

    /**
     * 评估自动创建计划实例(被 NursingScaleService.assess 调用):
     * 按量表编码+评分匹配启用模板, 无匹配返回 null; 有匹配则复制模板诊断/目标/措施创建实例;
     * 同一模板对同一患者已有执行中实例则直接返回该实例(重复评估不重复建计划)。
     */
    public HisNursingPlanInstance autoCreateFromAssessment(Long visitId, Long scaleRecordId,
                                                           String scaleCode, BigDecimal score) {
        HisInpVisit visit = requireVisit(visitId);
        HisNursingPlanTemplate matched = findMatchedTemplate(scaleCode, score);
        if (matched == null) {
            return null;
        }
        List<HisNursingPlanInstance> actives = instanceMapper.selectList(
                Wrappers.<HisNursingPlanInstance>lambdaQuery()
                        .eq(HisNursingPlanInstance::getInpVisitId, visit.getId())
                        .eq(HisNursingPlanInstance::getTemplateId, matched.getId())
                        .eq(HisNursingPlanInstance::getStatus, STATUS_ACTIVE)
                        .orderByDesc(HisNursingPlanInstance::getId));
        if (!actives.isEmpty()) {
            log.info("患者[visitId={}] 模板[{}] 已有执行中护理计划, 本次评估不重复创建",
                    visit.getId(), matched.getId());
            return actives.get(0);
        }
        HisNursingPlanInstance inst = new HisNursingPlanInstance();
        inst.setOrgId(visit.getOrgId());
        inst.setInpVisitId(visit.getId());
        inst.setTemplateId(matched.getId());
        inst.setScaleRecordId(scaleRecordId);
        inst.setNursingDiagnosis(matched.getNursingDiagnosis());
        inst.setNursingGoal(matched.getNursingGoal());
        inst.setPlannedInterventions(matched.getInterventions());
        inst.setStartTime(LocalDateTime.now());
        inst.setStatus(STATUS_ACTIVE);
        inst.setNurseId(currentNurseId());
        instanceMapper.insert(inst);
        log.info("评估触发护理计划: visitId={}, scale={}, score={}, templateId={}, instanceId={}",
                visit.getId(), scaleCode, score, matched.getId(), inst.getId());
        return inst;
    }

    /** 患者计划列表(按就诊): 可选状态筛选, 开始时间倒序(最新在前)。 */
    public List<HisNursingPlanInstance> listByVisit(Long visitId, Integer status) {
        requireVisit(visitId);
        return instanceMapper.selectList(Wrappers.<HisNursingPlanInstance>lambdaQuery()
                .eq(HisNursingPlanInstance::getInpVisitId, visitId)
                .eq(status != null, HisNursingPlanInstance::getStatus, status)
                .orderByDesc(HisNursingPlanInstance::getStartTime)
                .orderByDesc(HisNursingPlanInstance::getId));
    }

    /**
     * 记录措施执行: interventionJson 须为 JSON 对象(或对象数组), 追加到 actual_interventions JSON 数组;
     * 措施对象缺 time 字段时自动补记录时间(yyyy-MM-dd HH:mm); 存量 JSON 非法时重建数组(容错不阻断)。
     */
    public void recordIntervention(Long instanceId, String interventionJson) {
        HisNursingPlanInstance inst = requireInstance(instanceId);
        if (!StringUtils.hasText(interventionJson)) {
            throw new BizException(400, "措施内容不能为空");
        }
        Object parsed;
        try {
            parsed = JSON.parse(interventionJson);
        } catch (Exception e) {
            throw new BizException(400, "措施内容必须为合法 JSON");
        }
        JSONArray append = new JSONArray();
        if (parsed instanceof JSONArray) {
            JSONArray arr = (JSONArray) parsed;
            if (arr.isEmpty()) {
                throw new BizException(400, "措施内容不能为空数组");
            }
            for (int i = 0; i < arr.size(); i++) {
                append.add(asInterventionEntry(arr.get(i)));
            }
        } else {
            append.add(asInterventionEntry(parsed));
        }
        JSONArray actual = new JSONArray();
        if (StringUtils.hasText(inst.getActualInterventions())) {
            try {
                JSONArray old = JSON.parseArray(inst.getActualInterventions());
                if (old != null) {
                    actual = old;
                }
            } catch (Exception e) {
                log.warn("计划实例[{}] 实际措施存量 JSON 非法, 追加时重建数组: {}", instanceId, e.getMessage());
            }
        }
        actual.addAll(append);
        int n = instanceMapper.update(null, Wrappers.<HisNursingPlanInstance>lambdaUpdate()
                .eq(HisNursingPlanInstance::getId, inst.getId())
                .set(HisNursingPlanInstance::getActualInterventions, actual.toJSONString())
                .set(HisNursingPlanInstance::getUpdateBy, currentUserName()));
        if (n == 0) {
            throw new BizException(409, "计划已变更或不存在, 请刷新后重试");
        }
    }

    /** 评价计划(status 1→2): 记录评价时间与评价结果; 仅执行中的计划可评价。 */
    public void evaluate(Long instanceId, String result) {
        HisNursingPlanInstance inst = requireInstance(instanceId);
        if (!StringUtils.hasText(result)) {
            throw new BizException(400, "评价结果不能为空");
        }
        if (inst.getStatus() != null && inst.getStatus() != STATUS_ACTIVE) {
            throw new BizException(400, "仅执行中的计划可评价(当前状态: " + statusName(inst.getStatus()) + ")");
        }
        int n = instanceMapper.update(null, Wrappers.<HisNursingPlanInstance>lambdaUpdate()
                .eq(HisNursingPlanInstance::getId, inst.getId())
                .set(HisNursingPlanInstance::getStatus, STATUS_EVALUATED)
                .set(HisNursingPlanInstance::getEvaluationTime, LocalDateTime.now())
                .set(HisNursingPlanInstance::getEvaluationResult, result.trim())
                .set(HisNursingPlanInstance::getUpdateBy, currentUserName()));
        if (n == 0) {
            throw new BizException(409, "计划已变更或不存在, 请刷新后重试");
        }
    }

    /** 关闭计划(status→3): 执行中/已评价均可关闭; 已关闭的重复操作报错。 */
    public void closePlan(Long instanceId) {
        HisNursingPlanInstance inst = requireInstance(instanceId);
        if (inst.getStatus() != null && inst.getStatus() == STATUS_CLOSED) {
            throw new BizException(400, "计划已关闭, 无需重复操作");
        }
        int n = instanceMapper.update(null, Wrappers.<HisNursingPlanInstance>lambdaUpdate()
                .eq(HisNursingPlanInstance::getId, inst.getId())
                .set(HisNursingPlanInstance::getStatus, STATUS_CLOSED)
                .set(HisNursingPlanInstance::getUpdateBy, currentUserName()));
        if (n == 0) {
            throw new BizException(409, "计划已变更或不存在, 请刷新后重试");
        }
    }

    /* ================= 触发匹配(内部) ================= */

    /**
     * 匹配触发模板: trigger_scale_code 相等 + 启用 + 评分命中 trigger_score_range;
     * 多个模板命中时取 id 最小者(创建在前优先), 无匹配返回 null。
     */
    private HisNursingPlanTemplate findMatchedTemplate(String scaleCode, BigDecimal score) {
        if (!StringUtils.hasText(scaleCode) || score == null) {
            return null;
        }
        List<HisNursingPlanTemplate> candidates = templateMapper.selectList(
                Wrappers.<HisNursingPlanTemplate>lambdaQuery()
                        .eq(HisNursingPlanTemplate::getTriggerScaleCode, scaleCode)
                        .eq(HisNursingPlanTemplate::getStatus, TEMPLATE_ENABLED)
                        .orderByAsc(HisNursingPlanTemplate::getId));
        for (HisNursingPlanTemplate t : candidates) {
            if (matchesTriggerRange(t.getTriggerScoreRange(), score)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 触发分值区间是否命中: 支持 "min-max"/"min~max"(含端点)、">=x"、">x"、"<=x"、"<x"、精确值 "x";
     * 格式非法视为不命中并告警(不阻断评估流程)。
     */
    static boolean matchesTriggerRange(String range, BigDecimal score) {
        if (!StringUtils.hasText(range) || score == null) {
            return false;
        }
        String r = range.trim();
        try {
            if (r.startsWith(">=")) {
                return score.compareTo(new BigDecimal(r.substring(2).trim())) >= 0;
            }
            if (r.startsWith("<=")) {
                return score.compareTo(new BigDecimal(r.substring(2).trim())) <= 0;
            }
            if (r.startsWith(">")) {
                return score.compareTo(new BigDecimal(r.substring(1).trim())) > 0;
            }
            if (r.startsWith("<")) {
                return score.compareTo(new BigDecimal(r.substring(1).trim())) < 0;
            }
            String sep = r.contains("~") ? "~" : "-";
            if (r.contains(sep)) {
                String[] parts = r.split(Pattern.quote(sep));
                if (parts.length == 2) {
                    return score.compareTo(new BigDecimal(parts[0].trim())) >= 0
                            && score.compareTo(new BigDecimal(parts[1].trim())) <= 0;
                }
            }
            return score.compareTo(new BigDecimal(r)) == 0;
        } catch (NumberFormatException e) {
            log.warn("护理计划模板触发分值区间非法(视为不命中): {}", range);
            return false;
        }
    }

    /* ================= 内部工具 ================= */

    /** 触发配置校验: 配置了分值区间时必须指定触发量表编码, 且区间格式合法。 */
    private static void validateTriggerConfig(String scaleCode, String scoreRange) {
        if (StringUtils.hasText(scoreRange) && !StringUtils.hasText(scaleCode)) {
            throw new BizException(400, "配置触发分值区间时必须指定触发量表编码");
        }
        if (StringUtils.hasText(scoreRange)) {
            validateTriggerRangeFormat(scoreRange);
        }
    }

    /** 触发分值区间格式校验(创建/更新模板时): 与 matchesTriggerRange 同一套格式。 */
    private static void validateTriggerRangeFormat(String range) {
        String r = range.trim();
        boolean ok;
        if (r.startsWith(">=") || r.startsWith("<=")) {
            ok = isNumeric(r.substring(2));
        } else if (r.startsWith(">") || r.startsWith("<")) {
            ok = isNumeric(r.substring(1));
        } else if (r.contains("-")) {
            String[] p = r.split(Pattern.quote("-"), -1);
            ok = p.length == 2 && isNumeric(p[0]) && isNumeric(p[1]);
        } else if (r.contains("~")) {
            String[] p = r.split(Pattern.quote("~"), -1);
            ok = p.length == 2 && isNumeric(p[0]) && isNumeric(p[1]);
        } else {
            ok = isNumeric(r);
        }
        if (!ok) {
            throw new BizException(400, "触发分值区间格式非法: " + r
                    + "(支持 min-max / min~max / >=x / >x / <=x / <x / 精确值)");
        }
    }

    private static boolean isNumeric(String s) {
        if (!StringUtils.hasText(s)) {
            return false;
        }
        try {
            new BigDecimal(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** content 必须为合法 JSON 数组(护理措施列表)。 */
    private static void validateJsonArray(String json, String message) {
        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr == null) {
                throw new BizException(400, message);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, message);
        }
    }

    private static void validateStatus(Integer status) {
        if (status != null && status != 0 && status != 1) {
            throw new BizException(400, "状态无效(0停用 1启用)");
        }
    }

    /** 措施记录元素: 须为 JSON 对象, 缺 time 字段时补记录时间。 */
    private static Object asInterventionEntry(Object element) {
        if (!(element instanceof JSONObject)) {
            throw new BizException(400, "措施内容必须为 JSON 对象(或对象数组)");
        }
        JSONObject entry = (JSONObject) element;
        if (entry.getString("time") == null) {
            entry.put("time", TIME_FMT.format(LocalDateTime.now()));
        }
        return entry;
    }

    private static String pick(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static String statusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case STATUS_ACTIVE:
                return "执行中";
            case STATUS_EVALUATED:
                return "已评价";
            case STATUS_CLOSED:
                return "已关闭";
            default:
                return "未知";
        }
    }

    private HisNursingPlanTemplate requireTemplate(Long id) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        HisNursingPlanTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(404, "护理计划模板不存在");
        }
        return t;
    }

    private HisNursingPlanInstance requireInstance(Long id) {
        if (id == null) {
            throw new BizException(400, "计划实例ID不能为空");
        }
        HisNursingPlanInstance inst = instanceMapper.selectById(id);
        if (inst == null) {
            throw new BizException(404, "护理计划实例不存在");
        }
        requireSameOrg(inst.getOrgId());
        return inst;
    }

    /** 就诊必读校验: 存在 + 归属当前登录机构(平台超管放行), 供实例归属机构取值。 */
    private HisInpVisit requireVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (!u.hasRole(Roles.SUPER_ADMIN) && visit.getOrgId() != null && u.getOrgId() != null
                && !visit.getOrgId().equals(u.getOrgId())) {
            throw new BizException(403, "该就诊不属于当前登录机构, 无权操作");
        }
        return visit;
    }

    /** 写操作机构校验: 记录归属机构须与当前登录机构一致(平台超管放行)。 */
    private void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作护士站数据");
        }
        if (orgId != null && !orgId.equals(u.getOrgId()) && !u.hasRole(Roles.SUPER_ADMIN)) {
            throw new BizException(403, "该护理计划不属于当前登录机构, 无权操作");
        }
    }

    private static LoginUser requireLogin() {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        return u;
    }

    /** 当前登录护士(his_staff.id 优先, 未关联职工时回退用户ID)。 */
    private static Long currentNurseId() {
        LoginUser u = UserContext.get();
        return u == null ? null : (u.getStaffId() != null ? u.getStaffId() : u.getUserId());
    }

    private static String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return lu.getRealName() != null && !lu.getRealName().isEmpty() ? lu.getRealName() : lu.getUsername();
    }
}
