package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.NursingVitalSignDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisNursingVitalSign;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.framework.util.SafeJsonTool;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisNursingVitalSignMapper;
import com.yb.hi.service.emr.SseEmitterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 护理生命体征服务(P4a): 结构化单次测量录入/批量录入/列表查询/体温单绘图数据/MEWS早期预警评分/异常预警推送。
 * 说明:
 * 1) 表 his_nursing_vital_sign 由 DictSchemaMigration@0 幂等建出, 走 MyBatis-Plus 正常租户隔离;
 *    JdbcTemplate 原生 SQL(体温单事件联查/待测统计)一律手工带 tenant_id/deleted 过滤;
 * 2) 录入全量范围校验(生理上下限, 超出视为笔误直接 400), 落库前自动计算 MEWS 评分(mews_score/mews_level)
 *    与异常预警清单; 命中预警时经 SseEmitterService 广播 NURSING_VITAL_ALERT 事件(失败静默不影响录入);
 * 3) 批量录入(护士站多床巡回)逐条走同一校验, 单条失败整体回滚, 全部落库成功后才统一推送预警;
 * 4) 体温单绘图数据按 7 天(可调)窗口返回: vitals 按日×六档时段(02/06/10/14/18/22)组织,
 *    events 汇总入院/手术(his_surgery, 排除已取消)/转科转床(his_inp_transfer, 仅已执行)临床事件;
 * 5) 写操作校验就诊归属机构与当前登录机构一致(平台超管放行), 沿用住院护士站口径。
 */
@Slf4j
@Service
public class NursingVitalSignService {

    /** 体温单固定测量时段(六档, 按钟点就近归段) */
    public static final String[] CHART_SLOTS = {"02", "06", "10", "14", "18", "22"};

    /** SSE 事件名: 生命体征异常预警(前端按事件名路由预警看板) */
    public static final String EVENT_VITAL_ALERT = "NURSING_VITAL_ALERT";

    /** 体温单默认窗口天数 */
    private static final int DEFAULT_CHART_DAYS = 7;
    /** 体温单窗口上限(防止超大区间查询) */
    private static final int MAX_CHART_DAYS = 31;
    /** 批量录入单次上限 */
    private static final int MAX_BATCH_SIZE = 100;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisNursingVitalSignMapper vitalSignMapper;
    private final HisInpVisitMapper visitMapper;
    private final SseEmitterService sseEmitterService;
    private final SafeJsonTool safeJsonTool;
    private final JdbcTemplate jdbcTemplate;

    public NursingVitalSignService(HisNursingVitalSignMapper vitalSignMapper, HisInpVisitMapper visitMapper,
                                   SseEmitterService sseEmitterService, SafeJsonTool safeJsonTool,
                                   JdbcTemplate jdbcTemplate) {
        this.vitalSignMapper = vitalSignMapper;
        this.visitMapper = visitMapper;
        this.sseEmitterService = sseEmitterService;
        this.safeJsonTool = safeJsonTool;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 录入 ================= */

    /**
     * 录入单次体征: 校验就诊归属与生理上下限 → 自动计算 MEWS 评分 → 检测异常预警 → 落库 →
     * 命中预警时广播 SSE 事件。recordTime 缺省当前, patientId 缺省从就诊主表回填。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingVitalSign record(NursingVitalSignDTO dto) {
        RecordResult r = doRecord(dto);
        publishAlert(r);
        return r.entity;
    }

    /**
     * 批量录入(按项目维度: 护士站多床巡回同时录入同一指标): 逐条走 record 全量校验,
     * 单条失败整体回滚(报错标注第几条), 全部落库成功后再统一推送预警。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<HisNursingVitalSign> batchRecord(List<NursingVitalSignDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            throw new BizException(400, "批量录入清单不能为空");
        }
        if (dtos.size() > MAX_BATCH_SIZE) {
            throw new BizException(400, "单次批量录入最多 " + MAX_BATCH_SIZE + " 条");
        }
        List<RecordResult> results = new ArrayList<>(dtos.size());
        for (int i = 0; i < dtos.size(); i++) {
            try {
                results.add(doRecord(dtos.get(i)));
            } catch (BizException e) {
                throw new BizException(e.getCode(), "第 " + (i + 1) + " 条: " + e.getMessage());
            }
        }
        List<HisNursingVitalSign> out = new ArrayList<>(results.size());
        for (RecordResult r : results) {
            publishAlert(r);
            out.add(r.entity);
        }
        return out;
    }

    /** 单条录入主体(校验→组装→MEWS→预警检测→落库), 预警推送由调用方在事务收口处决定 */
    private RecordResult doRecord(NursingVitalSignDTO dto) {
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        validateRanges(dto);

        Long patientId = dto.getPatientId() != null ? dto.getPatientId() : visit.getPatientId();
        if (patientId == null) {
            throw new BizException(400, "就诊记录缺少患者信息, 无法录入体征");
        }

        HisNursingVitalSign v = new HisNursingVitalSign();
        v.setOrgId(visit.getOrgId());
        v.setInpVisitId(visit.getId());
        v.setPatientId(patientId);
        v.setRecordTime(dto.getRecordTime() != null ? dto.getRecordTime() : LocalDateTime.now());
        v.setTemperature(dto.getTemperature());
        v.setTempType(dto.getTempType());
        v.setPulse(dto.getPulse());
        v.setHeartRate(dto.getHeartRate());
        v.setRespiration(dto.getRespiration());
        v.setSystolicBp(dto.getSystolicBp());
        v.setDiastolicBp(dto.getDiastolicBp());
        v.setSpo2(dto.getSpo2());
        v.setBloodGlucose(dto.getBloodGlucose());
        v.setPainScore(dto.getPainScore());
        v.setConsciousness(dto.getConsciousness());
        v.setWeight(dto.getWeight());
        v.setHeight(dto.getHeight());
        v.setGcsScore(dto.getGcsScore());
        v.setStoolCount(dto.getStoolCount());
        v.setUrineMl(dto.getUrineMl());
        v.setDrainMl(dto.getDrainMl());
        v.setNote(dto.getNote());

        calcMews(v); // 先算 MEWS(预警判定依赖评分), 同时回填 mews_score/mews_level
        List<String> alerts = checkAlerts(v);
        vitalSignMapper.insert(v);
        return new RecordResult(v, visit, alerts);
    }

    /* ================= 查询 ================= */

    /**
     * 体征列表(按就诊): 可选时间范围过滤(闭区间), 测量时间倒序(最新在前)。
     */
    public List<HisNursingVitalSign> listByVisit(Long inpVisitId, LocalDateTime start, LocalDateTime end) {
        requireVisit(inpVisitId);
        if (start != null && end != null && start.isAfter(end)) {
            throw new BizException(400, "开始时间不能晚于结束时间");
        }
        return vitalSignMapper.selectList(Wrappers.<HisNursingVitalSign>lambdaQuery()
                .eq(HisNursingVitalSign::getInpVisitId, inpVisitId)
                .ge(start != null, HisNursingVitalSign::getRecordTime, start)
                .le(end != null, HisNursingVitalSign::getRecordTime, end)
                .orderByDesc(HisNursingVitalSign::getRecordTime)
                .orderByDesc(HisNursingVitalSign::getId));
    }

    /**
     * 体温单绘图数据(默认 7 天, 上限 31 天): 窗口从今日回溯, 不早于入院日;
     * 返回:
     * - vitals: 逐条测量(含 date 与 slot 六档时段标签, 测量时间升序), 各测量项原样输出供折线/符号绘制;
     * - events: 临床事件(入院/手术/转科转床)按时间升序;
     * - slots: 固定六档时段标签; startDate/endDate: 窗口端点;
     * - admitDate: 入院日; surgeryDates: 窗口内手术日清单(去重);
     * - dayCount: 在院天数(入院日至今日, 含头含尾; 无入院时间返回 null)。
     */
    public Map<String, Object> chartData(Long inpVisitId, Integer days) {
        HisInpVisit visit = requireVisit(inpVisitId);
        int d = (days == null || days < 1) ? DEFAULT_CHART_DAYS : Math.min(days, MAX_CHART_DAYS);
        LocalDate endDate = LocalDate.now();
        LocalDate admitDate = visit.getAdmitDate() == null ? null : visit.getAdmitDate().toLocalDate();
        LocalDate startDate = endDate.minusDays(d - 1L);
        if (admitDate != null && !admitDate.isAfter(endDate) && admitDate.isAfter(startDate)) {
            startDate = admitDate; // 入院不足 d 天: 从入院日起画
        }
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();

        List<HisNursingVitalSign> rows = vitalSignMapper.selectList(Wrappers.<HisNursingVitalSign>lambdaQuery()
                .eq(HisNursingVitalSign::getInpVisitId, visit.getId())
                .ge(HisNursingVitalSign::getRecordTime, start)
                .lt(HisNursingVitalSign::getRecordTime, end)
                .orderByAsc(HisNursingVitalSign::getRecordTime)
                .orderByAsc(HisNursingVitalSign::getId));
        List<Map<String, Object>> vitals = new ArrayList<>(rows.size());
        for (HisNursingVitalSign v : rows) {
            vitals.add(toChartRow(v));
        }

        // 手术(排除已取消): 事件与手术日清单共用一次查询
        List<Map<String, Object>> surgeries = jdbcTemplate.queryForList(
                "SELECT s.id, DATE_FORMAT(s.schedule_date, '%Y-%m-%d') AS surgeryDate,"
                        + " COALESCE(s.surgery_name, '手术') AS surgeryName"
                        + " FROM his_surgery s"
                        + " WHERE s.inp_visit_id = ? AND s.deleted = 0 AND s.tenant_id = ? AND s.status <> 6"
                        + " AND s.schedule_date BETWEEN ? AND ?"
                        + " ORDER BY s.schedule_date ASC, s.id ASC",
                visit.getId(), tenantId(), startDate, endDate);
        List<String> surgeryDates = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        if (admitDate != null && !admitDate.isBefore(startDate) && !admitDate.isAfter(endDate)) {
            events.add(event("admit", visit.getAdmitDate() == null ? admitDate.toString()
                    : TIME_FMT.format(visit.getAdmitDate()), "入院"));
        }
        for (Map<String, Object> s : surgeries) {
            String dt = String.valueOf(s.get("surgeryDate"));
            if (!surgeryDates.contains(dt)) {
                surgeryDates.add(dt);
            }
            events.add(event("surgery", dt, String.valueOf(s.get("surgeryName"))));
        }
        List<Map<String, Object>> transfers = jdbcTemplate.queryForList(
                "SELECT t.transfer_type AS transferType,"
                        + " DATE_FORMAT(COALESCE(t.approve_time, t.apply_time), '%Y-%m-%d %H:%i') AS eventTime"
                        + " FROM his_inp_transfer t"
                        + " WHERE t.inp_visit_id = ? AND t.deleted = 0 AND t.tenant_id = ? AND t.status = 4"
                        + " AND COALESCE(t.approve_time, t.apply_time) >= ?"
                        + " AND COALESCE(t.approve_time, t.apply_time) < ?"
                        + " ORDER BY COALESCE(t.approve_time, t.apply_time) ASC",
                visit.getId(), tenantId(), start, end);
        for (Map<String, Object> t : transfers) {
            events.add(event("transfer", String.valueOf(t.get("eventTime")), transferTitle(t.get("transferType"))));
        }
        events.sort(Comparator.comparing(e -> String.valueOf(e.get("time")),
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vitals", vitals);
        out.put("events", events);
        out.put("slots", CHART_SLOTS);
        out.put("startDate", startDate.toString());
        out.put("endDate", endDate.toString());
        out.put("admitDate", admitDate == null ? null : admitDate.toString());
        out.put("surgeryDates", surgeryDates);
        out.put("dayCount", admitDate == null ? null
                : (int) ChronoUnit.DAYS.between(admitDate, endDate) + 1);
        return out;
    }

    /** 体征实体 → 体温单行(date/slot 供按日×时段定位, 各测量项原样输出) */
    private static Map<String, Object> toChartRow(HisNursingVitalSign v) {
        LocalDateTime t = v.getRecordTime();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", v.getId());
        row.put("recordTime", t == null ? null : TIME_FMT.format(t));
        row.put("date", t == null ? null : t.toLocalDate().toString());
        row.put("slot", t == null ? null : slotOf(t.getHour()));
        row.put("temperature", v.getTemperature());
        row.put("tempType", v.getTempType());
        row.put("pulse", v.getPulse());
        row.put("heartRate", v.getHeartRate());
        row.put("respiration", v.getRespiration());
        row.put("systolicBp", v.getSystolicBp());
        row.put("diastolicBp", v.getDiastolicBp());
        row.put("spo2", v.getSpo2());
        row.put("bloodGlucose", v.getBloodGlucose());
        row.put("painScore", v.getPainScore());
        row.put("consciousness", v.getConsciousness());
        row.put("weight", v.getWeight());
        row.put("height", v.getHeight());
        row.put("gcsScore", v.getGcsScore());
        row.put("mewsScore", v.getMewsScore());
        row.put("mewsLevel", v.getMewsLevel());
        row.put("stoolCount", v.getStoolCount());
        row.put("urineMl", v.getUrineMl());
        row.put("drainMl", v.getDrainMl());
        row.put("note", v.getNote());
        return row;
    }

    /** 刻度时段: 2/6/10/14/18/22 六档(按钟点就近归段) */
    static String slotOf(int hour) {
        if (hour < 4) {
            return "02";
        }
        if (hour < 8) {
            return "06";
        }
        if (hour < 12) {
            return "10";
        }
        if (hour < 16) {
            return "14";
        }
        if (hour < 20) {
            return "18";
        }
        return "22";
    }

    /** 转科转床事件标题: 1转科 2转床 3加床 */
    private static String transferTitle(Object transferType) {
        Integer type = toInt(transferType);
        if (type == null) {
            return "转床";
        }
        switch (type) {
            case 1:
                return "转科";
            case 3:
                return "加床";
            default:
                return "转床";
        }
    }

    private static Map<String, Object> event(String type, String time, String title) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("time", time);
        e.put("title", title);
        return e;
    }

    /* ================= MEWS 早期预警 ================= */

    /**
     * MEWS 自动评分(Modified Early Warning Score): 心率/收缩压/呼吸/体温/意识五维累加,
     * 并回填 mews_score 与 mews_level(0绿 1黄 2橙 3红; 0分=绿, 1-4=黄, 5-6=橙, >=7=红)。
     * 缺失项不计分(体温 <35 或 ≥38.5 计 2); 心率缺省回退脉搏。
     */
    public int calcMews(HisNursingVitalSign v) {
        if (v == null) {
            return 0;
        }
        int score = 0;

        Integer hr = v.getHeartRate() != null ? v.getHeartRate() : v.getPulse();
        if (hr != null) {
            if (hr < 40) {
                score += 2;
            } else if (hr <= 50) {
                score += 1;
            } else if (hr <= 100) {
                score += 0;
            } else if (hr <= 110) {
                score += 1;
            } else if (hr <= 129) {
                score += 2;
            } else {
                score += 3;
            }
        }

        Integer sbp = v.getSystolicBp();
        if (sbp != null) {
            if (sbp < 70) {
                score += 3;
            } else if (sbp <= 80) {
                score += 2;
            } else if (sbp <= 100) {
                score += 1;
            } else if (sbp <= 199) {
                score += 0;
            } else {
                score += 2;
            }
        }

        Integer resp = v.getRespiration();
        if (resp != null) {
            if (resp < 9) {
                score += 2;
            } else if (resp <= 14) {
                score += 0;
            } else if (resp <= 20) {
                score += 1;
            } else if (resp <= 29) {
                score += 2;
            } else {
                score += 3;
            }
        }

        BigDecimal temp = v.getTemperature();
        if (temp != null) {
            if (temp.compareTo(new BigDecimal("35")) < 0) {
                score += 2;
            } else if (temp.compareTo(new BigDecimal("38.5")) < 0) {
                score += 0;
            } else {
                score += 2;
            }
        }

        score += consciousnessScore(v.getConsciousness());

        v.setMewsScore(score);
        v.setMewsLevel(score <= 0 ? 0 : score <= 4 ? 1 : score <= 6 ? 2 : 3);
        return score;
    }

    /** 意识状态计分: alert→0, voice→1, pain→2, unresponsive→3(兼容中文; 缺失/未识别计 0) */
    private static int consciousnessScore(String consciousness) {
        if (!StringUtils.hasText(consciousness)) {
            return 0;
        }
        String s = consciousness.trim().toLowerCase();
        if (s.equals("alert") || s.contains("清醒") || s.contains("清楚") || s.contains("正常")) {
            return 0;
        }
        if (s.equals("voice") || s.contains("呼唤") || s.contains("声音") || s.contains("嗜睡") || s.contains("模糊")) {
            return 1;
        }
        if (s.equals("pain") || s.contains("疼痛")) {
            return 2;
        }
        if (s.equals("unresponsive") || s.contains("无反应") || s.contains("昏迷")) {
            return 3;
        }
        return 0;
    }

    /* ================= 异常预警 ================= */

    /**
     * 异常值预警检测: 返回中文预警明细(空列表=正常)。
     * 规则: 体温>39 过高 / <35 过低; 心率或脉搏>120 过快 / <50 过缓; 呼吸>30 急促 / <8 过缓;
     * 收缩压>180 过高 / <90 过低; 舒张压>110 过高; 血氧<90 过低; 血糖>16.7 过高 / <3.9 过低;
     * 疼痛≥7 剧烈; GCS≤8 昏迷; MEWS≥5 高危(需床旁评估升级)。
     */
    public List<String> checkAlerts(HisNursingVitalSign v) {
        List<String> alerts = new ArrayList<>();
        if (v == null) {
            return alerts;
        }
        if (v.getTemperature() != null) {
            if (v.getTemperature().compareTo(new BigDecimal("39")) > 0) {
                alerts.add("体温过高:" + bd(v.getTemperature()) + "℃");
            } else if (v.getTemperature().compareTo(new BigDecimal("35")) < 0) {
                alerts.add("体温过低:" + bd(v.getTemperature()) + "℃");
            }
        }
        if (v.getHeartRate() != null) {
            if (v.getHeartRate() > 120) {
                alerts.add("心率过快:" + v.getHeartRate() + "次/分");
            } else if (v.getHeartRate() < 50) {
                alerts.add("心率过缓:" + v.getHeartRate() + "次/分");
            }
        }
        if (v.getPulse() != null) {
            if (v.getPulse() > 120) {
                alerts.add("脉搏过快:" + v.getPulse() + "次/分");
            } else if (v.getPulse() < 50) {
                alerts.add("脉搏过缓:" + v.getPulse() + "次/分");
            }
        }
        if (v.getRespiration() != null) {
            if (v.getRespiration() > 30) {
                alerts.add("呼吸急促:" + v.getRespiration() + "次/分");
            } else if (v.getRespiration() < 8) {
                alerts.add("呼吸过缓:" + v.getRespiration() + "次/分");
            }
        }
        if (v.getSystolicBp() != null) {
            if (v.getSystolicBp() > 180) {
                alerts.add("血压过高:" + v.getSystolicBp() + "mmHg");
            } else if (v.getSystolicBp() < 90) {
                alerts.add("血压过低:" + v.getSystolicBp() + "mmHg");
            }
        }
        if (v.getDiastolicBp() != null && v.getDiastolicBp() > 110) {
            alerts.add("舒张压过高:" + v.getDiastolicBp() + "mmHg");
        }
        if (v.getSpo2() != null && v.getSpo2() < 90) {
            alerts.add("血氧过低:" + v.getSpo2() + "%");
        }
        if (v.getBloodGlucose() != null) {
            if (v.getBloodGlucose().compareTo(new BigDecimal("16.7")) > 0) {
                alerts.add("血糖过高:" + bd(v.getBloodGlucose()) + "mmol/L");
            } else if (v.getBloodGlucose().compareTo(new BigDecimal("3.9")) < 0) {
                alerts.add("血糖过低:" + bd(v.getBloodGlucose()) + "mmol/L");
            }
        }
        if (v.getPainScore() != null && v.getPainScore() >= 7) {
            alerts.add("剧烈疼痛:" + v.getPainScore() + "分");
        }
        if (v.getGcsScore() != null && v.getGcsScore() <= 8) {
            alerts.add("GCS昏迷:" + v.getGcsScore() + "分");
        }
        if (v.getMewsScore() != null && v.getMewsScore() >= 5) {
            alerts.add("MEWS高危:" + v.getMewsScore() + "分");
        }
        return alerts;
    }

    /** 异常预警 SSE 广播(事件名 NURSING_VITAL_ALERT; 推送失败仅告警, 不影响录入主流程) */
    private void publishAlert(RecordResult r) {
        if (r == null || r.alerts == null || r.alerts.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inpVisitId", r.entity.getInpVisitId());
            payload.put("patientId", r.entity.getPatientId());
            payload.put("orgId", r.entity.getOrgId());
            payload.put("wardId", r.visit.getWardId());
            payload.put("mewsScore", r.entity.getMewsScore());
            payload.put("mewsLevel", r.entity.getMewsLevel());
            payload.put("recordTime", r.entity.getRecordTime() == null ? null
                    : TIME_FMT.format(r.entity.getRecordTime()));
            payload.put("alerts", r.alerts);
            int sent = sseEmitterService.broadcast(EVENT_VITAL_ALERT, safeJsonTool.toJson(payload));
            log.info("体征异常预警推送: visitId={}, mewsScore={}, 预警={}, SSE送达连接数={}",
                    r.entity.getInpVisitId(), r.entity.getMewsScore(), r.alerts, sent);
        } catch (Exception e) {
            log.warn("体征异常预警 SSE 推送失败(不影响录入): visitId={}, 原因={}",
                    r.entity.getInpVisitId(), e.getMessage());
        }
    }

    /* ================= 待测统计 ================= */

    /**
     * 待测患者统计(当日未录入体征的患者数): 在院(visit_status=2)且今日(00:00 起)无体征记录的患者数,
     * wardId 可选过滤病区。口径与护理评估待办一致(不含已删记录)。
     */
    public int countPendingToday(Long wardId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM his_inp_visit v"
                        + " WHERE v.visit_status = 2 AND v.deleted = 0 AND v.tenant_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM his_nursing_vital_sign s"
                        + "  WHERE s.inp_visit_id = v.id AND s.deleted = 0 AND s.record_time >= CURDATE())");
        Long tenant = TenantContext.get();
        Long n;
        if (wardId != null) {
            sql.append(" AND v.ward_id = ?");
            n = jdbcTemplate.queryForObject(sql.toString(), Long.class, tenant == null ? 0L : tenant, wardId);
        } else {
            n = jdbcTemplate.queryForObject(sql.toString(), Long.class, tenant == null ? 0L : tenant);
        }
        return n == null ? 0 : n.intValue();
    }

    /* ================= 校验 / 内部工具 ================= */

    /** 录入范围校验: 生理上下限(超出视为笔误拦截), 收缩压不得低于舒张压, 至少一项测量数据 */
    private static void validateRanges(NursingVitalSignDTO dto) {
        checkDecimalRange(dto.getTemperature(), "34", "43", "体温(℃)");
        checkIntRange(dto.getPulse(), 20, 250, "脉搏(次/分)");
        checkIntRange(dto.getHeartRate(), 20, 250, "心率(次/分)");
        checkIntRange(dto.getRespiration(), 4, 60, "呼吸(次/分)");
        checkIntRange(dto.getSystolicBp(), 40, 300, "收缩压(mmHg)");
        checkIntRange(dto.getDiastolicBp(), 20, 200, "舒张压(mmHg)");
        checkIntRange(dto.getSpo2(), 50, 100, "血氧饱和度(%)");
        checkDecimalRange(dto.getBloodGlucose(), "0", "40", "血糖(mmol/L)");
        checkIntRange(dto.getPainScore(), 0, 10, "疼痛评分(0-10)");
        checkIntRange(dto.getTempType(), 1, 4, "体温类型(1口温 2腋温 3肛温 4耳温)");
        checkIntRange(dto.getGcsScore(), 3, 15, "GCS评分(3-15)");
        checkDecimalRange(dto.getWeight(), "0.1", "300", "体重(kg)");
        checkDecimalRange(dto.getHeight(), "1", "250", "身高(cm)");
        checkIntRange(dto.getStoolCount(), 0, 99, "大便次数");
        checkIntRange(dto.getUrineMl(), 0, 99999, "尿量(ml)");
        checkIntRange(dto.getDrainMl(), 0, 99999, "引流量(ml)");
        if (dto.getSystolicBp() != null && dto.getDiastolicBp() != null
                && dto.getSystolicBp() < dto.getDiastolicBp()) {
            throw new BizException(400, "收缩压(" + dto.getSystolicBp()
                    + ")不得低于舒张压(" + dto.getDiastolicBp() + ")");
        }
        if (dto.getTemperature() == null && dto.getPulse() == null && dto.getHeartRate() == null
                && dto.getRespiration() == null && dto.getSystolicBp() == null && dto.getDiastolicBp() == null
                && dto.getSpo2() == null && dto.getBloodGlucose() == null && dto.getPainScore() == null
                && dto.getGcsScore() == null && dto.getWeight() == null && dto.getStoolCount() == null
                && dto.getUrineMl() == null && dto.getDrainMl() == null) {
            throw new BizException(400, "至少录入一项体征数据");
        }
    }

    /** 数值上限校验(空值跳过; BigDecimal 口径, 保证 38.45 等中间值判定精确) */
    private static void checkDecimalRange(BigDecimal val, String min, String max, String label) {
        if (val == null) {
            return;
        }
        if (val.compareTo(new BigDecimal(min)) < 0 || val.compareTo(new BigDecimal(max)) > 0) {
            throw new BizException(400, label + "超出有效范围(" + min + "~" + max + ")");
        }
    }

    /** 整数上限校验(空值跳过) */
    private static void checkIntRange(Integer val, int min, int max, String label) {
        if (val == null) {
            return;
        }
        if (val < min || val > max) {
            throw new BizException(400, label + "超出有效范围(" + min + "~" + max + ")");
        }
    }

    /** 就诊必读校验: 存在 + 归属当前登录机构(平台超管放行), 沿用住院护士站口径 */
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

    /** BigDecimal 展示口径: 去尾零(39.0 → 39, 36.50 → 36.5) */
    private static String bd(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** JDBC 数值对象 → Integer(TINYINT/INT/字符串兼容) */
    private static Integer toInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /** 录入中间结果(实体 + 就诊 + 预警清单): 批量场景在全部落库成功后再统一推送预警 */
    private static class RecordResult {
        final HisNursingVitalSign entity;
        final HisInpVisit visit;
        final List<String> alerts;

        RecordResult(HisNursingVitalSign entity, HisInpVisit visit, List<String> alerts) {
            this.entity = entity;
            this.visit = visit;
            this.alerts = alerts;
        }
    }
}
