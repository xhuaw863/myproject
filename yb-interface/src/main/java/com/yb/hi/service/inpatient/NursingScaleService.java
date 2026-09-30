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
import com.yb.hi.mapper.inpatient.HisInpNursingRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
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
 * 4) 启动种子(ApplicationReadyEvent, 在建表迁移之后执行): 按 scale_code 幂等补种 5 大标准量表
 *    (Braden压疮/Morse跌倒/Barthel ADL/NRS疼痛/Caprini VTE), 判存不含 deleted 条件
 *    (uk_scale_code 含 deleted, 墓碑行仍占键位)。
 */
@Slf4j
@Service
public class NursingScaleService {

    /** 记录类型: 2护理评估(对应 his_inp_nursing_record.record_type) */
    public static final int TYPE_ASSESSMENT = 2;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisInpNursingRecordMapper recordMapper;
    private final HisInpVisitMapper visitMapper;
    private final JdbcTemplate jdbcTemplate;
    private final NursingPlanService planService;

    public NursingScaleService(HisInpNursingRecordMapper recordMapper, HisInpVisitMapper visitMapper,
                               JdbcTemplate jdbcTemplate, @Lazy NursingPlanService planService) {
        this.recordMapper = recordMapper;
        this.visitMapper = visitMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.planService = planService;
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

    /* ================= 启动种子(5大标准量表) ================= */

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

    /** 5大标准量表种子: {编码, 名称, 类型(1入院评估 2专科 3风险), 维度定义JSON, 分数→风险映射JSON, 必评频次} */
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
                    "入院24小时内评估; 术后及病情变化时复评"}
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
