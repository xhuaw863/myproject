package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.SurgeryApplyDTO;
import com.yb.hi.dto.inpatient.SurgeryScheduleDTO;
import com.yb.hi.entity.inpatient.HisSurgeryApply;
import com.yb.hi.entity.inpatient.HisSurgeryNotify;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisSurgeryApplyMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryNotifyMapper;
import com.yb.hi.platform.notify.SmsNotifyGateway;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手术申请单服务(规范2.2.2.3.7.7/14/22): 申请→病区护士复核(可退回)→待安排→已安排→已完成, 可作废;
 * 支持住院/门诊/日间三类就诊(患者信息建单时快照); 创建/复核通过时自动产生预约通知(type1)。
 * 通知管理: 未通知批量"发送"(仅置状态留痕, 短信无真实通道)、患者回复登记。
 * JdbcTemplate 手写 SQL 显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class SurgeryApplyService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisSurgeryApplyMapper applyMapper;
    private final HisSurgeryNotifyMapper notifyMapper;
    private final SurgeryAuthRuleService authRuleService;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;
    private final SmsNotifyGateway notifyGateway;

    public SurgeryApplyService(HisSurgeryApplyMapper applyMapper, HisSurgeryNotifyMapper notifyMapper,
                               SurgeryAuthRuleService authRuleService, OrgAccessGuard guard,
                               JdbcTemplate jdbcTemplate, SmsNotifyGateway notifyGateway) {
        this.applyMapper = applyMapper;
        this.notifyMapper = notifyMapper;
        this.authRuleService = authRuleService;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
        this.notifyGateway = notifyGateway;
    }

    /* ==================== 查询 ==================== */

    /** 申请单分页: visitType/status/applyDeptId/申请日期区间/关键字(单号/患者/手术名) */
    public IPage<Map<String, Object>> page(Integer visitType, Integer status, Long deptId,
                                           String startDate, String endDate, String keyword,
                                           int page, int size) {
        long p = page < 1 ? 1 : page;
        long s = size < 1 ? 20 : Math.min(size, 200);
        StringBuilder where = new StringBuilder(
                " FROM his_surgery_apply a WHERE a.deleted = 0 AND a.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            where.append(" AND a.org_id = ?");
            args.add(scope);
        }
        if (visitType != null) {
            where.append(" AND a.visit_type = ?");
            args.add(visitType);
        }
        if (status != null) {
            where.append(" AND a.status = ?");
            args.add(status);
        }
        if (deptId != null) {
            where.append(" AND a.apply_dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(startDate)) {
            where.append(" AND a.apply_time >= ?");
            args.add(startDate + " 00:00:00");
        }
        if (StringUtils.hasText(endDate)) {
            where.append(" AND a.apply_time <= ?");
            args.add(endDate + " 23:59:59");
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (a.apply_no LIKE ? OR a.patient_name LIKE ? OR a.surgery_name LIKE ? OR a.medical_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        long cnt = total == null ? 0L : total;
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> records = cnt == 0 ? new ArrayList<>()
                : jdbcTemplate.queryForList(
                "SELECT a.id, a.apply_no, a.visit_type, a.inp_visit_id, a.visit_id, a.patient_id,"
                        + " a.patient_name, a.gender, a.age, a.medical_no, a.bed_no, a.phone,"
                        + " a.apply_dept_id, a.apply_dept_name, a.surgery_code, a.surgery_name, a.surgery_level,"
                        + " a.anesthesia_type, a.surgeon_id, a.surgeon_name, a.expect_time, a.deadline_type,"
                        + " a.pre_op_diag, a.apply_reason, a.special_req, a.apply_by_id, a.apply_by_name, a.apply_time,"
                        + " a.status, a.reconfirm_by, a.reconfirm_time, a.reject_reason, a.cancel_reason, a.surgery_id"
                        + where + " ORDER BY a.id DESC LIMIT ?, ?",
                dataArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s, cnt);
        result.setRecords(records);
        return result;
    }

    /** 申请单详情 */
    public HisSurgeryApply detail(Long id) {
        return requireApply(id);
    }

    /** 门诊/日间就诊检索(申请对话框选患者): 按姓名/门诊号/病历号 LIKE, 最近就诊在前 */
    public List<Map<String, Object>> visitSearch(String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT v.id visit_id, v.patient_id, v.patient_name, v.ipt_otp_no, v.dept_id, v.dept_name,"
                        + " p.gender_name, p.age, p.phone"
                        + " FROM his_visit v"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " WHERE v.deleted = 0 AND v.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            sql.append(" AND v.org_id = ?");
            args.add(scope);
        }
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (v.patient_name LIKE ? OR v.ipt_otp_no LIKE ? OR v.patient_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        sql.append(" ORDER BY v.id DESC LIMIT 20");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /* ==================== 申请创建(患者快照 + 权限校验 + 通知) ==================== */

    /** 新建申请单: status=1待复核; 主刀权限命中拦截; 自动生成预约成功通知(待通知) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryApply add(SurgeryApplyDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getSurgeryName())) {
            throw new BizException(400, "手术名称不能为空");
        }
        int visitType = dto.getVisitType() == null ? 1 : dto.getVisitType();
        if (visitType != 1 && visitType != 2 && visitType != 3) {
            throw new BizException(400, "就诊类型仅支持 1住院 2门诊 3日间");
        }
        HisSurgeryApply a = new HisSurgeryApply();
        a.setOrgId(guard.currentOrgId());
        a.setVisitType(visitType);
        // 患者快照(住院: his_inp_visit+his_patient+his_bed; 门诊/日间: his_visit)
        Map<String, Object> snap = visitType == 1 ? snapshotInp(dto.getInpVisitId()) : snapshotOutp(dto.getVisitId());
        a.setInpVisitId(visitType == 1 ? dto.getInpVisitId() : null);
        a.setVisitId(visitType == 1 ? null : dto.getVisitId());
        a.setPatientId(toLong(snap.get("patient_id")));
        a.setPatientName(str(snap.get("patient_name")));
        a.setGender(str(snap.get("gender")));
        a.setAge(str(snap.get("age")));
        a.setMedicalNo(str(snap.get("medical_no")));
        a.setBedNo(str(snap.get("bed_no")));
        a.setPhone(str(snap.get("phone")));
        a.setApplyDeptId(toLong(snap.get("dept_id")));
        a.setApplyDeptName(str(snap.get("dept_name")));
        a.setSurgeryCode(trimOrNull(dto.getSurgeryCode()));
        a.setSurgeryName(dto.getSurgeryName().trim());
        a.setSurgeryLevel(dto.getSurgeryLevel());
        a.setAnesthesiaType(dto.getAnesthesiaType());
        a.setSurgeonId(dto.getSurgeonId());
        a.setSurgeonName(staffName(dto.getSurgeonId()));
        a.setExpectTime(dto.getExpectTime());
        a.setDeadlineType(dto.getDeadlineType() == null ? 1 : dto.getDeadlineType());
        a.setPreOpDiag(trimOrNull(dto.getPreOpDiag()));
        a.setApplyReason(trimOrNull(dto.getApplyReason()));
        a.setSpecialReq(trimOrNull(dto.getSpecialReq()));
        // 术者权限校验(自定义分类/等级规则 + 职工自身级别回退)
        if (dto.getSurgeonId() != null) {
            Map<String, Object> chk = authRuleService.check(dto.getSurgeonId(),
                    a.getSurgeryCode(), a.getSurgeryName(), a.getSurgeryLevel());
            if (!Boolean.TRUE.equals(chk.get("allowed"))) {
                throw new BizException("主刀医师权限不足: " + chk.get("reason"));
            }
        }
        LoginUser lu = requireLogin();
        a.setApplyById(lu.getStaffId());
        a.setApplyByName(StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername());
        a.setApplyTime(LocalDateTime.now());
        a.setStatus(1);
        a.setApplyNo(nextApplyNo());
        applyMapper.insert(a);
        createNotify(a.getId(), null, a.getPatientName(), a.getPhone(), 1,
                "手术申请已预约: " + a.getSurgeryName() + (a.getExpectTime() == null ? "" : ", 期望时间" + a.getExpectTime().format(DTF)));
        log.info("手术申请单创建: id={}, no={}, patient={}, surgery={}", a.getId(), a.getApplyNo(),
                a.getPatientName(), a.getSurgeryName());
        return a;
    }

    /* ==================== 复核 / 退回 / 作废 ==================== */

    /** 急诊直排自动建档(规范2.2.2.3.7.5): 生成已安排(4)申请单留档, 不做复核链 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryApply createUrgentApply(SurgeryScheduleDTO dto) {
        int visitType = dto.getVisitType() == null ? 1 : dto.getVisitType();
        HisSurgeryApply a = new HisSurgeryApply();
        a.setOrgId(guard.currentOrgId());
        a.setVisitType(visitType);
        Map<String, Object> snap = visitType == 1 ? snapshotInp(dto.getInpVisitId()) : snapshotOutp(dto.getVisitId());
        a.setInpVisitId(visitType == 1 ? dto.getInpVisitId() : null);
        a.setVisitId(visitType == 1 ? null : dto.getVisitId());
        a.setPatientId(toLong(snap.get("patient_id")));
        a.setPatientName(str(snap.get("patient_name")));
        a.setGender(str(snap.get("gender")));
        a.setAge(str(snap.get("age")));
        a.setMedicalNo(str(snap.get("medical_no")));
        a.setBedNo(str(snap.get("bed_no")));
        a.setPhone(str(snap.get("phone")));
        a.setApplyDeptId(toLong(snap.get("dept_id")));
        a.setApplyDeptName(str(snap.get("dept_name")));
        a.setSurgeryCode(trimOrNull(dto.getSurgeryCode()));
        a.setSurgeryName(dto.getSurgeryName().trim());
        a.setSurgeryLevel(dto.getSurgeryLevel());
        a.setAnesthesiaType(dto.getAnesthesiaType());
        a.setSurgeonId(dto.getSurgeonId());
        a.setSurgeonName(dto.getSurgeonId() == null ? null : staffName(dto.getSurgeonId()));
        a.setExpectTime(dto.getExpectTime() != null ? dto.getExpectTime()
                : dto.getScheduleDate().atStartOfDay());
        a.setDeadlineType(3);
        a.setPreOpDiag(trimOrNull(dto.getPreOpDiag()));
        a.setApplyReason("急诊直接安排");
        LoginUser lu = requireLogin();
        a.setApplyById(lu.getStaffId());
        a.setApplyByName(displayName(lu));
        a.setApplyTime(LocalDateTime.now());
        a.setStatus(4);
        a.setReconfirmBy("急诊免复核");
        a.setReconfirmTime(LocalDateTime.now());
        a.setApplyNo(nextApplyNo());
        applyMapper.insert(a);
        log.info("急诊直排自动建档: id={}, no={}", a.getId(), a.getApplyNo());
        return a;
    }

    /** 病区护士复核通过: 1→2(待安排), 记复核人 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryApply reconfirm(Long id) {
        HisSurgeryApply a = requireApply(id);
        LoginUser lu = requireLogin();
        int affected = applyMapper.update(null, new LambdaUpdateWrapper<HisSurgeryApply>()
                .set(HisSurgeryApply::getStatus, 2)
                .set(HisSurgeryApply::getReconfirmBy, displayName(lu))
                .set(HisSurgeryApply::getReconfirmTime, LocalDateTime.now())
                .set(HisSurgeryApply::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryApply::getId, id)
                .eq(HisSurgeryApply::getStatus, 1));
        if (affected == 0) {
            throw new BizException("仅待复核的申请可复核通过(状态已变化), 请刷新后重试");
        }
        // 手麻P1: 复核合并——联动审核本申请关联且仍为新开(1)的术前医嘱(药审无需或通过 0/2 者, 尊重药审门控)
        int audited = jdbcTemplate.update(
                "UPDATE his_inp_order SET order_status = 2, audit_nurse_id = ?, audit_time = NOW(), update_time = NOW()"
                        + " WHERE surgery_apply_id = ? AND order_status = 1 AND pharm_audit_status IN (0, 2)"
                        + " AND deleted = 0 AND tenant_id = ?",
                lu.getStaffId(), id, tenantId());
        log.info("手术申请复核通过: id={}, by={}, 合并审核术前医嘱={}条", id, displayName(lu), audited);
        return applyMapper.selectById(id);
    }

    /** 复核退回: 1→3, 退回原因必填 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryApply reject(Long id, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "退回原因必填");
        }
        HisSurgeryApply a = requireApply(id);
        LoginUser lu = requireLogin();
        int affected = applyMapper.update(null, new LambdaUpdateWrapper<HisSurgeryApply>()
                .set(HisSurgeryApply::getStatus, 3)
                .set(HisSurgeryApply::getReconfirmBy, displayName(lu))
                .set(HisSurgeryApply::getReconfirmTime, LocalDateTime.now())
                .set(HisSurgeryApply::getRejectReason, reason.trim())
                .set(HisSurgeryApply::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryApply::getId, id)
                .eq(HisSurgeryApply::getStatus, 1));
        if (affected == 0) {
            throw new BizException("仅待复核的申请可退回(状态已变化), 请刷新后重试");
        }
        log.info("手术申请退回: id={}, reason={}", id, reason);
        return applyMapper.selectById(id);
    }

    /** 作废: 1/2/3 → 6(已安排的手术须先取消安排), 原因必填 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryApply voidApply(Long id, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "作废原因必填");
        }
        requireApply(id);
        int affected = applyMapper.update(null, new LambdaUpdateWrapper<HisSurgeryApply>()
                .set(HisSurgeryApply::getStatus, 6)
                .set(HisSurgeryApply::getCancelReason, reason.trim())
                .set(HisSurgeryApply::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryApply::getId, id)
                .in(HisSurgeryApply::getStatus, 1, 2, 3));
        if (affected == 0) {
            throw new BizException("仅未安排的申请可作废(状态已变化), 请刷新后重试");
        }
        log.info("手术申请作废: id={}, reason={}", id, reason);
        return applyMapper.selectById(id);
    }

    /** 退回后重新提交: 3→1(清空退回原因, 更新申请时间) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryApply resubmit(Long id, SurgeryApplyDTO dto) {
        HisSurgeryApply a = requireApply(id);
        if (a.getStatus() == null || a.getStatus() != 3) {
            throw new BizException("仅已退回的申请可重新提交");
        }
        if (dto != null) {
            if (StringUtils.hasText(dto.getSurgeryName())) {
                a.setSurgeryName(dto.getSurgeryName().trim());
            }
            if (dto.getSurgeryLevel() != null) {
                a.setSurgeryLevel(dto.getSurgeryLevel());
            }
            if (dto.getSurgeonId() != null && !dto.getSurgeonId().equals(a.getSurgeonId())) {
                a.setSurgeonId(dto.getSurgeonId());
                a.setSurgeonName(staffName(dto.getSurgeonId()));
                Map<String, Object> chk = authRuleService.check(a.getSurgeonId(),
                        a.getSurgeryCode(), a.getSurgeryName(), a.getSurgeryLevel());
                if (!Boolean.TRUE.equals(chk.get("allowed"))) {
                    throw new BizException("主刀医师权限不足: " + chk.get("reason"));
                }
            }
            if (dto.getExpectTime() != null) {
                a.setExpectTime(dto.getExpectTime());
            }
            if (dto.getAnesthesiaType() != null) {
                a.setAnesthesiaType(dto.getAnesthesiaType());
            }
            if (StringUtils.hasText(dto.getApplyReason())) {
                a.setApplyReason(dto.getApplyReason().trim());
            }
        }
        a.setStatus(1);
        a.setRejectReason(null);
        a.setApplyTime(LocalDateTime.now());
        applyMapper.updateById(a);
        log.info("手术申请重新提交: id={}", id);
        return applyMapper.selectById(id);
    }

    /* ==================== 通知管理(规范2.2.2.3.7.6) ==================== */

    /** 通知分页: status/notifyType/channel 可选(1待通知集中管理批量补发) */
    public IPage<Map<String, Object>> notifyPage(Integer status, Integer notifyType, Integer channel, int page, int size) {
        long p = page < 1 ? 1 : page;
        long s = size < 1 ? 20 : Math.min(size, 200);
        StringBuilder where = new StringBuilder(
                " FROM his_surgery_notify n WHERE n.deleted = 0 AND n.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            where.append(" AND n.org_id = ?");
            args.add(scope);
        }
        if (status != null) {
            where.append(" AND n.status = ?");
            args.add(status);
        }
        if (notifyType != null) {
            where.append(" AND n.notify_type = ?");
            args.add(notifyType);
        }
        if (channel != null) {
            where.append(" AND n.channel = ?");
            args.add(channel);
        }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        long cnt = total == null ? 0L : total;
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> records = cnt == 0 ? new ArrayList<>()
                : jdbcTemplate.queryForList(
                "SELECT n.id, n.apply_id, n.surgery_id, n.patient_name, n.phone, n.notify_type, n.channel,"
                        + " n.content, n.status, n.reply_content, n.send_by, n.send_time,"
                        + " n.retry_count, n.gateway_msg_id, n.create_time"
                        + where + " ORDER BY n.id DESC LIMIT ?, ?",
                dataArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s, cnt);
        result.setRecords(records);
        return result;
    }

    /**
     * 批量发送通知: 置状态2+留痕; 电话(2)/诊间(3)仅登记不真实下发, 其余渠道(短信1/自助机4/APP5/公众号6)
     * 经 notifyGateway 下发, Noop 恒成功(无回执), Http 回填 gateway_msg_id。每次下发 retry_count+1。
     */
    @Transactional(rollbackFor = Exception.class)
    public int notifySend(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "请选择要发送的通知");
        }
        LoginUser lu = requireLogin();
        int sent = 0;
        for (Long id : ids) {
            HisSurgeryNotify n = notifyMapper.selectById(id);
            if (n == null || !Integer.valueOf(1).equals(n.getStatus())) {
                continue;
            }
            Integer ch = n.getChannel();
            if (needGateway(ch)) {
                SmsNotifyGateway.Result r = notifyGateway.send(n.getPhone(), n.getContent());
                if (!r.isSuccess()) {
                    log.warn("手术通知下发失败: id={}, channel={}, err={}", id, ch, r.getError());
                    continue;
                }
                n.setGatewayMsgId(r.getMsgId());
            }
            n.setStatus(2);
            n.setRetryCount((n.getRetryCount() == null ? 0 : n.getRetryCount()) + 1);
            n.setSendBy(displayName(lu));
            n.setSendTime(LocalDateTime.now());
            notifyMapper.updateById(n);
            sent++;
        }
        log.info("手术通知批量发送: 请求={}条, 实际={}条", ids.size(), sent);
        return sent;
    }

    /** 单条重发(失败或需再触达): 不校验原状态, 走网关后 retry_count+1 并置已通知。 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryNotify notifyResend(Long id) {
        if (id == null) {
            throw new BizException(400, "通知ID不能为空");
        }
        HisSurgeryNotify n = notifyMapper.selectById(id);
        if (n == null) {
            throw new BizException(404, "通知记录不存在");
        }
        LoginUser lu = requireLogin();
        Integer ch = n.getChannel();
        if (needGateway(ch)) {
            SmsNotifyGateway.Result r = notifyGateway.send(n.getPhone(), n.getContent());
            if (!r.isSuccess()) {
                throw new BizException("下发失败: " + r.getError());
            }
            n.setGatewayMsgId(r.getMsgId());
        }
        n.setStatus(2);
        n.setRetryCount((n.getRetryCount() == null ? 0 : n.getRetryCount()) + 1);
        n.setSendBy(displayName(lu));
        n.setSendTime(LocalDateTime.now());
        notifyMapper.updateById(n);
        return notifyMapper.selectById(id);
    }

    /**
     * 生成术前提醒(P2c, 规范2.2.2.3.7.6): 对已安排(status=4)且排期在 [今天, 今天+beforeHours小时对应日] 的手术,
     * 逐台产 notify_type=3 记录; 去重: 同 surgery_id 已存在未发送(type3,status=1)则跳过。返回新增条数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int generatePreOpReminders(int beforeHours) {
        int hours = beforeHours <= 0 ? 24 : Math.min(beforeHours, 168);
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays((long) Math.ceil(hours / 24.0));
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        Long scope = guard.scopeOrgId(null);
        String orgSql = "";
        if (scope != null) {
            orgSql = " AND s.org_id = ?";
            args.add(scope);
        }
        args.add(today);
        args.add(horizon);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id surgery_id, s.org_id, s.apply_id, s.schedule_date, s.schedule_time, s.surgery_name"
                        + " FROM his_surgery s WHERE s.deleted = 0 AND s.tenant_id = ?" + orgSql
                        + " AND s.status = 4 AND s.schedule_date BETWEEN ? AND ?"
                        + " AND NOT EXISTS (SELECT 1 FROM his_surgery_notify n WHERE n.deleted = 0"
                        + " AND n.surgery_id = s.id AND n.notify_type = 3 AND n.status = 1)"
                        + " ORDER BY s.schedule_date", args.toArray());
        int created = 0;
        for (Map<String, Object> row : rows) {
            Long surgeryId = toLong(row.get("surgery_id"));
            String pname = str(jdbcTemplate.queryForObject(
                    "SELECT COALESCE(p.name, '') FROM his_surgery s LEFT JOIN his_inp_visit v ON v.id = s.inp_visit_id AND v.deleted = 0"
                            + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0 WHERE s.id = ? AND s.deleted = 0",
                    String.class, surgeryId));
            String phone = str(jdbcTemplate.queryForObject(
                    "SELECT COALESCE(p.phone, '') FROM his_surgery s LEFT JOIN his_inp_visit v ON v.id = s.inp_visit_id AND v.deleted = 0"
                            + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0 WHERE s.id = ? AND s.deleted = 0",
                    String.class, surgeryId));
            String content = "术前提醒: " + str(row.get("surgery_name")) + " 将于 "
                    + str(row.get("schedule_date")) + " " + str(row.get("schedule_time")) + " 进行, 请提前做好准备";
            // 直接按手术自身 org_id 落库(不依赖登录上下文), 使定时任务线程(无 UserContext)也可生成提醒
            insertNotify(toLong(row.get("org_id")), toLong(row.get("apply_id")), surgeryId,
                    pname, phone, 3, content, 1);
            created++;
        }
        log.info("术前提醒生成: horizon={}天, 新增={}条", hours, created);
        return created;
    }

    /** 是否需经真实网关下发(电话/诊间为线下登记, 不走网关)。 */
    private static boolean needGateway(Integer channel) {
        return channel == null || (channel != 2 && channel != 3);
    }

    /** 建通知(默认渠道短信): 供 SurgeryService 联动调用 */
    public void createNotify(Long applyId, Long surgeryId, String patientName, String phone,
                             int notifyType, String content) {
        createNotify(applyId, surgeryId, patientName, phone, notifyType, content, 1);
    }

    /** 建通知(指定渠道)。 */
    public void createNotify(Long applyId, Long surgeryId, String patientName, String phone,
                             int notifyType, String content, Integer channel) {
        insertNotify(guard.currentOrgId(), applyId, surgeryId, patientName, phone, notifyType, content, channel);
    }

    /** 通知落库(显式 orgId, 供登录联动与无上下文定时任务共用): 默认渠道短信(1)、状态1待通知、retry 0。 */
    private void insertNotify(Long orgId, Long applyId, Long surgeryId, String patientName, String phone,
                              int notifyType, String content, Integer channel) {
        HisSurgeryNotify n = new HisSurgeryNotify();
        n.setOrgId(orgId);
        n.setApplyId(applyId);
        n.setSurgeryId(surgeryId);
        n.setPatientName(patientName);
        n.setPhone(phone);
        n.setNotifyType(notifyType);
        n.setChannel(channel != null ? channel : 1);
        n.setContent(content);
        n.setRetryCount(0);
        n.setStatus(1);
        notifyMapper.insert(n);
    }

    /** 电话通知登记 / 患者回复登记: 置状态3并记录回复内容 */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryNotify notifyReply(Long id, String replyContent, Integer channel) {
        HisSurgeryNotify n = notifyMapper.selectById(id);
        if (n == null) {
            throw new BizException(404, "通知记录不存在");
        }
        LoginUser lu = requireLogin();
        n.setStatus(3);
        n.setReplyContent(trimOrNull(replyContent));
        if (channel != null) {
            n.setChannel(channel);
        }
        n.setSendBy(displayName(lu));
        if (n.getSendTime() == null) {
            n.setSendTime(LocalDateTime.now());
        }
        notifyMapper.updateById(n);
        return notifyMapper.selectById(id);
    }

    /* ==================== 校验 / 工具 ==================== */

    /** 申请单存在性 + 机构归属 */
    public HisSurgeryApply requireApply(Long id) {
        if (id == null) {
            throw new BizException(400, "申请单ID不能为空");
        }
        HisSurgeryApply a = applyMapper.selectById(id);
        if (a == null) {
            throw new BizException(404, "手术申请单不存在");
        }
        Long scope = guard.scopeOrgId(a.getOrgId());
        if (scope == null || !scope.equals(a.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的申请单");
        }
        return a;
    }

    /** 申请单状态流转(供 SurgeryService 安排/完成/回退联动) */
    public void updateApplyStatus(Long applyId, int fromStatus, int toStatus, Long surgeryId) {
        int affected = applyMapper.update(null, new LambdaUpdateWrapper<HisSurgeryApply>()
                .set(HisSurgeryApply::getStatus, toStatus)
                .set(surgeryId != null, HisSurgeryApply::getSurgeryId, surgeryId)
                .set(HisSurgeryApply::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryApply::getId, applyId)
                .eq(HisSurgeryApply::getStatus, fromStatus));
        if (affected == 0) {
            throw new BizException("申请单状态已变化(期望" + fromStatus + "), 请刷新后重试");
        }
    }

    /** 住院患者快照 */
    private Map<String, Object> snapshotInp(Long inpVisitId) {
        if (inpVisitId == null) {
            throw new BizException(400, "住院手术须选择住院就诊记录");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT v.patient_id, p.name patient_name, p.gender_name gender, p.age,"
                        + " v.inp_no medical_no, b.bed_no, p.phone, v.dept_id, d.dept_name"
                        + " FROM his_inp_visit v"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " LEFT JOIN his_bed b ON b.id = v.bed_id AND b.deleted = 0"
                        + " LEFT JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0"
                        + " WHERE v.id = ? AND v.deleted = 0 AND v.tenant_id = ?", inpVisitId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "住院就诊记录不存在");
        }
        Map<String, Object> r = rows.get(0);
        // 在院校验
        List<Map<String, Object>> st = jdbcTemplate.queryForList(
                "SELECT visit_status FROM his_inp_visit WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                inpVisitId, tenantId());
        Object vsv = st.isEmpty() ? null : st.get(0).get("visit_status");
        if (vsv != null) {
            int n = ((Number) vsv).intValue();
            if (n == 4 || n == 5) {
                throw new BizException("该就诊已出院或已取消, 不能申请手术");
            }
        }
        return r;
    }

    /** 门诊/日间患者快照 */
    private Map<String, Object> snapshotOutp(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "门诊/日间手术须选择门诊就诊记录");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT v.patient_id, v.patient_name, p.gender_name gender, p.age,"
                        + " COALESCE(v.ipt_otp_no, v.patient_no) medical_no, v.dept_id, v.dept_name, p.phone"
                        + " FROM his_visit v"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " WHERE v.id = ? AND v.deleted = 0 AND v.tenant_id = ?", visitId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "门诊就诊记录不存在");
        }
        rows.get(0).put("bed_no", null);
        return rows.get(0);
    }

    /** 申请单号: SQ+yyyyMMdd+4位当日序号 */
    private String nextApplyNo() {
        String day = LocalDateTime.now().format(NO_FMT);
        String prefix = "SQ" + day;
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_surgery_apply WHERE apply_no LIKE ? AND tenant_id = ?",
                Long.class, prefix + "%", tenantId());
        long seq = (cnt == null ? 0L : cnt) + 1;
        // 唯一键冲突时向后探测(并发/软删留下的号段)
        for (int i = 0; i < 50; i++, seq++) {
            String no = prefix + String.format("%04d", seq);
            Long exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_surgery_apply WHERE apply_no = ?", Long.class, no);
            if (exists == null || exists == 0) {
                return no;
            }
        }
        throw new BizException("申请单号生成失败, 请重试");
    }

    private String staffName(Long staffId) {
        if (staffId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT staff_name FROM his_staff WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                staffId, tenantId());
        return rows.isEmpty() ? null : str(rows.get(0).get("staff_name"));
    }

    private static LoginUser requireLogin() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        return lu;
    }

    private static String displayName(LoginUser lu) {
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static String trimOrNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
