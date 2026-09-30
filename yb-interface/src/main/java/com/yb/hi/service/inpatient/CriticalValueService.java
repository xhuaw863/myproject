package com.yb.hi.service.inpatient;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院危急值闭环服务(T37): 检验结果上报 → 规则匹配告警 → 通知医生/护士 → 医生确认(签名) → 处置 → 关闭。
 * 说明:
 * 1) 数据表: his_critical_value_rule(阈值规则, 通用/男女分段+年龄区间) + his_critical_value_record(处理记录,
 *    status 1待通知 → 2已通知 → 3已确认 → 4已处置 → 5已关闭);
 * 2) 阈值判定口径: value < critical_low(下限) 或 value > critical_high(上限) 即告警, 单侧为 NULL 不检查;
 * 3) 状态推进一律乐观更新(UPDATE ... WHERE id=? AND status=from), affected=0 视为状态已变更并提示刷新;
 * 4) 原生 SQL 显式带 tenant_id 与 deleted=0(手写 SQL 不经 MP 租户插件自动隔离);
 * 5) 告警推送复用 InpNotificationService.createNotification(TYPE_ALERT=4, refType=critical_value)。
 */
@Slf4j
@Service("inpCriticalValueService")
public class CriticalValueService {

    /** 状态: 1待通知 2已通知 3已确认 4已处置 5已关闭 */
    public static final int STATUS_PENDING_NOTIFY = 1;
    public static final int STATUS_NOTIFIED = 2;
    public static final int STATUS_CONFIRMED = 3;
    public static final int STATUS_HANDLED = 4;
    public static final int STATUS_CLOSED = 5;

    /** 通知业务关联类型(前端通知中心据此跳转危急值页面) */
    public static final String REF_TYPE = "critical_value";

    private final JdbcTemplate jdbcTemplate;
    private final InpNotificationService notificationService;

    public CriticalValueService(JdbcTemplate jdbcTemplate, InpNotificationService notificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
    }

    /* ==================== 结果上报 → 告警 ==================== */

    /**
     * 检验结果上报: 匹配危急值规则 → 命中则落处理记录(status=1)并推送医生/护士站内通知。
     * 返回 {alert:boolean, recordId, alertLevel, message}; 未命中或非数值返回 alert=false。
     */
    @Transactional
    public Map<String, Object> checkAndAlert(Long visitId, String itemCode, String itemName,
                                             String resultValue, String unit) {
        if (visitId == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        if (!StringUtils.hasText(itemCode)) {
            throw new BizException(400, "检验项目编码不能为空");
        }
        if (!StringUtils.hasText(resultValue)) {
            throw new BizException(400, "检验结果值不能为空");
        }
        Long tenant = tenantId();

        /* 1) 就诊+患者上下文(主管医生/责任护士均为 his_staff.id; 患者性别兼容 男/女 与 1/2 两种存量格式) */
        List<Map<String, Object>> visits = jdbcTemplate.queryForList(
                "SELECT v.id, v.patient_id AS patientId, v.ward_id AS wardId, v.doctor_id AS doctorId,"
                        + " v.nurse_id AS nurseId, v.inp_no AS inpNo, p.name AS patientName, p.gender, p.age"
                        + " FROM his_inp_visit v"
                        + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                        + " WHERE v.id = ? AND v.deleted = 0 AND v.tenant_id = ?",
                visitId, tenant);
        if (visits.isEmpty()) {
            throw new BizException(404, "住院就诊不存在");
        }
        Map<String, Object> visit = visits.get(0);

        /* 2) 数值解析: 非数值结果(如阴性/阳性)无法做阈值判定, 直接返回未告警 */
        BigDecimal val;
        try {
            val = new BigDecimal(resultValue.trim());
        } catch (NumberFormatException e) {
            log.info("危急值判定跳过(非数值): visitId={}, itemCode={}, value={}", visitId, itemCode, resultValue);
            return noAlert("结果值[" + resultValue + "]非数值, 跳过危急值判定");
        }

        /* 3) 规则匹配: 同项目多规则时性别专属优先(ORDER BY gender DESC), 通用规则兜底 */
        int patientGender = genderCode(visit.get("gender"));
        Integer patientAge = visit.get("age") == null ? null : ((Number) visit.get("age")).intValue();
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT id, item_code AS itemCode, item_name AS itemName, unit, critical_low AS criticalLow,"
                        + " critical_high AS criticalHigh, gender, age_min AS ageMin, age_max AS ageMax,"
                        + " alert_level AS alertLevel"
                        + " FROM his_critical_value_rule"
                        + " WHERE item_code = ? AND enabled = 1 AND deleted = 0 AND tenant_id = ?"
                        + " ORDER BY gender DESC, id ASC",
                itemCode.trim(), tenant);

        Map<String, Object> hit = null;
        BigDecimal hitBound = null;
        String hitDir = null;
        for (Map<String, Object> rule : rules) {
            int rg = rule.get("gender") == null ? 0 : ((Number) rule.get("gender")).intValue();
            if (rg != 0 && rg != patientGender) {
                continue;
            }
            Integer amin = rule.get("ageMin") == null ? null : ((Number) rule.get("ageMin")).intValue();
            Integer amax = rule.get("ageMax") == null ? null : ((Number) rule.get("ageMax")).intValue();
            if (amin != null && (patientAge == null || patientAge < amin)) {
                continue;
            }
            if (amax != null && (patientAge == null || patientAge > amax)) {
                continue;
            }
            BigDecimal low = toBigDecimal(rule.get("criticalLow"));
            BigDecimal high = toBigDecimal(rule.get("criticalHigh"));
            if (low != null && val.compareTo(low) < 0) {
                hit = rule;
                hitBound = low;
                hitDir = "low";
                break;
            }
            if (high != null && val.compareTo(high) > 0) {
                hit = rule;
                hitBound = high;
                hitDir = "high";
                break;
            }
        }
        if (hit == null) {
            return noAlert("未命中危急值规则");
        }

        /* 4) 命中: 落处理记录(status=1 待通知), 参考范围按规则阈值方向生成展示文本 */
        final String finalItemName = StringUtils.hasText(itemName) ? itemName.trim() : str(hit.get("itemName"));
        final String finalUnit = StringUtils.hasText(unit) ? unit.trim() : str(hit.get("unit"));
        final String refRange = refRangeText(toBigDecimal(hit.get("criticalLow")), toBigDecimal(hit.get("criticalHigh")));
        final int alertLevel = hit.get("alertLevel") == null ? 1 : ((Number) hit.get("alertLevel")).intValue();
        final String finalValue = resultValue.trim();
        final Long patientId = ((Number) visit.get("patientId")).longValue();

        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO his_critical_value_record (visit_id, patient_id, item_code, item_name,"
                            + " result_value, unit, ref_range, alert_level, report_time, status, tenant_id,"
                            + " deleted, create_time)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), " + STATUS_PENDING_NOTIFY + ", ?, 0, NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, visitId);
            ps.setLong(2, patientId);
            ps.setString(3, itemCode.trim());
            ps.setString(4, finalItemName);
            ps.setString(5, finalValue);
            ps.setString(6, finalUnit);
            ps.setString(7, refRange);
            ps.setInt(8, alertLevel);
            ps.setLong(9, tenant);
            return ps;
        }, kh);
        Long recordId = kh.getKey() == null ? null : kh.getKey().longValue();

        /* 5) 推送通知给主管医生与责任护士(type=4 预警, refType=critical_value 供通知中心跳转) */
        String patientName = str(visit.get("patientName"));
        String content = "患者" + patientName + "的" + finalItemName + "检验结果" + finalValue
                + (StringUtils.hasText(finalUnit) ? finalUnit : "")
                + (("low".equals(hitDir)) ? ", 低于危急值下限" : ", 高于危急值上限")
                + plain(hitBound) + ", 请及时确认处理";
        Long firstNotificationId = null;
        Long doctorUserId = staffUserId(toLong(visit.get("doctorId")));
        if (doctorUserId != null) {
            firstNotificationId = notificationService.createNotification(
                    doctorUserId, InpNotificationService.TYPE_ALERT, "危急值告警", content, REF_TYPE, recordId);
        }
        Long nurseUserId = staffUserId(toLong(visit.get("nurseId")));
        if (nurseUserId != null) {
            Long nid = notificationService.createNotification(
                    nurseUserId, InpNotificationService.TYPE_ALERT, "危急值告警", content, REF_TYPE, recordId);
            if (firstNotificationId == null) {
                firstNotificationId = nid;
            }
        }
        if (firstNotificationId != null && recordId != null) {
            jdbcTemplate.update("UPDATE his_critical_value_record SET notification_id = ? WHERE id = ?",
                    firstNotificationId, recordId);
        }
        log.info("危急值告警: recordId={}, visitId={}, itemCode={}, value={}, level={}, dir={}, doctorNotify={}, nurseNotify={}",
                recordId, visitId, itemCode, finalValue, alertLevel, hitDir, doctorUserId != null, nurseUserId != null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alert", true);
        out.put("recordId", recordId);
        out.put("alertLevel", alertLevel);
        out.put("message", content);
        return out;
    }

    /* ==================== 闭环流转 ==================== */

    /** 标记已通知(1→2): 记录通知时间。 */
    public void notifyDoctor(Long recordId) {
        advance(recordId, STATUS_PENDING_NOTIFY, STATUS_NOTIFIED, "notify_doctor_time = NOW()");
    }

    /** 医生确认(2→3): 记录确认医生(his_staff.id)与确认时间, 前端已先行电子签名。 */
    public void confirmByDoctor(Long recordId) {
        Long staffId = currentStaffId();
        if (staffId == null) {
            throw new BizException(403, "当前账号未关联职工, 无法确认危急值");
        }
        advance(recordId, STATUS_NOTIFIED, STATUS_CONFIRMED,
                "confirm_doctor_id = ?, confirm_time = NOW()", staffId);
    }

    /** 记录处置措施(3→4): 处置经过留痕, 发布后进入待关闭。 */
    public void handleCriticalValue(Long recordId, String measures) {
        if (!StringUtils.hasText(measures)) {
            throw new BizException(400, "处置措施不能为空");
        }
        advance(recordId, STATUS_CONFIRMED, STATUS_HANDLED,
                "handle_measures = ?, handle_time = NOW()", measures.trim());
    }

    /** 关闭(4→5): 闭环完成。 */
    public void closeCriticalValue(Long recordId) {
        advance(recordId, STATUS_HANDLED, STATUS_CLOSED, null);
    }

    /* ==================== 规则 CRUD ==================== */

    /** 规则列表(按项目编码排序)。 */
    public List<Map<String, Object>> listRules() {
        return jdbcTemplate.queryForList(
                "SELECT id, item_code AS itemCode, item_name AS itemName, unit, critical_low AS criticalLow,"
                        + " critical_high AS criticalHigh, gender, age_min AS ageMin, age_max AS ageMax,"
                        + " alert_level AS alertLevel, enabled"
                        + " FROM his_critical_value_rule WHERE tenant_id = ? AND deleted = 0"
                        + " ORDER BY item_code ASC, gender ASC, id ASC",
                tenantId());
    }

    /** 保存规则(有 id 更新, 无 id 新增); 同项目同性别仅允许一条有效规则。 */
    @Transactional
    public void saveRule(Map<String, Object> rule) {
        if (rule == null) {
            throw new BizException(400, "规则内容不能为空");
        }
        String itemCode = str(rule.get("itemCode"));
        String itemName = str(rule.get("itemName"));
        if (!StringUtils.hasText(itemCode)) {
            throw new BizException(400, "项目编码不能为空");
        }
        if (!StringUtils.hasText(itemName)) {
            throw new BizException(400, "项目名称不能为空");
        }
        itemCode = itemCode.trim();
        itemName = itemName.trim();
        String unit = StringUtils.hasText(str(rule.get("unit"))) ? str(rule.get("unit")).trim() : null;
        BigDecimal low = toBigDecimal(rule.get("criticalLow"));
        BigDecimal high = toBigDecimal(rule.get("criticalHigh"));
        if (low == null && high == null) {
            throw new BizException(400, "危急值下限与上限至少填写一项");
        }
        if (low != null && high != null && low.compareTo(high) >= 0) {
            throw new BizException(400, "危急值下限必须小于上限");
        }
        int gender = rule.get("gender") == null ? 0 : ((Number) rule.get("gender")).intValue();
        Integer ageMin = rule.get("ageMin") == null ? null : ((Number) rule.get("ageMin")).intValue();
        Integer ageMax = rule.get("ageMax") == null ? null : ((Number) rule.get("ageMax")).intValue();
        if (ageMin != null && ageMax != null && ageMin > ageMax) {
            throw new BizException(400, "年龄下限不能大于年龄上限");
        }
        int alertLevel = rule.get("alertLevel") == null ? 1 : ((Number) rule.get("alertLevel")).intValue();
        int enabled = rule.get("enabled") == null ? 1 : ((Number) rule.get("enabled")).intValue();
        Long id = toLong(rule.get("id"));

        Long tenant = tenantId();
        checkRuleConflict(itemCode, gender, id);
        if (id == null) {
            jdbcTemplate.update(
                    "INSERT INTO his_critical_value_rule (item_code, item_name, unit, critical_low, critical_high,"
                            + " gender, age_min, age_max, alert_level, enabled, tenant_id, deleted, create_time)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW())",
                    itemCode, itemName, unit, low, high, gender, ageMin, ageMax, alertLevel, enabled, tenant);
            log.info("危急值规则新增: itemCode={}, gender={}, low={}, high={}, tenant={}",
                    itemCode, gender, low, high, tenant);
        } else {
            int affected = jdbcTemplate.update(
                    "UPDATE his_critical_value_rule SET item_code = ?, item_name = ?, unit = ?, critical_low = ?,"
                            + " critical_high = ?, gender = ?, age_min = ?, age_max = ?, alert_level = ?, enabled = ?"
                            + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    itemCode, itemName, unit, low, high, gender, ageMin, ageMax, alertLevel, enabled, id, tenant);
            if (affected == 0) {
                throw new BizException(404, "规则不存在或已删除");
            }
            log.info("危急值规则更新: id={}, itemCode={}, low={}, high={}", id, itemCode, low, high);
        }
    }

    /**
     * 删除规则(软删)。注意唯一键 uk_item_tenant(item_code, gender, tenant_id, deleted) 含 deleted 位:
     * 同键历史墓碑行(deleted=1)存在时再次软删会撞唯一键, 故先物理清除同键墓碑再软删当前行。
     */
    @Transactional
    public void deleteRule(Long id) {
        if (id == null) {
            throw new BizException(400, "规则ID不能为空");
        }
        Long tenant = tenantId();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT item_code AS itemCode, COALESCE(gender, 0) AS gender"
                        + " FROM his_critical_value_rule WHERE id = ? AND tenant_id = ? AND deleted = 0",
                id, tenant);
        if (rows.isEmpty()) {
            throw new BizException(404, "规则不存在或已删除");
        }
        String itemCode = str(rows.get(0).get("itemCode"));
        int gender = ((Number) rows.get(0).get("gender")).intValue();
        jdbcTemplate.update(
                "DELETE FROM his_critical_value_rule WHERE item_code = ? AND COALESCE(gender, 0) = ?"
                        + " AND tenant_id = ? AND deleted = 1",
                itemCode, gender, tenant);
        jdbcTemplate.update(
                "UPDATE his_critical_value_rule SET deleted = 1 WHERE id = ? AND tenant_id = ? AND deleted = 0",
                id, tenant);
        log.info("危急值规则删除(软删): id={}, itemCode={}, gender={}", id, itemCode, gender);
    }

    /* ==================== 记录查询 ==================== */

    /** 处理记录分页(JOIN 患者/床位/病区, 可选病区与状态过滤; 未闭环优先、危急优先、时间倒序)。 */
    public Map<String, Object> listRecords(Long wardId, Integer status, int page, int size) {
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 200);
        StringBuilder where = new StringBuilder(
                " FROM his_critical_value_record r"
                        + " JOIN his_inp_visit v ON r.visit_id = v.id AND v.deleted = 0"
                        + " JOIN his_patient p ON r.patient_id = p.id AND p.deleted = 0"
                        + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                        + " LEFT JOIN his_staff ds ON r.confirm_doctor_id = ds.id AND ds.deleted = 0"
                        + " WHERE r.deleted = 0 AND r.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (wardId != null) {
            where.append(" AND v.ward_id = ?");
            args.add(wardId);
        }
        if (status != null) {
            where.append(" AND r.status = ?");
            args.add(status);
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());

        String dataSql = "SELECT r.id, r.visit_id AS visitId, r.patient_id AS patientId,"
                + " r.item_code AS itemCode, r.item_name AS itemName, r.result_value AS resultValue,"
                + " r.unit, r.ref_range AS refRange, r.alert_level AS alertLevel,"
                + " DATE_FORMAT(r.report_time, '%Y-%m-%d %H:%i:%s') AS reportTime,"
                + " DATE_FORMAT(r.notify_doctor_time, '%Y-%m-%d %H:%i:%s') AS notifyDoctorTime,"
                + " r.confirm_doctor_id AS confirmDoctorId, ds.staff_name AS confirmDoctorName,"
                + " DATE_FORMAT(r.confirm_time, '%Y-%m-%d %H:%i:%s') AS confirmTime,"
                + " r.handle_measures AS handleMeasures,"
                + " DATE_FORMAT(r.handle_time, '%Y-%m-%d %H:%i:%s') AS handleTime,"
                + " r.status, r.notification_id AS notificationId,"
                + " v.inp_no AS inpNo, v.ward_id AS wardId, p.name AS patientName, p.gender, p.age,"
                + " b.bed_no AS bedNo"
                + where
                + " ORDER BY r.status ASC, r.alert_level ASC, r.report_time DESC, r.id DESC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0L : total);
        out.put("rows", rows);
        return out;
    }

    /** 未处理数量(status IN 1,2,3), 供医生站/护士站看板待办计数。 */
    public int getUnhandledCount(Long wardId) {
        String sql = "SELECT COUNT(*) FROM his_critical_value_record r"
                + " JOIN his_inp_visit v ON r.visit_id = v.id AND v.deleted = 0"
                + " WHERE r.deleted = 0 AND r.tenant_id = ? AND r.status IN (1, 2, 3)";
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (wardId != null) {
            sql += " AND v.ward_id = ?";
            args.add(wardId);
        }
        Integer cnt = jdbcTemplate.queryForObject(sql, Integer.class, args.toArray());
        return cnt == null ? 0 : cnt;
    }

    /* ==================== 内部辅助 ==================== */

    /** 乐观状态推进: UPDATE ... SET status=to[, extraSet] WHERE id=? AND status=from; 未命中=状态已变更。 */
    private void advance(Long recordId, int fromStatus, int toStatus, String extraSet, Object... extraArgs) {
        if (recordId == null) {
            throw new BizException(400, "记录ID不能为空");
        }
        StringBuilder sql = new StringBuilder(
                "UPDATE his_critical_value_record SET status = ?, update_time = NOW()");
        List<Object> args = new ArrayList<>();
        args.add(toStatus);
        if (StringUtils.hasText(extraSet)) {
            sql.append(", ").append(extraSet);
            args.addAll(Arrays.asList(extraArgs));
        }
        sql.append(" WHERE id = ? AND status = ? AND deleted = 0 AND tenant_id = ?");
        args.add(recordId);
        args.add(fromStatus);
        args.add(tenantId());
        int affected = jdbcTemplate.update(sql.toString(), args.toArray());
        if (affected == 0) {
            throw new BizException(400, "操作失败: 记录不存在或状态已变更, 请刷新后重试");
        }
    }

    /** 校验规则冲突: 同租户同项目同性别仅允许一条有效规则(excludeId 为编辑时的自身排除)。 */
    private void checkRuleConflict(String itemCode, int gender, Long excludeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM his_critical_value_rule"
                        + " WHERE item_code = ? AND COALESCE(gender, 0) = ? AND tenant_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>(Arrays.asList(itemCode, gender, tenantId()));
        if (excludeId != null) {
            sql.append(" AND id <> ?");
            args.add(excludeId);
        }
        Integer cnt = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
        if (cnt != null && cnt > 0) {
            throw new BizException(400, "该项目同性别规则已存在, 请勿重复配置");
        }
    }

    /** his_staff.id → sys_user.id(通知接收用户); 职工未开通账号返回 null。 */
    private Long staffUserId(Long staffId) {
        if (staffId == null) {
            return null;
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM sys_user WHERE staff_id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                Long.class, staffId, tenantId());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 当前登录用户关联职工ID(UserContext) */
    private Long currentStaffId() {
        return UserContext.get() == null ? null : UserContext.get().getStaffId();
    }

    private Long tenantId() {
        return TenantContext.get();
    }

    /** 患者性别文本 → 规则性别码(1男 2女 0未知); 兼容 '男/女' 与 '1/2' 两种存量格式。 */
    private int genderCode(Object gender) {
        String g = gender == null ? null : String.valueOf(gender);
        if ("1".equals(g) || "男".equals(g)) {
            return 1;
        }
        if ("2".equals(g) || "女".equals(g)) {
            return 2;
        }
        return 0;
    }

    private Map<String, Object> noAlert(String message) {
        Map<String, Object> miss = new LinkedHashMap<>();
        miss.put("alert", false);
        miss.put("message", message);
        return miss;
    }

    /** 参考范围展示文本: 双端 "low ~ high"; 单端 "≥ low" / "≤ high"。 */
    private String refRangeText(BigDecimal low, BigDecimal high) {
        if (low != null && high != null) {
            return plain(low) + " ~ " + plain(high);
        }
        if (low != null) {
            return "> " + plain(low);
        }
        if (high != null) {
            return "< " + plain(high);
        }
        return null;
    }

    /** BigDecimal 去尾零展示(如 6.5000 → 6.5) */
    private String plain(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new BizException(400, "阈值[" + s + "]不是有效数字");
        }
    }

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Long.valueOf(s);
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
