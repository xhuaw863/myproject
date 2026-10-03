package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisNursingShiftReport;
import com.yb.hi.entity.inpatient.HisWard;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.framework.util.SafeJsonTool;
import com.yb.hi.mapper.inpatient.HisNursingShiftReportMapper;
import com.yb.hi.mapper.inpatient.HisWardMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 护理交班报告服务(P4c): 病区×班次(day/evening/night)×日期一份, 一键汇总生成交班正文。
 * 说明:
 * 1) 表 his_nursing_shift_report 由 DictSchemaMigration@0 幂等建出, 走 MyBatis-Plus 正常租户隔离;
 *    跨表联查(his_inp_visit/his_inp_transfer/his_surgery/his_nursing_vital_sign/
 *    his_nursing_clinical_event)走 JdbcTemplate 原生 SQL, 一律手工带 tenant_id/deleted 过滤;
 * 2) 班次时段: day 08:00-16:00 / evening 16:00-24:00 / night 00:00-08:00(当日口径);
 * 3) 生成即汇总固化: 在科总人数与危/重/一般分布(visit_status=2 现状快照)、班内新入院
 *    (admit_date 落窗)、转科转入/转出(his_inp_transfer status=4, 以审批时间为准)、
 *    手术(his_surgery.schedule_date=交班日, 排除取消)、MEWS≥5 高危(班内末次评分)、
 *    关键临床事件(时间窗内) → 全部写入 content JSON, 病区数据后续变化不回写;
 * 4) 生命周期状态机: 生成(status=0 草稿, 同班次重生成覆盖草稿) → 交班(status=1,
 *    回填交班人=当前登录职工+接班人) → 接班(status=2, 回填实际接班人=当前登录职工);
 *    已交班/已接班报告不可重新生成;
 * 5) 病区归属机构校验(平台超管放行), 沿用住院护士站口径。
 */
@Slf4j
@Service
public class NursingShiftService {

    /** 状态: 0草稿 1已交班 2已接班 */
    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_HANDED_OVER = 1;
    public static final int STATUS_RECEIVED = 2;

    /** 班次固定集 */
    public static final Set<String> SHIFT_TYPES = new HashSet<>(Arrays.asList("day", "evening", "night"));

    /** MEWS 高危阈值(与生命体征预警口径一致) */
    public static final int MEWS_ALERT_THRESHOLD = 5;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisNursingShiftReportMapper shiftReportMapper;
    private final HisWardMapper wardMapper;
    private final SafeJsonTool safeJsonTool;
    private final JdbcTemplate jdbcTemplate;

    public NursingShiftService(HisNursingShiftReportMapper shiftReportMapper, HisWardMapper wardMapper,
                               SafeJsonTool safeJsonTool, JdbcTemplate jdbcTemplate) {
        this.shiftReportMapper = shiftReportMapper;
        this.wardMapper = wardMapper;
        this.safeJsonTool = safeJsonTool;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 生成 ================= */

    /**
     * 一键生成交班报告: 病区×班次×日期唯一, 已存在草稿则覆盖重算, 已交班/已接班拒绝重生成。
     * 汇总结果(计数列 + content JSON)随报告固化, status=0 草稿。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingShiftReport generate(Long wardId, String shiftType, LocalDate shiftDate) {
        if (wardId == null) {
            throw new BizException(400, "病区ID不能为空");
        }
        if (!StringUtils.hasText(shiftType) || !SHIFT_TYPES.contains(shiftType.trim())) {
            throw new BizException(400, "班次无效(day/evening/night)");
        }
        LocalDate date = shiftDate != null ? shiftDate : LocalDate.now();
        HisWard ward = requireWard(wardId);

        LocalDateTime start = shiftStart(date, shiftType.trim());
        LocalDateTime end = shiftEnd(date, shiftType.trim());

        /* --- 在科现状: 人数与病情分布(visit_status=2) --- */
        List<Map<String, Object>> inWard = jdbcTemplate.queryForList(
                "SELECT v.id, v.condition_level AS conditionLevel, v.is_quarantine AS isQuarantine,"
                        + " v.admit_diag AS admitDiag, pt.name AS patientName, v.inp_no AS inpNo, v.bed_id AS bedId"
                        + " FROM his_inp_visit v"
                        + " LEFT JOIN his_patient pt ON v.patient_id = pt.id AND pt.deleted = 0"
                        + " WHERE v.ward_id = ? AND v.visit_status = 2 AND v.deleted = 0 AND v.tenant_id = ?"
                        + " ORDER BY v.bed_id ASC, v.id ASC",
                wardId, tenantId());
        int critical = 0;
        int serious = 0;
        int normal = 0;
        int quarantine = 0;
        List<Map<String, Object>> criticalPatients = new java.util.ArrayList<>();
        for (Map<String, Object> row : inWard) {
            Integer level = toInt(row.remove("conditionLevel"));
            if (level != null && level == 1) {
                critical++;
                criticalPatients.add(patientBrief(row));
            } else if (level != null && level == 2) {
                serious++;
            } else {
                normal++;
            }
            Integer q = toInt(row.get("isQuarantine"));
            if (q != null && q == 1) {
                quarantine++;
            }
            row.remove("isQuarantine");
            row.remove("admitDiag");
        }

        /* --- 班内新入院(admit_date 落窗) --- */
        List<Map<String, Object>> newAdmits = jdbcTemplate.queryForList(
                "SELECT pt.name AS patientName, v.inp_no AS inpNo, v.bed_id AS bedId,"
                        + " v.admit_diag AS admitDiag,"
                        + " DATE_FORMAT(v.admit_date, '%Y-%m-%d %H:%i') AS admitTime"
                        + " FROM his_inp_visit v"
                        + " LEFT JOIN his_patient pt ON v.patient_id = pt.id AND pt.deleted = 0"
                        + " WHERE v.ward_id = ? AND v.deleted = 0 AND v.tenant_id = ?"
                        + " AND v.admit_date >= ? AND v.admit_date < ?"
                        + " ORDER BY v.admit_date ASC, v.id ASC",
                wardId, tenantId(), start, end);

        /* --- 班内转科转入/转出(his_inp_transfer status=4 已执行, 以审批时间为准) --- */
        Integer transferIn = queryCount(
                "SELECT COUNT(*) FROM his_inp_transfer t"
                        + " WHERE t.to_ward_id = ? AND t.status = 4 AND t.deleted = 0 AND t.tenant_id = ?"
                        + " AND COALESCE(t.approve_time, t.apply_time) >= ?"
                        + " AND COALESCE(t.approve_time, t.apply_time) < ?",
                wardId, tenantId(), start, end);
        Integer transferOut = queryCount(
                "SELECT COUNT(*) FROM his_inp_transfer t"
                        + " WHERE t.from_ward_id = ? AND t.status = 4 AND t.deleted = 0 AND t.tenant_id = ?"
                        + " AND COALESCE(t.approve_time, t.apply_time) >= ?"
                        + " AND COALESCE(t.approve_time, t.apply_time) < ?",
                wardId, tenantId(), start, end);

        /* --- 班内手术(schedule_date=交班日, 排除已取消 status=6) --- */
        List<Map<String, Object>> surgeries = jdbcTemplate.queryForList(
                "SELECT pt.name AS patientName, s.surgery_name AS surgeryName, s.room_no AS roomNo,"
                        + " DATE_FORMAT(s.schedule_date, '%Y-%m-%d') AS scheduleDate"
                        + " FROM his_surgery s"
                        + " JOIN his_inp_visit v ON s.inp_visit_id = v.id AND v.deleted = 0 AND v.tenant_id = ?"
                        + " LEFT JOIN his_patient pt ON v.patient_id = pt.id AND pt.deleted = 0"
                        + " WHERE v.ward_id = ? AND s.deleted = 0 AND s.tenant_id = ?"
                        + " AND s.status <> 6 AND s.schedule_date = ?"
                        + " ORDER BY s.id ASC",
                tenantId(), wardId, tenantId(), date);

        /* --- 班内 MEWS≥5 高危(末次评分口径: 每就诊取窗口内最后一条) --- */
        List<Map<String, Object>> mewsAlerts = jdbcTemplate.queryForList(
                "SELECT pt.name AS patientName, v.inp_no AS inpNo, v.bed_id AS bedId,"
                        + " s.mews_score AS mewsScore, s.mews_level AS mewsLevel,"
                        + " DATE_FORMAT(s.record_time, '%Y-%m-%d %H:%i') AS recordTime"
                        + " FROM his_nursing_vital_sign s"
                        + " JOIN his_inp_visit v ON s.inp_visit_id = v.id AND v.deleted = 0 AND v.tenant_id = ?"
                        + " LEFT JOIN his_patient pt ON v.patient_id = pt.id AND pt.deleted = 0"
                        + " WHERE v.ward_id = ? AND s.deleted = 0 AND s.tenant_id = ?"
                        + " AND s.mews_score >= ? AND s.record_time >= ? AND s.record_time < ?"
                        + " AND s.record_time = (SELECT MAX(s2.record_time) FROM his_nursing_vital_sign s2"
                        + "  WHERE s2.inp_visit_id = s.inp_visit_id AND s2.deleted = 0"
                        + "  AND s2.record_time >= ? AND s2.record_time < ?)"
                        + " ORDER BY s.mews_score DESC, v.bed_id ASC",
                tenantId(), wardId, tenantId(), MEWS_ALERT_THRESHOLD, start, end, start, end);

        /* --- 班内关键临床事件 --- */
        List<Map<String, Object>> keyEvents = jdbcTemplate.queryForList(
                "SELECT pt.name AS patientName, v.inp_no AS inpNo, v.bed_id AS bedId,"
                        + " e.event_type AS eventType, e.event_desc AS eventDesc, e.auto_generated AS autoGenerated,"
                        + " DATE_FORMAT(e.event_time, '%Y-%m-%d %H:%i') AS eventTime"
                        + " FROM his_nursing_clinical_event e"
                        + " JOIN his_inp_visit v ON e.inp_visit_id = v.id AND v.deleted = 0 AND v.tenant_id = ?"
                        + " LEFT JOIN his_patient pt ON v.patient_id = pt.id AND pt.deleted = 0"
                        + " WHERE v.ward_id = ? AND e.deleted = 0 AND e.tenant_id = ?"
                        + " AND e.event_time >= ? AND e.event_time < ?"
                        + " ORDER BY e.event_time ASC, e.id ASC",
                tenantId(), wardId, tenantId(), start, end);

        /* --- 组装 content JSON(全部随报告固化) --- */
        Map<String, Object> patients = new LinkedHashMap<>();
        patients.put("total", inWard.size());
        patients.put("critical", critical);
        patients.put("serious", serious);
        patients.put("normal", normal);
        patients.put("quarantine", quarantine);

        Map<String, Object> range = new LinkedHashMap<>();
        range.put("start", TIME_FMT.format(start));
        range.put("end", TIME_FMT.format(end));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("wardId", wardId);
        content.put("wardName", ward.getWardName());
        content.put("shiftType", shiftType.trim());
        content.put("shiftDate", date.toString());
        content.put("range", range);
        content.put("patients", patients);
        content.put("criticalPatients", criticalPatients);
        content.put("newAdmissions", newAdmits);
        content.put("transferIn", transferIn);
        content.put("transferOut", transferOut);
        content.put("surgeries", surgeries);
        content.put("mewsAlerts", mewsAlerts);
        content.put("keyEvents", keyEvents);

        /* --- 病区×班次×日期唯一: 草稿覆盖重算, 已交班/已接班拒绝 --- */
        HisNursingShiftReport report = shiftReportMapper.selectOne(
                Wrappers.<HisNursingShiftReport>lambdaQuery()
                        .eq(HisNursingShiftReport::getWardId, wardId)
                        .eq(HisNursingShiftReport::getShiftType, shiftType.trim())
                        .eq(HisNursingShiftReport::getShiftDate, date)
                        .last("LIMIT 1"));
        boolean creating = report == null;
        if (creating) {
            report = new HisNursingShiftReport();
            report.setOrgId(ward.getOrgId());
            report.setWardId(wardId);
            report.setShiftType(shiftType.trim());
            report.setShiftDate(date);
        } else if (report.getStatus() != null && report.getStatus() != STATUS_DRAFT) {
            throw new BizException(400, "该班次报告已"
                    + (report.getStatus() == STATUS_RECEIVED ? "接班" : "交班") + ", 不可重新生成");
        }
        report.setContent(safeJsonTool.toJson(content));
        report.setCriticalCount(critical);
        report.setNewAdmitCount(newAdmits.size());
        report.setTransferInCount(transferIn);
        report.setTransferOutCount(transferOut);
        report.setSurgeryCount(surgeries.size());
        report.setTotalPatients(inWard.size());
        report.setStatus(STATUS_DRAFT);
        if (creating) {
            shiftReportMapper.insert(report);
        } else {
            shiftReportMapper.updateById(report);
        }
        log.info("交班报告生成: ward={}, shift={}/{}, 在科={}, 危/重/一般={}/{}/{}, 新入院={}, 转入/转出={}/{}, 手术={}",
                wardId, date, shiftType, inWard.size(), critical, serious, normal,
                newAdmits.size(), transferIn, transferOut, surgeries.size());
        return report;
    }

    /* ================= 交班 / 接班 ================= */

    /** 交班: 草稿报告回填交班人(当前登录职工)/接班人并置 status=1。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingShiftReport handover(Long id, Long receiverId, String receiverName) {
        LoginUser u = requireLogin();
        if (receiverId == null && !StringUtils.hasText(receiverName)) {
            throw new BizException(400, "接班人不能为空");
        }
        HisNursingShiftReport r = requireStatus(id, STATUS_DRAFT, "交班", "草稿");
        r.setReporterId(u.getStaffId() != null ? u.getStaffId() : u.getUserId());
        r.setReporterName(u.getRealName());
        r.setReceiverId(receiverId);
        r.setReceiverName(trimToNull(receiverName));
        r.setStatus(STATUS_HANDED_OVER);
        shiftReportMapper.updateById(r);
        return r;
    }

    /** 接班: 已交班报告回填实际接班人(当前登录职工)并置 status=2, 闭环。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNursingShiftReport receive(Long id) {
        LoginUser u = requireLogin();
        HisNursingShiftReport r = requireStatus(id, STATUS_HANDED_OVER, "接班", "已交班");
        r.setReceiverId(u.getStaffId() != null ? u.getStaffId() : u.getUserId());
        r.setReceiverName(u.getRealName());
        r.setStatus(STATUS_RECEIVED);
        shiftReportMapper.updateById(r);
        return r;
    }

    /* ================= 查询 ================= */

    /** 交班报告详情。 */
    public HisNursingShiftReport getById(Long id) {
        if (id == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisNursingShiftReport r = shiftReportMapper.selectById(id);
        if (r == null) {
            throw new BizException(404, "交班报告不存在");
        }
        requireSameOrg(r.getOrgId());
        return r;
    }

    /** 报告列表(按病区+日期范围): 交班日期倒序, 班次按 day→evening→night 排列。 */
    public List<HisNursingShiftReport> listByWard(Long wardId, LocalDate startDate, LocalDate endDate) {
        requireWard(wardId);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BizException(400, "开始日期不能晚于结束日期");
        }
        return shiftReportMapper.selectList(Wrappers.<HisNursingShiftReport>lambdaQuery()
                .eq(HisNursingShiftReport::getWardId, wardId)
                .ge(startDate != null, HisNursingShiftReport::getShiftDate, startDate)
                .le(endDate != null, HisNursingShiftReport::getShiftDate, endDate)
                .orderByDesc(HisNursingShiftReport::getShiftDate)
                .orderByAsc(HisNursingShiftReport::getId));
    }

    /** 最近一份报告(按病区): 交班日期倒序取第一, 无报告返回 null。 */
    public HisNursingShiftReport getLatest(Long wardId) {
        requireWard(wardId);
        return shiftReportMapper.selectOne(Wrappers.<HisNursingShiftReport>lambdaQuery()
                .eq(HisNursingShiftReport::getWardId, wardId)
                .orderByDesc(HisNursingShiftReport::getShiftDate)
                .orderByDesc(HisNursingShiftReport::getId)
                .last("LIMIT 1"));
    }

    /* ================= 班次时段 ================= */

    /** 班次窗口起点: day 08:00 / evening 16:00 / night 00:00。 */
    static LocalDateTime shiftStart(LocalDate date, String shiftType) {
        switch (shiftType) {
            case "day":
                return date.atTime(8, 0);
            case "evening":
                return date.atTime(16, 0);
            case "night":
            default:
                return date.atStartOfDay();
        }
    }

    /** 班次窗口终点(开区间): day 16:00 / evening 次日 00:00 / night 08:00。 */
    static LocalDateTime shiftEnd(LocalDate date, String shiftType) {
        switch (shiftType) {
            case "day":
                return date.atTime(16, 0);
            case "evening":
                return date.plusDays(1).atStartOfDay();
            case "night":
            default:
                return date.atTime(8, 0);
        }
    }

    /* ================= 校验 / 内部工具 ================= */

    /** 在科患者简报(危重清单行): 姓名/住院号/床位。 */
    private static Map<String, Object> patientBrief(Map<String, Object> row) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("patientName", row.get("patientName"));
        brief.put("inpNo", row.get("inpNo"));
        brief.put("bedId", row.get("bedId"));
        return brief;
    }

    /** 病区必读校验: 存在 + 归属当前登录机构(平台超管放行)。 */
    private HisWard requireWard(Long wardId) {
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

    /** 指定状态必读: 存在 + 归属机构一致 + 状态匹配(交班/接班前置校验)。 */
    private HisNursingShiftReport requireStatus(Long id, int expectStatus, String action, String statusLabel) {
        if (id == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisNursingShiftReport r = shiftReportMapper.selectById(id);
        if (r == null) {
            throw new BizException(404, "交班报告不存在");
        }
        requireSameOrg(r.getOrgId());
        if (r.getStatus() == null || r.getStatus() != expectStatus) {
            throw new BizException(400, "仅" + statusLabel + "报告可" + action + "(当前状态: "
                    + statusName(r.getStatus()) + ")");
        }
        return r;
    }

    /** 写操作机构校验: 记录归属机构须与当前登录机构一致(平台超管放行)。 */
    private void requireSameOrg(Long orgId) {
        LoginUser u = requireLogin();
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作护士站数据");
        }
        if (orgId != null && !orgId.equals(u.getOrgId()) && !u.hasRole(Roles.SUPER_ADMIN)) {
            throw new BizException(403, "该交班报告不属于当前登录机构, 无权操作");
        }
    }

    private static LoginUser requireLogin() {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        return u;
    }

    /** 状态中文名(报错展示) */
    private static String statusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case STATUS_DRAFT:
                return "草稿";
            case STATUS_HANDED_OVER:
                return "已交班";
            case STATUS_RECEIVED:
                return "已接班";
            default:
                return "未知(" + status + ")";
        }
    }

    private Integer queryCount(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n.intValue();
    }

    /** JDBC 数值对象 → Integer */
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

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
