package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.NursingAssessmentDTO;
import com.yb.hi.entity.inpatient.HisInpNursingRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisNursingPlanInstance;
import com.yb.hi.entity.inpatient.HisNursingScaleDef;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.framework.util.SafeJsonTool;
import com.yb.hi.mapper.inpatient.HisInpNursingRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.service.emr.SseEmitterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 护理评估量表服务: 量表定义读取 + 量表评估执行 + 评估历史/评分趋势 + 标准量表种子初始化。
 * 说明:
 * 1) his_nursing_scale_def 量表编码全局唯一(uk_scale_code), 标准临床量表为全局共享行
 *    (tenant_id=0, org_id=0 表全机构通用), 故量表定义读写走 JdbcTemplate 显式 SQL 绕过租户插件
 *    (MyBatis-Plus 查询会被按登录租户过滤, 看不到全局行), SQL 一律显式带 deleted=0;
 * 2) 评估结果落 his_inp_nursing_record(record_type=2 护理评估, 走 MyBatis-Plus 正常租户隔离):
 *    scale_score 总分 / scale_detail 各维度得分 JSON / content 完整快照
 *    {score, scaleCode, scaleName, level, color, answers};
 * 3) 评分命中护理计划模板触发区间(trigger_scale_code + trigger_score_range)时自动创建计划实例,
 *    并回写记录的 plan_template_id(经 @Lazy 注入 NursingPlanService 防循环依赖);
 * 4) 启动种子(ApplicationReadyEvent, 在建表迁移之后执行): 按 scale_code 幂等补种 11 大标准量表
 *    (Braden压疮/Morse跌倒/Barthel ADL/NRS疼痛/Caprini VTE/NRS-2002营养风险/GUSS吞咽/
 *    SAS-SDS心理/保护性约束/烫伤风险/姑息PPS), 判存不含 deleted 条件
 *    (uk_scale_code 含 deleted, 墓碑行仍占键位);
 * 5) 智能触发(P4b-2): 评估风险档位(分级颜色归一 0-3)达阈值(红色系)时自动生成"推荐待确认"护理计划
 *    (autoGeneratePlan, 与评分区间精确命中建执行中计划的路径互补);
 *    风险分级较上次评估发生变化时广播 NURSING_RISK_CHANGE SSE 事件(失败静默不阻断评估)。
 */
@Slf4j
@Service
public class NursingScaleService {

    /** 记录类型: 2护理评估(对应 his_inp_nursing_record.record_type) */
    public static final int TYPE_ASSESSMENT = 2;

    /** SSE 事件名: 护理评估风险分级变化(前端按事件名路由预警看板, 载荷携带 orgId/wardId 供过滤) */
    public static final String EVENT_RISK_CHANGE = "NURSING_RISK_CHANGE";

    /** 智能计划触发阈值: 风险档位≥2(红色系分级: 高危/极高危/重度等)时自动生成推荐护理计划 */
    public static final int THRESHOLD_FOR_PLAN = 2;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisInpNursingRecordMapper recordMapper;
    private final HisInpVisitMapper visitMapper;
    private final JdbcTemplate jdbcTemplate;
    private final NursingPlanService planService;
    private final SseEmitterService sseEmitterService;
    private final SafeJsonTool safeJsonTool;

    public NursingScaleService(HisInpNursingRecordMapper recordMapper, HisInpVisitMapper visitMapper,
                               JdbcTemplate jdbcTemplate, @Lazy NursingPlanService planService,
                               SseEmitterService sseEmitterService, SafeJsonTool safeJsonTool) {
        this.recordMapper = recordMapper;
        this.visitMapper = visitMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.planService = planService;
        this.sseEmitterService = sseEmitterService;
        this.safeJsonTool = safeJsonTool;
    }

    /* ================= 量表定义 ================= */

    /** 量表定义列表(全部启用): 按量表类型、id 升序(入院评估→专科→风险)。 */
    public List<HisNursingScaleDef> listScales() {
        return jdbcTemplate.query(
                "SELECT * FROM his_nursing_scale_def WHERE status = 1 AND deleted = 0 ORDER BY scale_type ASC, id ASC",
                BeanPropertyRowMapper.newInstance(HisNursingScaleDef.class));
    }

    /** 量表详情(含 dimensions 维度定义与 score_interpretation 分级映射), 未启用或不存在返回 404。 */
    public HisNursingScaleDef getScale(String scaleCode) {
        if (!StringUtils.hasText(scaleCode)) {
            throw new BizException(400, "量表编码不能为空");
        }
        List<HisNursingScaleDef> scales = jdbcTemplate.query(
                "SELECT * FROM his_nursing_scale_def WHERE scale_code = ? AND status = 1 AND deleted = 0",
                BeanPropertyRowMapper.newInstance(HisNursingScaleDef.class), scaleCode.trim());
        if (scales.isEmpty()) {
            throw new BizException(404, "量表不存在或未启用: " + scaleCode);
        }
        return scales.get(0);
    }

    /* ================= 量表评估 ================= */

    /**
     * 执行评估: 校验就诊归属 → 加载量表定义 → 由 scaleDetail(各维度答案)计算总分与风险分级 →
     * 落 his_inp_nursing_record(record_type=2) → 命中计划模板触发区间时自动创建计划实例并回写 plan_template_id。
     * 答案格式(scaleDetail JSON 对象, 维度key→答案): 答案可为选项分值(数字)、选项标签(文本)、
     * {"score":分值} 对象; 多选维度(multi=true)传数组(逐项累加)或已汇总的非负数字。
     * scaleDetail 缺失但 scaleScore 有值时信任前端总分(无可解析明细的兼容路径)。
     */
    public HisInpNursingRecord assess(NursingAssessmentDTO dto) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (!StringUtils.hasText(dto.getScaleCode())) {
            throw new BizException(400, "量表编码不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        HisNursingScaleDef scale = getScale(dto.getScaleCode());

        JSONObject answers = parseAnswers(dto.getScaleDetail());
        JSONObject dimScores = new JSONObject();
        int total;
        if (answers != null) {
            total = computeScore(scale, answers, dimScores);
        } else if (dto.getScaleScore() != null) {
            total = dto.getScaleScore().intValue();
        } else {
            throw new BizException(400, "量表明细(各维度答案)与量表评分不能同时为空");
        }
        JSONObject level = matchLevel(scale.getScoreInterpretation(), total);

        HisInpNursingRecord rec = new HisInpNursingRecord();
        rec.setOrgId(visit.getOrgId());
        rec.setInpVisitId(visit.getId());
        rec.setRecordType(TYPE_ASSESSMENT);
        rec.setScaleCode(scale.getScaleCode());
        rec.setScaleScore(BigDecimal.valueOf(total));
        rec.setScaleDetail(dimScores.isEmpty() ? null : dimScores.toJSONString());
        JSONObject content = new JSONObject();
        content.put("score", total);
        content.put("scaleCode", scale.getScaleCode());
        content.put("scaleName", scale.getScaleName());
        content.put("level", level == null ? null : level.getString("level"));
        content.put("color", level == null ? null : level.getString("color"));
        content.put("answers", answers);
        rec.setContent(content.toJSONString());
        rec.setRecordTime(dto.getRecordTime() != null ? dto.getRecordTime() : LocalDateTime.now());
        rec.setNurseId(dto.getNurseId() != null ? dto.getNurseId() : currentNurseId());
        recordMapper.insert(rec);

        // 命中护理计划模板触发区间 → 自动创建计划实例(同一模板同一患者仅保留一个执行中实例)
        try {
            HisNursingPlanInstance inst = planService.autoCreateFromAssessment(
                    visit.getId(), rec.getId(), scale.getScaleCode(), rec.getScaleScore());
            if (inst != null && inst.getTemplateId() != null) {
                recordMapper.update(null, Wrappers.<HisInpNursingRecord>lambdaUpdate()
                        .eq(HisInpNursingRecord::getId, rec.getId())
                        .set(HisInpNursingRecord::getPlanTemplateId, inst.getTemplateId())
                        .set(HisInpNursingRecord::getUpdateBy, currentUserName()));
                rec.setPlanTemplateId(inst.getTemplateId());
            }
        } catch (Exception e) {
            // 评估记录为主数据, 自动建计划失败仅告警不回滚(可手工创建计划)
            log.warn("评估[recordId={}] 自动创建护理计划失败: {}", rec.getId(), e.getMessage());
        }

        // P4b-2 智能触发: 高风险自动生成"推荐待确认"护理计划 + 风险分级变化 SSE 推送(失败仅告警, 不阻断评估)
        try {
            String levelName = level == null ? null : level.getString("level");
            int riskRank = riskRankOf(level == null ? null : level.getString("color"));
            if (riskRank >= THRESHOLD_FOR_PLAN) {
                HisNursingPlanInstance recommended = planService.autoGeneratePlan(visit.getId(),
                        scale.getScaleCode(), riskRank,
                        scale.getScaleName() + " " + total + "分" + (levelName == null ? "" : " " + levelName));
                if (recommended != null && recommended.getTemplateId() != null && rec.getPlanTemplateId() == null) {
                    recordMapper.update(null, Wrappers.<HisInpNursingRecord>lambdaUpdate()
                            .eq(HisInpNursingRecord::getId, rec.getId())
                            .set(HisInpNursingRecord::getPlanTemplateId, recommended.getTemplateId())
                            .set(HisInpNursingRecord::getUpdateBy, currentUserName()));
                    rec.setPlanTemplateId(recommended.getTemplateId());
                }
            }
            String prevLevel = previousLevelName(visit.getId(), scale.getScaleCode(), rec);
            if (levelName != null && prevLevel != null && !prevLevel.equals(levelName)) {
                publishRiskChange(visit, rec, scale, total, prevLevel, levelName,
                        level.getString("color"), riskRank);
            }
        } catch (Exception e) {
            log.warn("评估[recordId={}] 智能计划推荐/风险分级变化推送失败(不影响评估): {}",
                    rec.getId(), e.getMessage());
        }
        return rec;
    }

    /** 评估历史(按就诊+量表): record_type=2, 记录时间倒序(最新在前)。 */
    public List<HisInpNursingRecord> getAssessmentHistory(Long visitId, String scaleCode) {
        requireVisit(visitId);
        return recordMapper.selectList(Wrappers.<HisInpNursingRecord>lambdaQuery()
                .eq(HisInpNursingRecord::getInpVisitId, visitId)
                .eq(HisInpNursingRecord::getRecordType, TYPE_ASSESSMENT)
                .eq(StringUtils.hasText(scaleCode), HisInpNursingRecord::getScaleCode, scaleCode)
                .orderByDesc(HisInpNursingRecord::getRecordTime)
                .orderByDesc(HisInpNursingRecord::getId));
    }

    /** 评分趋势: record_type=2 且有评分的记录按时间升序输出 {time, score}(折线图数据源)。 */
    public List<Map<String, Object>> getScoreTrend(Long visitId, String scaleCode) {
        requireVisit(visitId);
        List<HisInpNursingRecord> recs = recordMapper.selectList(Wrappers.<HisInpNursingRecord>lambdaQuery()
                .eq(HisInpNursingRecord::getInpVisitId, visitId)
                .eq(HisInpNursingRecord::getRecordType, TYPE_ASSESSMENT)
                .eq(StringUtils.hasText(scaleCode), HisInpNursingRecord::getScaleCode, scaleCode)
                .isNotNull(HisInpNursingRecord::getScaleScore)
                .orderByAsc(HisInpNursingRecord::getRecordTime)
                .orderByAsc(HisInpNursingRecord::getId));
        List<Map<String, Object>> out = new ArrayList<>(recs.size());
        for (HisInpNursingRecord r : recs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", r.getRecordTime() == null ? null : TIME_FMT.format(r.getRecordTime()));
            row.put("score", r.getScaleScore());
            out.add(row);
        }
        return out;
    }

    /* ================= 智能触发(P4b-2 内部) ================= */

    /** 分级颜色 → 风险档位: 蓝绿(无险/低危)0 橙(中危/轻度)1 红(高危/中度)2 深红(极高危/重度/濒死)3; 未分级/未知 -1。 */
    static int riskRankOf(String color) {
        if (!StringUtils.hasText(color)) {
            return -1;
        }
        switch (color.trim().toUpperCase()) {
            case "#409EFF":
            case "#67C23A":
                return 0;
            case "#E6A23C":
                return 1;
            case "#F56C6C":
                return 2;
            case "#B71C1C":
                return 3;
            default:
                return -1;
        }
    }

    /**
     * 该量表最近一次评估的分级(排除当前记录, 且记录时间不晚于当前记录):
     * 取 content 快照的 level 字段; 无历史/快照不可解析返回 null(回补历史评估不触发变化推送)。
     */
    private String previousLevelName(Long visitId, String scaleCode, HisInpNursingRecord current) {
        List<HisInpNursingRecord> history = recordMapper.selectList(Wrappers.<HisInpNursingRecord>lambdaQuery()
                .eq(HisInpNursingRecord::getInpVisitId, visitId)
                .eq(HisInpNursingRecord::getRecordType, TYPE_ASSESSMENT)
                .eq(HisInpNursingRecord::getScaleCode, scaleCode)
                .ne(HisInpNursingRecord::getId, current.getId())
                .le(HisInpNursingRecord::getRecordTime, current.getRecordTime())
                .orderByDesc(HisInpNursingRecord::getRecordTime)
                .orderByDesc(HisInpNursingRecord::getId));
        if (history.isEmpty() || !StringUtils.hasText(history.get(0).getContent())) {
            return null;
        }
        try {
            return JSON.parseObject(history.get(0).getContent()).getString("level");
        } catch (Exception e) {
            return null;
        }
    }

    /** 风险分级变化 SSE 广播(事件名 NURSING_RISK_CHANGE, 载荷携带 orgId/wardId 供前端过滤; 失败静默不影响评估)。 */
    private void publishRiskChange(HisInpVisit visit, HisInpNursingRecord rec, HisNursingScaleDef scale,
                                   int score, String oldLevel, String newLevel, String newColor, int riskRank) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inpVisitId", visit.getId());
            payload.put("patientId", visit.getPatientId());
            payload.put("patientName", patientNameOf(visit.getPatientId()));
            payload.put("orgId", visit.getOrgId());
            payload.put("wardId", visit.getWardId());
            payload.put("scaleCode", scale.getScaleCode());
            payload.put("scaleName", scale.getScaleName());
            payload.put("score", score);
            payload.put("oldLevel", oldLevel);
            payload.put("newLevel", newLevel);
            payload.put("newColor", newColor);
            payload.put("riskRank", riskRank);
            payload.put("recordTime", rec.getRecordTime() == null ? null : TIME_FMT.format(rec.getRecordTime()));
            int sent = sseEmitterService.broadcast(EVENT_RISK_CHANGE, safeJsonTool.toJson(payload));
            log.info("评估风险分级变化推送: visitId={}, scale={}, {} → {}, SSE送达连接数={}",
                    visit.getId(), scale.getScaleCode(), oldLevel, newLevel, sent);
        } catch (Exception e) {
            log.warn("风险分级变化 SSE 推送失败(不影响评估): visitId={}, 原因={}", visit.getId(), e.getMessage());
        }
    }

    /** 患者姓名回查(his_patient.name, 查不到返回 null; 仅用于 SSE 载荷展示)。 */
    private String patientNameOf(Long patientId) {
        if (patientId == null) {
            return null;
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT name FROM his_patient WHERE id = ?", String.class, patientId);
            return names.isEmpty() ? null : names.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /* ================= 评分计算(内部) ================= */

    /** 解析量表明细为答案 JSON 对象; 空白返回 null, 非法/非对象报 400。 */
    private static JSONObject parseAnswers(String scaleDetail) {
        if (!StringUtils.hasText(scaleDetail)) {
            return null;
        }
        JSONObject answers;
        try {
            answers = JSON.parseObject(scaleDetail);
        } catch (Exception e) {
            throw new BizException(400, "量表明细必须为合法 JSON 对象");
        }
        if (answers == null) {
            throw new BizException(400, "量表明细必须为 JSON 对象");
        }
        return answers;
    }

    /**
     * 遍历量表 dimensions 计算总分: 每个维度取答案对应选项的 score 累加
     * (multi 维度对数组答案逐项累加), 各维度得分写入 dimScores(key→score)。
     */
    private int computeScore(HisNursingScaleDef scale, JSONObject answers, JSONObject dimScores) {
        JSONArray dims;
        try {
            dims = JSON.parseArray(scale.getDimensions());
        } catch (Exception e) {
            dims = null;
        }
        if (dims == null || dims.isEmpty()) {
            throw new BizException(500, "量表[" + scale.getScaleCode() + "] 未配置评估维度");
        }
        int total = 0;
        for (int i = 0; i < dims.size(); i++) {
            JSONObject dim = dims.getJSONObject(i);
            String key = dim.getString("key");
            String name = dim.getString("name");
            JSONArray options = dim.getJSONArray("options");
            if (!StringUtils.hasText(key) || options == null || options.isEmpty()) {
                throw new BizException(500, "量表[" + scale.getScaleCode() + "] 维度定义缺少 key/options");
            }
            boolean multi = dim.getBooleanValue("multi");
            int score = multi
                    ? resolveMultiScore(scale.getScaleCode(), name, options, answers.get(key))
                    : resolveSingleScore(scale.getScaleCode(), name, options, answers.get(key));
            dimScores.put(key, score);
            total += score;
        }
        return total;
    }

    /** 单选维度: 答案须命中唯一选项(分值/标签/对象score), 未作答报 400。 */
    private static int resolveSingleScore(String scaleCode, String dimName, JSONArray options, Object answer) {
        if (answer == null) {
            throw new BizException(400, "量表[" + scaleCode + "] 评估答案不完整: 维度[" + dimName + "]未作答");
        }
        Integer score = resolveOptionScore(options, answer);
        if (score == null) {
            throw new BizException(400, "量表[" + scaleCode + "] 评估答案无效: 维度[" + dimName + "]的答案不在选项内");
        }
        return score;
    }

    /** 多选维度: 数组答案逐项按选项累加; 未作答计 0 分; 单值优先按选项匹配, 非负数字视为前端已汇总分值。 */
    private static int resolveMultiScore(String scaleCode, String dimName, JSONArray options, Object answer) {
        if (answer == null) {
            return 0;
        }
        if (answer instanceof JSONArray) {
            int sum = 0;
            JSONArray arr = (JSONArray) answer;
            for (int i = 0; i < arr.size(); i++) {
                Integer score = resolveOptionScore(options, arr.get(i));
                if (score == null) {
                    throw new BizException(400, "量表[" + scaleCode + "] 评估答案无效: 维度[" + dimName + "]的选项不在可选范围");
                }
                sum += score;
            }
            return sum;
        }
        Integer score = resolveOptionScore(options, answer);
        if (score != null) {
            return score;
        }
        if (answer instanceof Number) {
            int v = ((Number) answer).intValue();
            if (v >= 0) {
                return v;
            }
        }
        throw new BizException(400, "量表[" + scaleCode + "] 评估答案无效: 维度[" + dimName + "]的答案不在选项内");
    }

    /** 解析单个答案为选项分值: 数字按选项分值匹配 / 文本先按标签再按数字匹配 / 对象取 score 字段匹配; 未命中返回 null。 */
    private static Integer resolveOptionScore(JSONArray options, Object answer) {
        if (answer instanceof JSONObject) {
            Object s = ((JSONObject) answer).get("score");
            return s instanceof Number ? matchOptionScore(options, ((Number) s).intValue()) : null;
        }
        if (answer instanceof Number) {
            return matchOptionScore(options, ((Number) answer).intValue());
        }
        if (answer instanceof String) {
            String label = ((String) answer).trim();
            for (int i = 0; i < options.size(); i++) {
                JSONObject opt = options.getJSONObject(i);
                if (label.equals(opt.getString("label"))) {
                    return opt.getIntValue("score");
                }
            }
            try {
                return matchOptionScore(options, Integer.parseInt(label));
            } catch (NumberFormatException ignore) {
                // 非数字文本且不匹配任何标签
            }
        }
        return null;
    }

    /** 分值须与某一选项的 score 完全一致, 命中返回该分值, 否则返回 null。 */
    private static Integer matchOptionScore(JSONArray options, int score) {
        for (int i = 0; i < options.size(); i++) {
            JSONObject opt = options.getJSONObject(i);
            if (opt.getIntValue("score") == score) {
                return score;
            }
        }
        return null;
    }

    /** 总分匹配分数→风险映射(score_interpretation): 命中返回区间对象(含 level/color), 无映射或未命中返回 null。 */
    private static JSONObject matchLevel(String interpretation, int total) {
        if (!StringUtils.hasText(interpretation)) {
            return null;
        }
        JSONArray arr;
        try {
            arr = JSON.parseArray(interpretation);
        } catch (Exception e) {
            return null;
        }
        if (arr == null) {
            return null;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject seg = arr.getJSONObject(i);
            Integer min = seg.getInteger("min");
            Integer max = seg.getInteger("max");
            if (min != null && max != null && total >= min && total <= max) {
                return seg;
            }
        }
        return null;
    }

    /* ================= 启动种子(11大标准量表) ================= */

    /**
     * 量表种子初始化(ApplicationReadyEvent, 在 DictSchemaMigration 建表之后执行):
     * 按 scale_code 幂等补种(判存不含 deleted, 墓碑行仍占 uk_scale_code 键位);
     * 全局共享行 tenant_id=0/org_id=0; 表未建或库异常时告警跳过(下次启动补种)。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedScaleDefs() {
        int added = 0;
        try {
            for (Object[] s : SCALE_SEEDS) {
                String code = (String) s[0];
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM his_nursing_scale_def WHERE scale_code = ?",
                        Integer.class, code);
                if (exists != null && exists > 0) {
                    // 防御性修复: 若已有行 dimensions 为空, UPDATE 补写
                    Integer emptyDims = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM his_nursing_scale_def WHERE scale_code = ? AND (dimensions IS NULL OR dimensions = '')",
                            Integer.class, code);
                    if (emptyDims != null && emptyDims > 0) {
                        jdbcTemplate.update(
                                "UPDATE his_nursing_scale_def SET dimensions = ?, score_interpretation = ?, update_time = NOW() WHERE scale_code = ? AND (dimensions IS NULL OR dimensions = '')",
                                s[3], s[4], code);
                        log.info("护理量表种子: 补写 {} 的 dimensions/score_interpretation", code);
                    }
                    continue;
                }
                jdbcTemplate.update(
                        "INSERT INTO his_nursing_scale_def (tenant_id, org_id, scale_code, scale_name, scale_type,"
                                + " dimensions, score_interpretation, required_frequency, status,"
                                + " create_by, create_time, update_by, update_time, deleted)"
                                + " VALUES (0, 0, ?, ?, ?, ?, ?, ?, 1, 'nursing-seed', NOW(), 'nursing-seed', NOW(), 0)",
                        code, s[1], s[2], s[3], s[4], s[5]);
                added++;
            }
            if (added > 0) {
                log.info("护理评估量表种子: 补种 {} 个标准量表({} 已存在跳过)", added, SCALE_SEEDS.length - added);
            }
        } catch (Exception e) {
            log.warn("护理评估量表种子初始化跳过(可能表未建, 本次补种 {} 个): {}", added, e.getMessage());
        }
    }

    /* ---------- Braden 压疮评估(6维度, 6-23分, 分值越高风险越低) ---------- */
    private static final String BRADEN_DIMENSIONS = "["
            + "{\"key\":\"sensory\",\"name\":\"感觉\",\"options\":["
            + "{\"label\":\"完全受限\",\"score\":1},{\"label\":\"非常受限\",\"score\":2},"
            + "{\"label\":\"轻微受限\",\"score\":3},{\"label\":\"未受损\",\"score\":4}]},"
            + "{\"key\":\"moisture\",\"name\":\"潮湿\",\"options\":["
            + "{\"label\":\"持续潮湿\",\"score\":1},{\"label\":\"非常潮湿\",\"score\":2},"
            + "{\"label\":\"偶尔潮湿\",\"score\":3},{\"label\":\"很少潮湿\",\"score\":4}]},"
            + "{\"key\":\"activity\",\"name\":\"活动\",\"options\":["
            + "{\"label\":\"卧床\",\"score\":1},{\"label\":\"坐椅\",\"score\":2},"
            + "{\"label\":\"偶尔行走\",\"score\":3},{\"label\":\"经常行走\",\"score\":4}]},"
            + "{\"key\":\"mobility\",\"name\":\"移动\",\"options\":["
            + "{\"label\":\"完全不能\",\"score\":1},{\"label\":\"非常受限\",\"score\":2},"
            + "{\"label\":\"轻微受限\",\"score\":3},{\"label\":\"不受限\",\"score\":4}]},"
            + "{\"key\":\"nutrition\",\"name\":\"营养\",\"options\":["
            + "{\"label\":\"非常差\",\"score\":1},{\"label\":\"可能不足\",\"score\":2},"
            + "{\"label\":\"充足\",\"score\":3},{\"label\":\"丰富\",\"score\":4}]},"
            + "{\"key\":\"friction\",\"name\":\"摩擦力和剪切力\",\"options\":["
            + "{\"label\":\"存在问题\",\"score\":1},{\"label\":\"潜在问题\",\"score\":2},"
            + "{\"label\":\"无明显问题\",\"score\":3}]}"
            + "]";
    private static final String BRADEN_LEVELS = "["
            + "{\"min\":6,\"max\":12,\"level\":\"高危\",\"color\":\"#F56C6C\"},"
            + "{\"min\":13,\"max\":14,\"level\":\"中危\",\"color\":\"#E6A23C\"},"
            + "{\"min\":15,\"max\":18,\"level\":\"低危\",\"color\":\"#67C23A\"},"
            + "{\"min\":19,\"max\":23,\"level\":\"无危险\",\"color\":\"#409EFF\"}"
            + "]";

    /* ---------- Morse 跌倒评估(6维度, 0-125分, 分值越高风险越高) ---------- */
    private static final String MORSE_DIMENSIONS = "["
            + "{\"key\":\"fallHistory\",\"name\":\"跌倒史\",\"options\":["
            + "{\"label\":\"无\",\"score\":0},{\"label\":\"有\",\"score\":25}]},"
            + "{\"key\":\"secDiagnosis\",\"name\":\"合并症(≥2个医学诊断)\",\"options\":["
            + "{\"label\":\"无\",\"score\":0},{\"label\":\"有\",\"score\":15}]},"
            + "{\"key\":\"ambulatoryAid\",\"name\":\"步行辅助\",\"options\":["
            + "{\"label\":\"无/卧床/轮椅\",\"score\":0},{\"label\":\"拐杖/助行器\",\"score\":15},"
            + "{\"label\":\"扶家具行走\",\"score\":30}]},"
            + "{\"key\":\"ivTherapy\",\"name\":\"静脉治疗/肝素锁\",\"options\":["
            + "{\"label\":\"无\",\"score\":0},{\"label\":\"有\",\"score\":20}]},"
            + "{\"key\":\"gait\",\"name\":\"步态\",\"options\":["
            + "{\"label\":\"正常/卧床/轮椅\",\"score\":0},{\"label\":\"虚弱\",\"score\":10},"
            + "{\"label\":\"障碍\",\"score\":20}]},"
            + "{\"key\":\"mentalStatus\",\"name\":\"精神状态\",\"options\":["
            + "{\"label\":\"能正确评估自己活动能力\",\"score\":0},"
            + "{\"label\":\"高估自己活动能力/忘记限制\",\"score\":15}]}"
            + "]";
    private static final String MORSE_LEVELS = "["
            + "{\"min\":0,\"max\":24,\"level\":\"低危\",\"color\":\"#67C23A\"},"
            + "{\"min\":25,\"max\":44,\"level\":\"中危\",\"color\":\"#E6A23C\"},"
            + "{\"min\":45,\"max\":125,\"level\":\"高危\",\"color\":\"#F56C6C\"}"
            + "]";

    /* ---------- Barthel ADL 日常生活能力评估(10维度, 0-100分, 分值越高自理能力越好) ---------- */
    private static final String BARTHEL_DIMENSIONS = "["
            + "{\"key\":\"feeding\",\"name\":\"进食\",\"options\":["
            + "{\"label\":\"独立\",\"score\":10},{\"label\":\"需要帮助\",\"score\":5},"
            + "{\"label\":\"完全依赖\",\"score\":0}]},"
            + "{\"key\":\"bathing\",\"name\":\"洗澡\",\"options\":["
            + "{\"label\":\"独立\",\"score\":5},{\"label\":\"需要帮助\",\"score\":0}]},"
            + "{\"key\":\"grooming\",\"name\":\"修饰\",\"options\":["
            + "{\"label\":\"独立\",\"score\":5},{\"label\":\"需要帮助\",\"score\":0}]},"
            + "{\"key\":\"dressing\",\"name\":\"穿衣\",\"options\":["
            + "{\"label\":\"独立\",\"score\":10},{\"label\":\"需要帮助\",\"score\":5},"
            + "{\"label\":\"完全依赖\",\"score\":0}]},"
            + "{\"key\":\"bowels\",\"name\":\"控制大便\",\"options\":["
            + "{\"label\":\"可控制\",\"score\":10},{\"label\":\"偶尔失控\",\"score\":5},"
            + "{\"label\":\"失控\",\"score\":0}]},"
            + "{\"key\":\"bladder\",\"name\":\"控制小便\",\"options\":["
            + "{\"label\":\"可控制\",\"score\":10},{\"label\":\"偶尔失控\",\"score\":5},"
            + "{\"label\":\"失控\",\"score\":0}]},"
            + "{\"key\":\"toilet\",\"name\":\"如厕\",\"options\":["
            + "{\"label\":\"独立\",\"score\":10},{\"label\":\"需要帮助\",\"score\":5},"
            + "{\"label\":\"完全依赖\",\"score\":0}]},"
            + "{\"key\":\"transfer\",\"name\":\"床椅转移\",\"options\":["
            + "{\"label\":\"独立\",\"score\":15},{\"label\":\"少量帮助\",\"score\":10},"
            + "{\"label\":\"大量帮助\",\"score\":5},{\"label\":\"完全依赖\",\"score\":0}]},"
            + "{\"key\":\"mobility\",\"name\":\"平地行走\",\"options\":["
            + "{\"label\":\"独立行走45m\",\"score\":15},{\"label\":\"需要帮助\",\"score\":10},"
            + "{\"label\":\"轮椅独立\",\"score\":5},{\"label\":\"不能行走\",\"score\":0}]},"
            + "{\"key\":\"stairs\",\"name\":\"上下楼梯\",\"options\":["
            + "{\"label\":\"独立\",\"score\":10},{\"label\":\"需要帮助\",\"score\":5},"
            + "{\"label\":\"不能\",\"score\":0}]}"
            + "]";
    private static final String BARTHEL_LEVELS = "["
            + "{\"min\":0,\"max\":20,\"level\":\"极严重功能障碍\",\"color\":\"#F56C6C\"},"
            + "{\"min\":21,\"max\":40,\"level\":\"严重功能障碍\",\"color\":\"#F56C6C\"},"
            + "{\"min\":41,\"max\":60,\"level\":\"中度功能障碍\",\"color\":\"#E6A23C\"},"
            + "{\"min\":61,\"max\":99,\"level\":\"轻度功能障碍\",\"color\":\"#67C23A\"},"
            + "{\"min\":100,\"max\":100,\"level\":\"生活自理\",\"color\":\"#409EFF\"}"
            + "]";

    /* ---------- NRS 疼痛数字评估(单维度 0-10 分) ---------- */
    private static final String NRS_DIMENSIONS = "["
            + "{\"key\":\"painScore\",\"name\":\"疼痛程度(0-10分)\",\"options\":["
            + "{\"label\":\"0分-无痛\",\"score\":0},{\"label\":\"1分\",\"score\":1},"
            + "{\"label\":\"2分\",\"score\":2},{\"label\":\"3分\",\"score\":3},"
            + "{\"label\":\"4分\",\"score\":4},{\"label\":\"5分\",\"score\":5},"
            + "{\"label\":\"6分\",\"score\":6},{\"label\":\"7分\",\"score\":7},"
            + "{\"label\":\"8分\",\"score\":8},{\"label\":\"9分\",\"score\":9},"
            + "{\"label\":\"10分-剧痛\",\"score\":10}]}"
            + "]";
    private static final String NRS_LEVELS = "["
            + "{\"min\":0,\"max\":0,\"level\":\"无痛\",\"color\":\"#409EFF\"},"
            + "{\"min\":1,\"max\":3,\"level\":\"轻度疼痛\",\"color\":\"#67C23A\"},"
            + "{\"min\":4,\"max\":6,\"level\":\"中度疼痛\",\"color\":\"#E6A23C\"},"
            + "{\"min\":7,\"max\":10,\"level\":\"重度疼痛\",\"color\":\"#F56C6C\"}"
            + "]";

    /* ---------- Caprini VTE 风险评估(多选累加模式, multi=true 维度按选项求和) ---------- */
    private static final String CAPRINI_DIMENSIONS = "["
            + "{\"key\":\"age\",\"name\":\"年龄\",\"multi\":false,\"options\":["
            + "{\"label\":\"≤40岁\",\"score\":0},{\"label\":\"41-60岁\",\"score\":1},"
            + "{\"label\":\"61-74岁\",\"score\":2},{\"label\":\"≥75岁\",\"score\":3}]},"
            + "{\"key\":\"surgery\",\"name\":\"手术\",\"multi\":false,\"options\":["
            + "{\"label\":\"无手术\",\"score\":0},{\"label\":\"小手术\",\"score\":1},"
            + "{\"label\":\"大手术(>45min)\",\"score\":2},{\"label\":\"髋/膝/腿骨折手术\",\"score\":5}]},"
            + "{\"key\":\"riskFactors\",\"name\":\"风险因素(可多选)\",\"multi\":true,\"options\":["
            + "{\"label\":\"下肢水肿\",\"score\":1},{\"label\":\"静脉曲张\",\"score\":1},"
            + "{\"label\":\"肥胖(BMI>25)\",\"score\":1},{\"label\":\"卧床>72h\",\"score\":2},"
            + "{\"label\":\"中心静脉通路\",\"score\":2},{\"label\":\"恶性肿瘤\",\"score\":3},"
            + "{\"label\":\"VTE病史\",\"score\":3},{\"label\":\"VTE家族史\",\"score\":3},"
            + "{\"label\":\"凝血因子异常\",\"score\":3}]}"
            + "]";
    private static final String CAPRINI_LEVELS = "["
            + "{\"min\":0,\"max\":0,\"level\":\"低危\",\"color\":\"#409EFF\"},"
            + "{\"min\":1,\"max\":2,\"level\":\"中危\",\"color\":\"#67C23A\"},"
            + "{\"min\":3,\"max\":4,\"level\":\"高危\",\"color\":\"#E6A23C\"},"
            + "{\"min\":5,\"max\":99,\"level\":\"极高危\",\"color\":\"#F56C6C\"}"
            + "]";

    /* ---------- NRS-2002 营养风险筛查(3维度, 0-7分, ≥3分提示有营养风险) ---------- */
    private static final String NRS2002_DIMENSIONS = "["
            + "{\"key\":\"disease\",\"name\":\"疾病严重程度\",\"options\":["
            + "{\"label\":\"0分-正常营养需要\",\"score\":0},"
            + "{\"label\":\"1分-髋骨折/慢性病急性加重/肝硬化/COPD/血液透析/糖尿病/肿瘤\",\"score\":1},"
            + "{\"label\":\"2分-腹部大手术/脑卒中/重症肺炎/血液系统恶性肿瘤\",\"score\":2},"
            + "{\"label\":\"3分-颅脑损伤/骨髓移植/ICU患者(APACHEⅡ>10分)\",\"score\":3}]},"
            + "{\"key\":\"nutrition\",\"name\":\"营养状况\",\"options\":["
            + "{\"label\":\"0分-正常营养状态\",\"score\":0},"
            + "{\"label\":\"1分-3个月内体重下降>5%或前1周进食量减少25%~50%\",\"score\":1},"
            + "{\"label\":\"2分-2个月内体重下降>5%或BMI 18.5~20.5伴一般状况差\",\"score\":2},"
            + "{\"label\":\"3分-1个月内体重下降>5%或BMI<18.5或前1周进食量减少75%以上\",\"score\":3}]},"
            + "{\"key\":\"age\",\"name\":\"年龄(≥70岁加1分)\",\"options\":["
            + "{\"label\":\"<70岁\",\"score\":0},{\"label\":\"≥70岁\",\"score\":1}]}"
            + "]";
    private static final String NRS2002_LEVELS = "["
            + "{\"min\":0,\"max\":2,\"level\":\"无营养风险\",\"color\":\"#409EFF\"},"
            + "{\"min\":3,\"max\":3,\"level\":\"有营养风险\",\"color\":\"#E6A23C\"},"
            + "{\"min\":4,\"max\":7,\"level\":\"高营养风险\",\"color\":\"#F56C6C\"}"
            + "]";

    /* ---------- GUSS 吞咽障碍筛查(3维度, 0-20分, ≤14分提示吞咽障碍) ---------- */
    private static final String SWALLOW_DIMENSIONS = "["
            + "{\"key\":\"indirect\",\"name\":\"间接吞咽试验(意识/咳嗽/吞咽尝试/流涎/舌运动/声音)\",\"options\":["
            + "{\"label\":\"正常(清醒/咳嗽有力/发音清亮)\",\"score\":5},"
            + "{\"label\":\"轻度异常(咳嗽反射减弱)\",\"score\":3},"
            + "{\"label\":\"明显异常(嗜睡/咳嗽微弱/流涎)\",\"score\":1},"
            + "{\"label\":\"严重异常(昏迷或无吞咽咳嗽反射)\",\"score\":0}]},"
            + "{\"key\":\"waterTrial\",\"name\":\"吞咽尝试(水试验)\",\"options\":["
            + "{\"label\":\"顺利咽下无呛咳\",\"score\":5},"
            + "{\"label\":\"咽下缓慢伴轻微呛咳\",\"score\":3},"
            + "{\"label\":\"反复呛咳或需多次尝试\",\"score\":1},"
            + "{\"label\":\"不能完成或呛咳剧烈\",\"score\":0}]},"
            + "{\"key\":\"direct\",\"name\":\"直接吞咽试验(半固体/液体/固体)\",\"options\":["
            + "{\"label\":\"三种性状均可顺利吞咽\",\"score\":10},"
            + "{\"label\":\"仅一种性状吞咽困难\",\"score\":7},"
            + "{\"label\":\"两种性状吞咽困难\",\"score\":4},"
            + "{\"label\":\"三种性状均困难或不宜经口进食\",\"score\":0}]}"
            + "]";
    private static final String SWALLOW_LEVELS = "["
            + "{\"min\":20,\"max\":20,\"level\":\"吞咽功能正常\",\"color\":\"#409EFF\"},"
            + "{\"min\":15,\"max\":19,\"level\":\"轻度吞咽风险\",\"color\":\"#67C23A\"},"
            + "{\"min\":10,\"max\":14,\"level\":\"吞咽障碍\",\"color\":\"#E6A23C\"},"
            + "{\"min\":0,\"max\":9,\"level\":\"重度吞咽障碍\",\"color\":\"#F56C6C\"}"
            + "]";

    /* ---------- SAS/SDS 心理评估(焦虑/抑郁自评, 20条目×4级, 粗分20-80, 标准分=粗分×1.25) ---------- */
    /** 20条目共用4级作答选项: 没有或很少时间(1)/少部分时间(2)/相当多时间(3)/绝大部分或全部时间(4) */
    private static final String PSYCH_ITEM_OPTIONS = "["
            + "{\"label\":\"没有或很少时间\",\"score\":1},{\"label\":\"少部分时间\",\"score\":2},"
            + "{\"label\":\"相当多时间\",\"score\":3},{\"label\":\"绝大部分或全部时间\",\"score\":4}]";
    private static final String PSYCH_DIMENSIONS = "["
            + "{\"key\":\"item01\",\"name\":\"焦虑\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item02\",\"name\":\"惊恐\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item03\",\"name\":\"害怕\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item04\",\"name\":\"发疯感\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item05\",\"name\":\"不幸预感\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item06\",\"name\":\"手足颤抖\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item07\",\"name\":\"躯体疼痛\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item08\",\"name\":\"乏力\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item09\",\"name\":\"静坐不能\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item10\",\"name\":\"心悸\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item11\",\"name\":\"头昏\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item12\",\"name\":\"晕厥感\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item13\",\"name\":\"呼吸困难\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item14\",\"name\":\"手足刺痛\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item15\",\"name\":\"胃痛/消化不良\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item16\",\"name\":\"尿意频数\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item17\",\"name\":\"多汗\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item18\",\"name\":\"面部潮红\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item19\",\"name\":\"睡眠障碍\",\"options\":" + PSYCH_ITEM_OPTIONS + "},"
            + "{\"key\":\"item20\",\"name\":\"噩梦\",\"options\":" + PSYCH_ITEM_OPTIONS + "}"
            + "]";
    private static final String PSYCH_LEVELS = "["
            + "{\"min\":20,\"max\":39,\"level\":\"无明显焦虑/抑郁\",\"color\":\"#409EFF\"},"
            + "{\"min\":40,\"max\":47,\"level\":\"轻度焦虑/抑郁(标准分50~59)\",\"color\":\"#E6A23C\"},"
            + "{\"min\":48,\"max\":55,\"level\":\"中度焦虑/抑郁(标准分60~69)\",\"color\":\"#F56C6C\"},"
            + "{\"min\":56,\"max\":80,\"level\":\"重度焦虑/抑郁(标准分≥70)\",\"color\":\"#B71C1C\"}"
            + "]";

    /* ---------- 保护性约束评估(4维度, 0-10分, 分级判定需约束/不需约束) ---------- */
    private static final String RESTRAINT_DIMENSIONS = "["
            + "{\"key\":\"consciousness\",\"name\":\"意识状态\",\"options\":["
            + "{\"label\":\"清醒且配合治疗\",\"score\":0},"
            + "{\"label\":\"嗜睡或淡漠\",\"score\":1},"
            + "{\"label\":\"意识模糊伴躁动\",\"score\":2},"
            + "{\"label\":\"谵妄\",\"score\":3}]},"
            + "{\"key\":\"fallRisk\",\"name\":\"跌倒风险(Morse)\",\"options\":["
            + "{\"label\":\"低危(<25分)\",\"score\":0},"
            + "{\"label\":\"中危(25~44分)\",\"score\":1},"
            + "{\"label\":\"高危(≥45分)\",\"score\":2}]},"
            + "{\"key\":\"tubeRisk\",\"name\":\"管道风险\",\"options\":["
            + "{\"label\":\"无管道或普通管道\",\"score\":0},"
            + "{\"label\":\"1条重要管道\",\"score\":1},"
            + "{\"label\":\"≥2条重要管道(气管插管/深静脉/引流管等)\",\"score\":2}]},"
            + "{\"key\":\"behavior\",\"name\":\"行为评估\",\"options\":["
            + "{\"label\":\"安静合作\",\"score\":0},"
            + "{\"label\":\"偶有躁动可安抚\",\"score\":1},"
            + "{\"label\":\"持续躁动或有自行拔管倾向\",\"score\":2},"
            + "{\"label\":\"攻击性或自伤行为\",\"score\":3}]}"
            + "]";
    private static final String RESTRAINT_LEVELS = "["
            + "{\"min\":0,\"max\":2,\"level\":\"不需约束\",\"color\":\"#409EFF\"},"
            + "{\"min\":3,\"max\":5,\"level\":\"加强监护(慎约束)\",\"color\":\"#E6A23C\"},"
            + "{\"min\":6,\"max\":10,\"level\":\"需保护性约束(须医嘱+知情同意)\",\"color\":\"#F56C6C\"}"
            + "]";

    /* ---------- 住院患者烫伤风险评估(4维度, 0-12分, ≥6分高危) ---------- */
    private static final String BURN_DIMENSIONS = "["
            + "{\"key\":\"age\",\"name\":\"年龄\",\"options\":["
            + "{\"label\":\"婴幼儿(<3岁)或高龄(≥70岁)\",\"score\":3},"
            + "{\"label\":\"儿童(3~12岁)或老年(60~69岁)\",\"score\":2},"
            + "{\"label\":\"青少年/成人\",\"score\":0}]},"
            + "{\"key\":\"consciousness\",\"name\":\"意识\",\"options\":["
            + "{\"label\":\"昏迷或意识障碍\",\"score\":3},"
            + "{\"label\":\"嗜睡或意识模糊\",\"score\":2},"
            + "{\"label\":\"清醒伴认知障碍(痴呆)\",\"score\":1},"
            + "{\"label\":\"清醒\",\"score\":0}]},"
            + "{\"key\":\"skinSense\",\"name\":\"皮肤感觉\",\"options\":["
            + "{\"label\":\"感觉丧失(糖尿病/神经病变等)\",\"score\":3},"
            + "{\"label\":\"感觉迟钝\",\"score\":2},"
            + "{\"label\":\"感觉减退\",\"score\":1},"
            + "{\"label\":\"感觉正常\",\"score\":0}]},"
            + "{\"key\":\"mobility\",\"name\":\"活动能力\",\"options\":["
            + "{\"label\":\"完全不能自主活动\",\"score\":3},"
            + "{\"label\":\"活动受限需协助\",\"score\":2},"
            + "{\"label\":\"活动稍受限\",\"score\":1},"
            + "{\"label\":\"活动自如\",\"score\":0}]}"
            + "]";
    private static final String BURN_LEVELS = "["
            + "{\"min\":0,\"max\":5,\"level\":\"低危\",\"color\":\"#67C23A\"},"
            + "{\"min\":6,\"max\":9,\"level\":\"高危\",\"color\":\"#F56C6C\"},"
            + "{\"min\":10,\"max\":12,\"level\":\"极高危\",\"color\":\"#B71C1C\"}"
            + "]";

    /* ---------- 姑息护理 PPS 评估(5维度, 0-100%, 各维度20/10/0三档, 总分以10%递减) ---------- */
    private static final String PALLIATIVE_DIMENSIONS = "["
            + "{\"key\":\"activity\",\"name\":\"活动\",\"options\":["
            + "{\"label\":\"正常活动/无明显受限\",\"score\":20},"
            + "{\"label\":\"大部分时间卧床或坐椅\",\"score\":10},"
            + "{\"label\":\"完全卧床\",\"score\":0}]},"
            + "{\"key\":\"selfCare\",\"name\":\"日常生活\",\"options\":["
            + "{\"label\":\"完全自理\",\"score\":20},"
            + "{\"label\":\"需要部分帮助\",\"score\":10},"
            + "{\"label\":\"完全依赖护理\",\"score\":0}]},"
            + "{\"key\":\"consciousness\",\"name\":\"意识\",\"options\":["
            + "{\"label\":\"清醒\",\"score\":20},"
            + "{\"label\":\"意识模糊或嗜睡\",\"score\":10},"
            + "{\"label\":\"昏迷\",\"score\":0}]},"
            + "{\"key\":\"intake\",\"name\":\"口服摄入\",\"options\":["
            + "{\"label\":\"正常进食\",\"score\":20},"
            + "{\"label\":\"明显减少或仅流质\",\"score\":10},"
            + "{\"label\":\"不能经口进食\",\"score\":0}]},"
            + "{\"key\":\"disease\",\"name\":\"疾病程度(恶化证据)\",\"options\":["
            + "{\"label\":\"病情稳定\",\"score\":20},"
            + "{\"label\":\"进行性加重\",\"score\":10},"
            + "{\"label\":\"快速恶化\",\"score\":0}]}"
            + "]";
    private static final String PALLIATIVE_LEVELS = "["
            + "{\"min\":70,\"max\":100,\"level\":\"稳定期(以舒适照护为主)\",\"color\":\"#409EFF\"},"
            + "{\"min\":40,\"max\":60,\"level\":\"恶化期\",\"color\":\"#E6A23C\"},"
            + "{\"min\":10,\"max\":30,\"level\":\"临终期\",\"color\":\"#F56C6C\"},"
            + "{\"min\":0,\"max\":0,\"level\":\"濒死期\",\"color\":\"#B71C1C\"}"
            + "]";

    /** 11大标准量表种子: {编码, 名称, 类型(1入院评估 2专科 3风险), 维度定义JSON, 分数→风险映射JSON, 必评频次} */
    private static final Object[][] SCALE_SEEDS = {
            {"braden", "Braden压疮评估量表", 3, BRADEN_DIMENSIONS, BRADEN_LEVELS,
                    "入院24小时内首次评估; 高危者每日复评, 中低危每周复评"},
            {"morse", "Morse跌倒评估量表", 3, MORSE_DIMENSIONS, MORSE_LEVELS,
                    "入院24小时内首次评估; 高危者每日复评"},
            {"barthel", "Barthel日常生活能力(ADL)评估量表", 1, BARTHEL_DIMENSIONS, BARTHEL_LEVELS,
                    "入院24小时内评估; 病情变化时复评"},
            {"nrs", "NRS疼痛数字评估量表", 2, NRS_DIMENSIONS, NRS_LEVELS,
                    "入院时及疼痛发作时评估; 镇痛处理后复评"},
            {"caprini", "Caprini静脉血栓栓塞(VTE)风险评估量表", 3, CAPRINI_DIMENSIONS, CAPRINI_LEVELS,
                    "入院24小时内评估; 术后及病情变化时复评"},
            {"nrs2002", "NRS-2002营养风险筛查量表", 3, NRS2002_DIMENSIONS, NRS2002_LEVELS,
                    "入院24小时内筛查; 有风险者每周复评, 营养支持期间每3天复评"},
            {"swallowing", "GUSS吞咽障碍筛查量表", 2, SWALLOW_DIMENSIONS, SWALLOW_LEVELS,
                    "脑卒中/意识障碍等高危患者入院时评估; 病情变化或进食方式调整前复评"},
            {"psychology", "心理评估量表(SAS/SDS焦虑抑郁自评)", 2, PSYCH_DIMENSIONS, PSYCH_LEVELS,
                    "入院时评估; 总分(粗分)×1.25=标准分, 粗分≥40(标准分≥50)提示焦虑/抑郁, 心理干预后复评"},
            {"restraint", "保护性约束评估量表", 3, RESTRAINT_DIMENSIONS, RESTRAINT_LEVELS,
                    "入院24小时内评估; 实施约束须医嘱+知情同意, 约束期间每班复评并记录"},
            {"burn", "住院患者烫伤风险评估量表", 3, BURN_DIMENSIONS, BURN_LEVELS,
                    "入院24小时内评估; 高危者每日复评, 使用热水袋/烤灯等热源前必须评估"},
            {"palliative", "姑息护理PPS评估量表", 2, PALLIATIVE_DIMENSIONS, PALLIATIVE_LEVELS,
                    "姑息照护患者入院时评估; 病情变化时每日复评, 临终期每班复评"}
    };

    /* ================= 内部工具 ================= */

    /** 就诊必读校验: 存在 + 归属当前登录机构(平台超管放行)。 */
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
