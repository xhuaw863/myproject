package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.InpShiftDTO;
import com.yb.hi.entity.inpatient.HisInpShiftRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisWard;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpShiftRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisWardMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病区交接班服务(护士站): 当前班次判断 / 病区在院患者名单 / 发起交班(自动统计) / 接班确认 / 历史查询。
 * 说明:
 * 1) 班次划分: 08:00-16:00 白班(1) / 16:00-24:00 小夜(2) / 00:00-08:00 大夜(3);
 *    大夜的 shift_date 取当天日期(00:00-08:00 落在当天);
 * 2) 交班统计: total_patients=当前在院数, new_admit/discharged=班次时间窗内入院/出院数(自动),
 *    critical_count 由护士手动填写(经 content JSON 的 criticalCount 字段传入, 缺省 0);
 * 3) 病区×日期×班次防重: 同班次已存在待接班记录时拒绝重复发起(409);
 * 4) 接班确认乐观更新 status 1->2 并记录接班护士。
 */
@Slf4j
@Service
public class InpShiftService {

    /** 班次: 1白班 2小夜 3大夜 */
    public static final int SHIFT_DAY = 1;
    public static final int SHIFT_EVENING = 2;
    public static final int SHIFT_NIGHT = 3;

    /** 交接状态: 1待接班 2已交接 */
    public static final int SHIFT_STATUS_PENDING = 1;
    public static final int SHIFT_STATUS_DONE = 2;

    /** 在院就诊状态 */
    private static final int VISIT_IN_WARD = 2;
    /** 已出院就诊状态 */
    private static final int VISIT_DISCHARGED = 4;

    private final HisInpShiftRecordMapper shiftMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisWardMapper wardMapper;
    private final JdbcTemplate jdbcTemplate;

    public InpShiftService(HisInpShiftRecordMapper shiftMapper, HisInpVisitMapper visitMapper,
                           HisWardMapper wardMapper, JdbcTemplate jdbcTemplate) {
        this.shiftMapper = shiftMapper;
        this.visitMapper = visitMapper;
        this.wardMapper = wardMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 病区患者名单(护士站总览) ================= */

    /**
     * 本病区在院患者(visit_status=2): JOIN 患者档案取姓名/性别/年龄, LEFT JOIN 床位取床号房号;
     * keyword 匹配患者姓名/住院号; 入院时间正序(先入院在前)。
     */
    public IPage<Map<String, Object>> listWardPatients(Long wardId, String keyword, long page, long size) {
        HisWard ward = requireWard(wardId);
        long p = safePage(page);
        long s = safeSize(size);

        StringBuilder where = new StringBuilder(
                " WHERE v.ward_id = ? AND v.visit_status = " + VISIT_IN_WARD
                        + " AND v.deleted = 0 AND v.tenant_id = ?");
        List<Object> args = new ArrayList<>(Arrays.asList(wardId, tenantId()));
        if (StringUtils.hasText(keyword)) {
            String kw = "%" + keyword.trim() + "%";
            where.append(" AND (p.name LIKE ? OR v.inp_no LIKE ?)");
            args.add(kw);
            args.add(kw);
        }
        String joins = " FROM his_inp_visit v"
                + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());

        /* 床位一览增强: 护理等级/病情/隔离/饮食/预出院 + 待审待执计数 + 末次手术/体温/量表评分 + 过敏标志(双轨) + 责任护士 */
        String dataSql = "SELECT v.id, v.inp_no AS inpNo, v.visit_status AS visitStatus,"
                + " v.admit_date AS admitDate, v.doctor_id AS doctorId, v.admit_diag AS admitDiag,"
                + " v.deposit_balance AS depositBalance, v.total_cost AS totalCost,"
                + " v.nursing_level AS nursingLevel, v.condition_level AS conditionLevel,"
                + " v.is_quarantine AS isQuarantine, v.diet_type AS dietType,"
                + " v.expected_discharge_date AS expectedDischargeDate,"
                + " (SELECT COUNT(*) FROM his_inp_order po WHERE po.inp_visit_id = v.id AND po.order_status = 1 AND po.deleted = 0) AS pendingAudit,"
                + " (SELECT COUNT(*) FROM his_inp_order_exec pe WHERE pe.inp_visit_id = v.id AND pe.exec_status = 1 AND pe.deleted = 0) AS pendingExec,"
                + " (SELECT MAX(sg.schedule_date) FROM his_surgery sg WHERE sg.inp_visit_id = v.id AND sg.status IN (4, 5) AND sg.deleted = 0) AS lastSurgeryDate,"
                + " (SELECT JSON_UNQUOTE(JSON_EXTRACT(tr.content, '$.temperature')) FROM his_inp_nursing_record tr"
                + "   WHERE tr.inp_visit_id = v.id AND tr.record_type = 1 AND tr.deleted = 0 ORDER BY tr.record_time DESC LIMIT 1) AS lastTemp,"
                + " (SELECT bd.scale_score FROM his_inp_nursing_record bd WHERE bd.inp_visit_id = v.id AND bd.scale_code = 'braden' AND bd.deleted = 0 ORDER BY bd.record_time DESC LIMIT 1) AS bradenScore,"
                + " (SELECT mf.scale_score FROM his_inp_nursing_record mf WHERE mf.inp_visit_id = v.id AND mf.scale_code = 'morse' AND mf.deleted = 0 ORDER BY mf.record_time DESC LIMIT 1) AS morseScore,"
                + " (EXISTS(SELECT 1 FROM his_inp_allergy ai WHERE ai.inp_visit_id = v.id AND ai.deleted = 0)"
                + "   OR EXISTS(SELECT 1 FROM his_patient_allergy ap WHERE ap.patient_id = v.patient_id AND ap.is_active = 1 AND ap.deleted = 0)) AS allergyFlag,"
                + " p.id AS patientId, p.name AS patientName, p.gender, p.age, p.birth_date AS birthDate,"
                + " b.bed_no AS bedNo, b.room_no AS roomNo, b.bed_type AS bedType,"
                + " ds.staff_name AS doctorName, ns.staff_name AS nurseName"
                + joins
                + " LEFT JOIN his_staff ds ON v.doctor_id = ds.id AND ds.deleted = 0"
                + " LEFT JOIN his_staff ns ON v.nurse_id = ns.id AND ns.deleted = 0"
                + where + " ORDER BY v.admit_date ASC, v.id ASC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());

        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /* ================= 当前班次 ================= */

    /**
     * 当前班次信息: 按服务器时间判定班次(8-16白班 / 16-0小夜 / 0-8大夜),
     * 附在院统计(在院总数/本班次新入院/本班次出院)与待接班记录ID(已发起未接班时非空)。
     */
    public Map<String, Object> getCurrentShift(Long wardId) {
        HisWard ward = requireWard(wardId);
        LocalDateTime now = LocalDateTime.now();
        int type = resolveShiftType(now.toLocalTime());
        LocalDateTime[] range = shiftRange(now.toLocalDate(), type);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wardId", ward.getId());
        out.put("wardName", ward.getWardName());
        out.put("shiftType", type);
        out.put("shiftTypeName", shiftTypeName(type));
        out.put("shiftDate", now.toLocalDate().toString());
        out.put("startTime", range[0].toString());
        out.put("endTime", range[1].toString());
        out.put("totalPatients", countInWard(wardId));
        out.put("newAdmit", countAdmitInShift(wardId, range[0], range[1]));
        out.put("discharged", countDischargedInShift(wardId, range[0], range[1]));

        HisInpShiftRecord pending = shiftMapper.selectOne(Wrappers.<HisInpShiftRecord>lambdaQuery()
                .eq(HisInpShiftRecord::getWardId, wardId)
                .eq(HisInpShiftRecord::getShiftDate, now.toLocalDate())
                .eq(HisInpShiftRecord::getShiftType, type)
                .eq(HisInpShiftRecord::getStatus, SHIFT_STATUS_PENDING)
                .orderByDesc(HisInpShiftRecord::getId)
                .last("LIMIT 1"));
        out.put("pendingShiftId", pending == null ? null : pending.getId());
        return out;
    }

    /* ================= 交接班 ================= */

    /**
     * 发起交班: 自动统计在院/新入院/出院数, 危重人数取 content JSON 的 criticalCount(护士手动填写, 缺省 0);
     * SBAR 四段(情景/背景/评估/建议)随请求落库(前端「按患者交接」选中重点患者后自动预填, 可编辑);
     * 同病区同日同班次已存在待接班记录时拒绝(防重复发起); 落库 status=1 待接班。
     */
    @Transactional
    public Map<String, Object> handover(InpShiftDTO dto, Long nurseId) {
        if (dto == null || dto.getWardId() == null) {
            throw new BizException(400, "病区ID不能为空");
        }
        HisWard ward = requireWard(dto.getWardId());
        if (dto.getShiftType() == null || dto.getShiftType() < SHIFT_DAY || dto.getShiftType() > SHIFT_NIGHT) {
            throw new BizException(400, "班次无效(1白班 2小夜 3大夜)");
        }
        if (nurseId == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行交班签名");
        }
        LocalDate today = LocalDate.now();

        Long exists = shiftMapper.selectCount(Wrappers.<HisInpShiftRecord>lambdaQuery()
                .eq(HisInpShiftRecord::getWardId, ward.getId())
                .eq(HisInpShiftRecord::getShiftDate, today)
                .eq(HisInpShiftRecord::getShiftType, dto.getShiftType()));
        if (exists != null && exists > 0) {
            throw new BizException(409, "该班次交班记录已存在(病区×日期×班次唯一), 请勿重复发起");
        }

        LocalDateTime[] range = shiftRange(today, dto.getShiftType());
        int total = countInWard(ward.getId());
        int newAdmit = countAdmitInShift(ward.getId(), range[0], range[1]);
        int discharged = countDischargedInShift(ward.getId(), range[0], range[1]);
        int critical = parseCriticalCount(dto.getContent());

        HisInpShiftRecord rec = new HisInpShiftRecord();
        rec.setOrgId(ward.getOrgId());
        rec.setWardId(ward.getId());
        rec.setShiftDate(today);
        rec.setShiftType(dto.getShiftType());
        rec.setHandoverNurseId(nurseId);
        rec.setTotalPatients(total);
        rec.setNewAdmit(newAdmit);
        rec.setDischarged(discharged);
        rec.setCriticalCount(critical);
        rec.setContent(dto.getContent());
        rec.setSbarSituation(trimToNull(dto.getSbarSituation()));
        rec.setSbarBackground(trimToNull(dto.getSbarBackground()));
        rec.setSbarAssessment(trimToNull(dto.getSbarAssessment()));
        rec.setSbarRecommendation(trimToNull(dto.getSbarRecommendation()));
        rec.setStatus(SHIFT_STATUS_PENDING);
        shiftMapper.insert(rec);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rec.getId());
        out.put("wardId", rec.getWardId());
        out.put("shiftDate", today.toString());
        out.put("shiftType", rec.getShiftType());
        out.put("shiftTypeName", shiftTypeName(rec.getShiftType()));
        out.put("totalPatients", total);
        out.put("newAdmit", newAdmit);
        out.put("discharged", discharged);
        out.put("criticalCount", critical);
        out.put("status", SHIFT_STATUS_PENDING);
        return out;
    }

    /** 接班确认: 乐观更新 status 1->2 并记录接班护士; 已接班/不存在抛业务异常。 */
    public Map<String, Object> takeover(Long shiftId, Long nurseId) {
        if (shiftId == null) {
            throw new BizException(400, "交接班记录ID不能为空");
        }
        if (nurseId == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行接班签名");
        }
        HisInpShiftRecord rec = shiftMapper.selectById(shiftId);
        if (rec == null) {
            throw new BizException(404, "交接班记录不存在");
        }
        requireSameOrg(rec.getOrgId());
        if (rec.getStatus() == null || rec.getStatus() != SHIFT_STATUS_PENDING) {
            throw new BizException(409, "该交班记录已被接班, 请刷新后查看");
        }
        if (nurseId.equals(rec.getHandoverNurseId())) {
            throw new BizException(400, "交班人与接班人不能为同一护士");
        }
        int n = shiftMapper.update(null, Wrappers.<HisInpShiftRecord>lambdaUpdate()
                .eq(HisInpShiftRecord::getId, shiftId)
                .eq(HisInpShiftRecord::getStatus, SHIFT_STATUS_PENDING)
                .set(HisInpShiftRecord::getStatus, SHIFT_STATUS_DONE)
                .set(HisInpShiftRecord::getTakeoverNurseId, nurseId)
                .set(HisInpShiftRecord::getUpdateBy, currentUserName()));
        if (n == 0) {
            throw new BizException(409, "交班记录状态已变更(可能已被其他护士接班), 请刷新后重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", shiftId);
        out.put("status", SHIFT_STATUS_DONE);
        out.put("takeoverNurseId", nurseId);
        return out;
    }

    /** 交接班历史(按病区): 交班日期倒序(同日大夜/小夜/白班在后在前按类型倒序), 附交/接班护士姓名。 */
    public IPage<Map<String, Object>> history(Long wardId, long page, long size) {
        requireWard(wardId);
        long p = safePage(page);
        long s = safeSize(size);
        String joins = " FROM his_inp_shift_record r"
                + " LEFT JOIN his_staff hs ON r.handover_nurse_id = hs.id AND hs.deleted = 0"
                + " LEFT JOIN his_staff ts ON r.takeover_nurse_id = ts.id AND ts.deleted = 0"
                + " WHERE r.ward_id = ? AND r.deleted = 0 AND r.tenant_id = ?";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins, Long.class, wardId, tenantId());

        String dataSql = "SELECT r.id, r.ward_id AS wardId, r.shift_date AS shiftDate, r.shift_type AS shiftType,"
                + " r.handover_nurse_id AS handoverNurseId, hs.staff_name AS handoverNurseName,"
                + " r.takeover_nurse_id AS takeoverNurseId, ts.staff_name AS takeoverNurseName,"
                + " r.total_patients AS totalPatients, r.new_admit AS newAdmit, r.discharged,"
                + " r.critical_count AS criticalCount, r.content, r.status,"
                + " r.sbar_situation AS sbarSituation, r.sbar_background AS sbarBackground,"
                + " r.sbar_assessment AS sbarAssessment, r.sbar_recommendation AS sbarRecommendation,"
                + " DATE_FORMAT(r.create_time, '%Y-%m-%d %H:%i:%s') AS createTime"
                + joins + " ORDER BY r.shift_date DESC, r.shift_type DESC, r.id DESC LIMIT ?, ?";
        Object[] dataArgs = {wardId, tenantId(), (p - 1) * s, s};
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs);

        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /* ================= SBAR 按患者交接预填 ================= */

    /**
     * 单患者 SBAR 交接预填(前端「按患者交接」选中患者后调用):
     * S(情景): his_inp_visit 入院诊断 + 入院记录(record_type=1)主诉(优先 structure_data, 兼容 content JSON);
     * B(背景): 入院记录现病史摘要 + 最近3条有效医嘱(his_inp_order, 排除已停止/已作废);
     * A(评估): 最近一次体温单(record_type=1 生命体征, 兼容 blood_pressure) + 各量表最新一次评估分数
     *    (record_type=2, 按 scaleCode 去重取最新, 含 Braden/Morse/NRS);
     * R(建议): 默认护理交接建议模板(可编辑)。
     * 只读聚合, 任一数据缺失时对应段落降级为占位提示, 不阻断。
     */
    public Map<String, Object> getPatientHandoverDetail(Long visitId) {
        HisInpVisit visit = requireVisit(visitId);
        long tid = tenantId();

        /* 患者基本信息(姓名/性别/年龄/床位/住院号) */
        Map<String, Object> pat = queryFirst(
                "SELECT p.name AS patientName, p.gender, p.age, v.inp_no AS inpNo, b.bed_no AS bedNo"
                        + " FROM his_inp_visit v"
                        + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                        + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                        + " WHERE v.id = ? AND v.deleted = 0 AND v.tenant_id = ?", visit.getId(), tid);

        /* ---- S(情景): 主诉 + 入院诊断 ---- */
        String chiefComplaint = null;
        String presentIllness = null;
        Map<String, Object> admitRec = queryFirst(
                "SELECT structure_data, content FROM his_inp_medical_record"
                        + " WHERE inp_visit_id = ? AND record_type = 1 AND deleted = 0 AND tenant_id = ?"
                        + " ORDER BY id DESC LIMIT 1", visit.getId(), tid);
        if (admitRec != null) {
            JSONObject sd = parseJsonObject(String.valueOf(admitRec.get("structure_data")));
            JSONObject ct = parseJsonObject(String.valueOf(admitRec.get("content")));
            chiefComplaint = firstText(sd, ct, "chiefComplaint");
            presentIllness = firstText(sd, ct, "presentIllness");
        }
        StringBuilder s = new StringBuilder();
        if (StringUtils.hasText(chiefComplaint)) {
            s.append("主诉: ").append(chiefComplaint.trim());
        }
        if (StringUtils.hasText(visit.getAdmitDiag())) {
            if (s.length() > 0) { s.append("；"); }
            s.append("入院诊断: ").append(visit.getAdmitDiag().trim());
        }

        /* ---- B(背景): 病史摘要 + 最近3条有效医嘱 ---- */
        StringBuilder b = new StringBuilder();
        if (StringUtils.hasText(presentIllness)) {
            b.append("病史摘要: ").append(presentIllness.trim());
        }
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT order_content, dosage, dosage_unit, freq_code FROM his_inp_order"
                        + " WHERE inp_visit_id = ? AND deleted = 0 AND tenant_id = ?"
                        + " AND order_status NOT IN (5, 6)"
                        + " ORDER BY create_time DESC, id DESC LIMIT 3", visit.getId(), tid);
        if (!orders.isEmpty()) {
            if (b.length() > 0) { b.append('\n'); }
            b.append("近期医嘱:");
            for (int i = 0; i < orders.size(); i++) {
                Map<String, Object> o = orders.get(i);
                b.append("\n").append(i + 1).append(". ").append(textOf(o.get("order_content")));
                if (StringUtils.hasText(textOf(o.get("dosage")))) {
                    b.append(' ').append(textOf(o.get("dosage"))).append(textOf(o.get("dosage_unit")));
                }
                if (StringUtils.hasText(textOf(o.get("freq_code")))) {
                    b.append(" · ").append(textOf(o.get("freq_code")));
                }
            }
        }

        /* ---- A(评估): 最近一次体征 + 各量表最新评估分数 ---- */
        StringBuilder a = new StringBuilder();
        Map<String, Object> vital = queryFirst(
                "SELECT content, record_time FROM his_inp_nursing_record"
                        + " WHERE inp_visit_id = ? AND record_type = 1 AND deleted = 0 AND tenant_id = ?"
                        + " ORDER BY record_time DESC, id DESC LIMIT 1", visit.getId(), tid);
        if (vital != null) {
            JSONObject v = parseJsonObject(String.valueOf(vital.get("content")));
            if (v != null && !v.isEmpty()) {
                String when = StringUtils.hasText(v.getString("time"))
                        ? v.getString("time")
                        : String.valueOf(vital.get("record_time") == null ? "" : vital.get("record_time"));
                a.append("最近体征").append(StringUtils.hasText(when) ? "(" + when.replace('T', ' ') + ")" : "")
                        .append(": ");
                List<String> parts = new ArrayList<>();
                if (v.get("temperature") != null) { parts.add("体温 " + v.get("temperature") + "℃"); }
                if (v.get("pulse") != null) { parts.add("脉搏 " + v.get("pulse") + "次/分"); }
                if (v.get("respiration") != null) { parts.add("呼吸 " + v.get("respiration") + "次/分"); }
                if (v.get("systolicBp") != null || v.get("diastolicBp") != null) {
                    parts.add("血压 " + (v.get("systolicBp") == null ? "-" : v.get("systolicBp"))
                            + "/" + (v.get("diastolicBp") == null ? "-" : v.get("diastolicBp")) + "mmHg");
                } else if (StringUtils.hasText(v.getString("blood_pressure"))) {
                    parts.add("血压 " + v.getString("blood_pressure") + "mmHg");
                }
                if (!parts.isEmpty()) { a.append(String.join(" · ", parts)); } else { a.setLength(0); }
            }
        }
        /* 各量表最新评估(content 快照 {score, scaleCode, scaleName, level}), 按 scaleCode 去重取最新 */
        List<Map<String, Object>> assessRows = jdbcTemplate.queryForList(
                "SELECT content FROM his_inp_nursing_record"
                        + " WHERE inp_visit_id = ? AND record_type = 2 AND deleted = 0 AND tenant_id = ?"
                        + " ORDER BY record_time DESC, id DESC LIMIT 30", visit.getId(), tid);
        Map<String, String> scaleLatest = new LinkedHashMap<>();
        for (Map<String, Object> row : assessRows) {
            JSONObject c = parseJsonObject(String.valueOf(row.get("content")));
            if (c == null || c.get("score") == null || !StringUtils.hasText(c.getString("scaleCode"))) {
                continue;
            }
            String code = c.getString("scaleCode").trim();
            if (scaleLatest.containsKey(code)) { continue; }
            String name = StringUtils.hasText(c.getString("scaleName")) ? c.getString("scaleName") : code;
            String seg = name + " " + c.get("score") + "分"
                    + (StringUtils.hasText(c.getString("level")) ? "(" + c.getString("level") + ")" : "");
            scaleLatest.put(code, seg);
        }
        if (!scaleLatest.isEmpty()) {
            if (a.length() > 0) { a.append('\n'); }
            a.append("护理评估: ").append(String.join("、", scaleLatest.values()));
        }

        /* ---- R(建议): 默认交接建议模板 ---- */
        String r = "1. 密切观察病情变化及用药反应, 异常及时报告医生;\n"
                + "2. 按护理等级落实巡视与基础护理, 做好管路、皮肤与安全交接;\n"
                + "3. 重点交接当前治疗进展、待执行检查与注意事项。";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visitId", visit.getId());
        out.put("patientName", pat == null ? null : pat.get("patientName"));
        out.put("bedNo", pat == null ? null : pat.get("bedNo"));
        out.put("inpNo", pat == null ? null : pat.get("inpNo"));
        out.put("admitDiag", visit.getAdmitDiag());
        out.put("situation", s.length() > 0 ? s.toString() : "暂无主诉/入院诊断信息, 请人工补充");
        out.put("background", b.length() > 0 ? b.toString() : "暂无病史摘要与近期医嘱, 请人工补充");
        out.put("assessment", a.length() > 0 ? a.toString() : "暂无生命体征与护理评估记录, 请人工补充");
        out.put("recommendation", r);
        return out;
    }

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

    /** 单行查询(无命中返回 null, 容错)。 */
    private Map<String, Object> queryFirst(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 容错解析 JSON 对象(空白/非法返回 null)。 */
    private static JSONObject parseJsonObject(String json) {
        if (!StringUtils.hasText(json) || "null".equals(json)) {
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 两份 JSON 中取首个非空字段值(优先 primary)。 */
    private static String firstText(JSONObject primary, JSONObject fallback, String key) {
        if (primary != null && StringUtils.hasText(primary.getString(key))) {
            return primary.getString(key);
        }
        return fallback == null ? null : fallback.getString(key);
    }

    /** null 安全文本化。 */
    private static String textOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 去首尾空白, 空串归 null。 */
    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** 按时刻判定班次: [08:00,16:00)白班 / [16:00,24:00)小夜 / [00:00,08:00)大夜。 */
    private static int resolveShiftType(LocalTime time) {
        if (!time.isBefore(LocalTime.of(8, 0)) && time.isBefore(LocalTime.of(16, 0))) {
            return SHIFT_DAY;
        }
        if (!time.isBefore(LocalTime.of(16, 0))) {
            return SHIFT_EVENING;
        }
        return SHIFT_NIGHT;
    }

    /**
     * 班次时间窗(统计新入院/出院用): 大夜落在 shiftDate 当天 00:00-08:00;
     * 白班/小夜为当天 08:00-16:00 / 16:00-次日 00:00。
     */
    private static LocalDateTime[] shiftRange(LocalDate shiftDate, int shiftType) {
        switch (shiftType) {
            case SHIFT_DAY:
                return new LocalDateTime[]{shiftDate.atTime(8, 0), shiftDate.atTime(16, 0)};
            case SHIFT_EVENING:
                return new LocalDateTime[]{shiftDate.atTime(16, 0), shiftDate.plusDays(1).atStartOfDay()};
            case SHIFT_NIGHT:
            default:
                return new LocalDateTime[]{shiftDate.atStartOfDay(), shiftDate.atTime(8, 0)};
        }
    }

    private static String shiftTypeName(int shiftType) {
        switch (shiftType) {
            case SHIFT_DAY:
                return "白班";
            case SHIFT_EVENING:
                return "小夜班";
            case SHIFT_NIGHT:
                return "大夜班";
            default:
                return "未知";
        }
    }

    /** 病区当前在院数(visit_status=2)。 */
    private int countInWard(Long wardId) {
        Long n = visitMapper.selectCount(Wrappers.<HisInpVisit>lambdaQuery()
                .eq(HisInpVisit::getWardId, wardId)
                .eq(HisInpVisit::getVisitStatus, VISIT_IN_WARD));
        return n == null ? 0 : n.intValue();
    }

    /** 班次时间窗内新入院数(按 admit_date)。 */
    private int countAdmitInShift(Long wardId, LocalDateTime start, LocalDateTime end) {
        Long n = visitMapper.selectCount(Wrappers.<HisInpVisit>lambdaQuery()
                .eq(HisInpVisit::getWardId, wardId)
                .ge(HisInpVisit::getAdmitDate, start)
                .lt(HisInpVisit::getAdmitDate, end));
        return n == null ? 0 : n.intValue();
    }

    /** 班次时间窗内出院数(按 discharge_date, 已出院 visit_status=4)。 */
    private int countDischargedInShift(Long wardId, LocalDateTime start, LocalDateTime end) {
        Long n = visitMapper.selectCount(Wrappers.<HisInpVisit>lambdaQuery()
                .eq(HisInpVisit::getWardId, wardId)
                .eq(HisInpVisit::getVisitStatus, VISIT_DISCHARGED)
                .ge(HisInpVisit::getDischargeDate, start)
                .lt(HisInpVisit::getDischargeDate, end));
        return n == null ? 0 : n.intValue();
    }

    /** 危重人数由护士手动填写: 从交班内容 JSON 提取 criticalCount(缺省 0)。 */
    private static int parseCriticalCount(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        try {
            JSONObject json = JSON.parseObject(content);
            Integer v = json == null ? null : json.getInteger("criticalCount");
            return v == null || v < 0 ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 病区必读校验: 存在 + 归属当前登录机构(平台超管放行)。 */
    private HisWard requireWard(Long wardId) {
        if (wardId == null) {
            throw new BizException(400, "病区ID不能为空");
        }
        HisWard ward = wardMapper.selectById(wardId);
        if (ward == null) {
            throw new BizException(404, "病区不存在");
        }
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (!u.hasRole(Roles.SUPER_ADMIN) && ward.getOrgId() != null && u.getOrgId() != null
                && !ward.getOrgId().equals(u.getOrgId())) {
            throw new BizException(403, "该病区不属于当前登录机构, 无权操作");
        }
        return ward;
    }

    /** 写操作机构校验: 记录归属机构须与当前登录机构一致(防跨机构误操作)。 */
    private void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作护士站数据");
        }
        if (orgId != null && !orgId.equals(u.getOrgId()) && !u.hasRole(Roles.SUPER_ADMIN)) {
            throw new BizException(403, "该数据不属于当前登录机构, 无权操作");
        }
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }

    private static String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return lu.getRealName() != null && !lu.getRealName().isEmpty() ? lu.getRealName() : lu.getUsername();
    }
}
