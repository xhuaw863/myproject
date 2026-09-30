package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.inpatient.HisInpFeeAlert;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpFeeAlertMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院费用预警服务: 日限额/总限额/预交金不足/大额费用四类预警的生成、处理与统计。
 * 生成侧对接 InpChargeService(限额) 与 InpDepositService(预交金), 同就诊同类型未处理预警去重防刷屏;
 * 预警创建后联动站内通知(TYPE_ALERT=4, refType=fee_alert)分别推送主管医生与责任护士。
 */
@Slf4j
@Service
public class InpFeeAlertService {

    /** 通知业务关联类型(前端通知中心据此橙色高亮并跳转费用清单待审核) */
    public static final String REF_TYPE_FEE_ALERT = "fee_alert";

    private final HisInpFeeAlertMapper feeAlertMapper;
    private final HisInpVisitMapper visitMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;
    private final InpNotificationService notificationService;

    public InpFeeAlertService(HisInpFeeAlertMapper feeAlertMapper, HisInpVisitMapper visitMapper,
                              OrgAccessGuard guard, JdbcTemplate jdbcTemplate,
                              InpNotificationService notificationService) {
        this.feeAlertMapper = feeAlertMapper;
        this.visitMapper = visitMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
    }

    /** 预警分页(visitId/alertType 可选筛选, 创建时间倒序) */
    public R<IPage<HisInpFeeAlert>> listAlerts(Long visitId, Integer alertType, Page<HisInpFeeAlert> page) {
        Page<HisInpFeeAlert> p = page != null ? page : new Page<>(1, 10);
        LambdaQueryWrapper<HisInpFeeAlert> qw = new LambdaQueryWrapper<HisInpFeeAlert>()
                .orderByDesc(HisInpFeeAlert::getCreateTime);
        if (visitId != null) {
            qw.eq(HisInpFeeAlert::getInpVisitId, visitId);
        }
        if (alertType != null) {
            qw.eq(HisInpFeeAlert::getAlertType, alertType);
        }
        IPage<HisInpFeeAlert> result = feeAlertMapper.selectPage(p, qw);
        return R.ok(result);
    }

    /**
     * 生成预警(供记费/预交金链路调用): 同就诊+同类型存在未处理预警时跳过, 避免重复刷屏。
     */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> createAlert(Long visitId, Integer alertType, Long chargeDetailId,
                               BigDecimal alertAmount, BigDecimal threshold) {
        if (visitId == null) {
            throw new BizException(400, "visitId不能为空");
        }
        if (alertType == null) {
            throw new BizException(400, "预警类型不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊不存在");
        }
        Long dup = feeAlertMapper.selectCount(new LambdaQueryWrapper<HisInpFeeAlert>()
                .eq(HisInpFeeAlert::getInpVisitId, visitId)
                .eq(HisInpFeeAlert::getAlertType, alertType)
                .isNull(HisInpFeeAlert::getHandleResult));
        if (dup != null && dup > 0) {
            log.info("同类型未处理预警已存在, 跳过生成: visitId={}, alertType={}", visitId, alertType);
            return R.ok();
        }
        HisInpFeeAlert a = new HisInpFeeAlert();
        a.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : guard.currentOrgId());
        a.setInpVisitId(visitId);
        a.setAlertType(alertType);
        a.setChargeDetailId(chargeDetailId);
        a.setAlertAmount(alertAmount);
        a.setThresholdAmount(threshold);
        feeAlertMapper.insert(a);
        log.warn("住院费用预警生成: id={}, visitId={}, type={}, amount={}, threshold={}",
                a.getId(), visitId, alertType, alertAmount, threshold);
        pushAlertNotification(visit, a);
        return R.ok();
    }

    /** 处理预警(记录处理人/时间/结果/原因, 仅未处理预警可处理) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> handleAlert(Long id, Integer result, String reason) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        if (result == null || (result != 1 && result != 2)) {
            throw new BizException(400, "处理结果不合法(1放行 2拦截)");
        }
        int affected = feeAlertMapper.update(null, new LambdaUpdateWrapper<HisInpFeeAlert>()
                .eq(HisInpFeeAlert::getId, id)
                .isNull(HisInpFeeAlert::getHandleResult)
                .set(HisInpFeeAlert::getHandleResult, result)
                .set(HisInpFeeAlert::getHandlerId, currentStaffId())
                .set(HisInpFeeAlert::getHandleTime, LocalDateTime.now())
                .set(HisInpFeeAlert::getOverrideReason, reason));
        if (affected == 0) {
            throw new BizException("预警不存在或已被处理, 请刷新后重试");
        }
        log.info("住院费用预警处理: id={}, result={}, reason={}", id, result, reason);
        return R.ok();
    }

    /**
     * 预交金不足预警患者列表(在院且 deposit_balance < deposit_warning_amount, 缺口倒序)。
     * 牵头机构可查全医共体(null=全部), 非牵头锁定本机构。
     */
    public R<List<Map<String, Object>>> getWarningPatients(Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        StringBuilder sql = new StringBuilder("SELECT v.id AS visit_id, v.inp_no, v.patient_id, p.name AS patient_name,")
                .append(" v.dept_id, v.ward_id, v.deposit_balance, v.deposit_warning_amount,")
                .append(" (v.deposit_warning_amount - v.deposit_balance) AS gap_amount")
                .append(" FROM his_inp_visit v")
                .append(" LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0 AND p.tenant_id = ?")
                .append(" WHERE v.tenant_id = ? AND v.deleted = 0 AND v.visit_status = 2")
                .append(" AND v.deposit_warning_amount IS NOT NULL AND v.deposit_balance < v.deposit_warning_amount");
        List<Object> params = new ArrayList<>();
        params.add(tenantId());
        params.add(tenantId());
        if (scope != null) {
            sql.append(" AND v.org_id = ?");
            params.add(scope);
        }
        sql.append(" ORDER BY gap_amount DESC");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        return R.ok(rows);
    }

    /** 预警统计(按类型分组: 总数/未处理/已处理, 供费用监控看板) */
    public R<Map<String, Object>> getAlertStats(Long orgId) {
        Long scope = guard.scopeOrgId(orgId);
        StringBuilder sql = new StringBuilder("SELECT alert_type, COUNT(*) AS cnt,")
                .append(" SUM(CASE WHEN handle_result IS NULL THEN 1 ELSE 0 END) AS unhandled,")
                .append(" SUM(CASE WHEN handle_result IS NOT NULL THEN 1 ELSE 0 END) AS handled")
                .append(" FROM his_inp_fee_alert WHERE tenant_id = ? AND deleted = 0");
        List<Object> params = new ArrayList<>();
        params.add(tenantId());
        if (scope != null) {
            sql.append(" AND org_id = ?");
            params.add(scope);
        }
        sql.append(" GROUP BY alert_type ORDER BY alert_type");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());

        long total = 0L;
        long unhandled = 0L;
        long handled = 0L;
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long cnt = toLong(row.get("cnt"));
            long un = toLong(row.get("unhandled"));
            long ha = toLong(row.get("handled"));
            total += cnt;
            unhandled += un;
            handled += ha;
            Map<String, Object> item = new HashMap<>();
            Integer type = row.get("alert_type") == null ? null : ((Number) row.get("alert_type")).intValue();
            item.put("alertType", type);
            item.put("alertTypeName", alertTypeName(type));
            item.put("count", cnt);
            item.put("unhandled", un);
            item.put("handled", ha);
            byType.add(item);
        }
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("unhandled", unhandled);
        stats.put("handled", handled);
        stats.put("byType", byType);
        return R.ok(stats);
    }

    /* ================= 预警联动通知 ================= */

    /**
     * 预警创建后联动站内通知: 分别推送主管医生与责任护士(TYPE_ALERT=4, refType=fee_alert)。
     * 通知失败仅记警告日志, 不回滚预警生成(避免通知链路故障阻断记费/预交金主流程)。
     */
    private void pushAlertNotification(HisInpVisit visit, HisInpFeeAlert alert) {
        try {
            String patientName = queryPatientName(visit.getPatientId());
            String content = buildAlertContent(alert, patientName, visit.getInpNo());
            Long doctorUserId = staffUserId(visit.getDoctorId());
            if (doctorUserId != null) {
                notificationService.createNotification(doctorUserId, InpNotificationService.TYPE_ALERT,
                        "费用预警", content, REF_TYPE_FEE_ALERT, alert.getId());
            }
            Long nurseUserId = staffUserId(visit.getNurseId());
            if (nurseUserId != null) {
                notificationService.createNotification(nurseUserId, InpNotificationService.TYPE_ALERT,
                        "费用预警", content, REF_TYPE_FEE_ALERT, alert.getId());
            }
            log.info("费用预警通知推送: alertId={}, visitId={}, doctorNotify={}, nurseNotify={}",
                    alert.getId(), visit.getId(), doctorUserId != null, nurseUserId != null);
        } catch (Exception e) {
            log.warn("费用预警通知推送失败(不影响预警生成): alertId={}, err={}", alert.getId(), e.getMessage());
        }
    }

    /** 通知内容: 预警类型描述 + 患者(缺失回落住院号) + 金额 + 阈值 */
    private String buildAlertContent(HisInpFeeAlert alert, String patientName, String inpNo) {
        String who = StringUtils.hasText(patientName) ? "患者" + patientName
                : (StringUtils.hasText(inpNo) ? "住院号" + inpNo : "该患者");
        int type = alert.getAlertType() == null ? 0 : alert.getAlertType();
        String amount = plain(alert.getAlertAmount());
        String threshold = plain(alert.getThresholdAmount());
        switch (type) {
            case 1:
                return who + "当日累计费用 " + amount + " 元已超过日限额 " + threshold + " 元, 请复核处理";
            case 2:
                return who + "累计费用 " + amount + " 元已超过总限额 " + threshold + " 元, 请复核处理";
            case 3:
                return who + "预交金余额 " + amount + " 元已低于预警线 " + threshold + " 元, 请及时催缴";
            case 4:
                return who + "单笔费用 " + amount + " 元已超过大额阈值 " + threshold + " 元, 请复核处理";
            default:
                return who + "费用 " + amount + " 元已超过阈值 " + threshold + " 元, 请复核处理";
        }
    }

    /** 患者姓名(his_patient, 原生 SQL 显式带租户与逻辑删除条件) */
    private String queryPatientName(Long patientId) {
        if (patientId == null) {
            return null;
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM his_patient WHERE id = ? AND deleted = 0 AND tenant_id = ? LIMIT 1",
                String.class, patientId, tenantId());
        return names.isEmpty() ? null : names.get(0);
    }

    /** his_staff.id → sys_user.id(通知接收用户); 职工未开通账号返回 null */
    private Long staffUserId(Long staffId) {
        if (staffId == null) {
            return null;
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM sys_user WHERE staff_id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                Long.class, staffId, tenantId());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** BigDecimal 去尾零展示(如 6.5000 → 6.5, null → "-") */
    private static String plain(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    private long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private String alertTypeName(Integer type) {
        if (type == null) {
            return "未知";
        }
        switch (type) {
            case 1:
                return "日限额";
            case 2:
                return "总限额";
            case 3:
                return "预交金不足";
            case 4:
                return "大额费用";
            default:
                return "未知";
        }
    }

    /** 当前登录职工ID(处理预警需要职工身份) */
    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.getStaffId() == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法处理费用预警");
        }
        return lu.getStaffId();
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
