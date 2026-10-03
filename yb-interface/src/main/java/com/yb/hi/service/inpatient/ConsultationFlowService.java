package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.InpMedRecordDTO;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.inpatient.HisInpConsultation;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.inpatient.HisInpConsultationMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.emr.EmrEventPublisher;
import com.yb.hi.service.emr.EmrEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会诊流程引擎(P6-2, 住院/门诊统一入口):
 * 状态机 1申请→2受理→3完成 / 1或2→4拒绝 / 1→5取消, 与既有 InpConsultationService 同口径;
 * 在旧服务基础上扩展: 响应时限(responseDeadline: 特急=即刻/急=10min/普通=24h)与超时预警调度、
 * 完成后自动生成住院会诊记录文书(recordType=15, 回填 consult_record_id)、会诊医嘱关联(order_id)、
 * 双向评价(申请方/受邀方各评 1-5 分)、多条件分页与 JdbcTemplate 运营统计。
 * 状态流转均采用乐观更新(WHERE status=旧值), 并发冲突提示刷新重试;
 * SSE 通知经 EmrEventPublisher.publishToDept 走领域事件解耦(无监听器/无连接时静默丢弃)。
 * 注: 与旧 /api/his/inp/consultation/ 链路并存, 旧 Controller/Service 不改动。
 */
@Slf4j
@Service
public class ConsultationFlowService {

    /** 状态: 申请 */
    public static final int STATUS_APPLY = 1;
    /** 状态: 受理 */
    public static final int STATUS_ACCEPTED = 2;
    /** 状态: 完成 */
    public static final int STATUS_COMPLETED = 3;
    /** 状态: 拒绝 */
    public static final int STATUS_REJECTED = 4;
    /** 状态: 取消 */
    public static final int STATUS_CANCELLED = 5;

    /** 紧急程度: 普通 */
    private static final int URGENCY_NORMAL = 1;
    /** 紧急程度: 急 */
    private static final int URGENCY_URGENT = 2;
    /** 紧急程度: 特急 */
    private static final int URGENCY_EXTRA = 3;

    /** 急会诊响应时限(分钟) */
    private static final long URGENT_DEADLINE_MINUTES = 10;
    /** 普会诊响应时限(小时) */
    private static final long NORMAL_DEADLINE_HOURS = 24;

    /** 会诊记录病历类型(his_inp_medical_record.record_type, 与病历P2扩展口径一致) */
    private static final int RECORD_TYPE_CONSULT = 15;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisInpConsultationMapper consultationMapper;
    private final HisInpVisitMapper inpVisitMapper;
    private final HisVisitMapper outVisitMapper;
    private final HisDeptMapper deptMapper;
    private final HisStaffMapper staffMapper;
    private final OrgAccessGuard guard;
    private final InpMedRecordService medRecordService;
    private final EmrEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    public ConsultationFlowService(HisInpConsultationMapper consultationMapper,
                                   HisInpVisitMapper inpVisitMapper,
                                   HisVisitMapper outVisitMapper,
                                   HisDeptMapper deptMapper,
                                   HisStaffMapper staffMapper,
                                   OrgAccessGuard guard,
                                   InpMedRecordService medRecordService,
                                   EmrEventPublisher eventPublisher,
                                   JdbcTemplate jdbcTemplate) {
        this.consultationMapper = consultationMapper;
        this.inpVisitMapper = inpVisitMapper;
        this.outVisitMapper = outVisitMapper;
        this.deptMapper = deptMapper;
        this.staffMapper = staffMapper;
        this.guard = guard;
        this.medRecordService = medRecordService;
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 状态机核心 ==================== */

    /**
     * 创建会诊申请(住院/门诊通用)。
     * 校验受邀科室与申请理由必填; 申请科室/医师回落当前登录上下文或就诊归属;
     * 按紧急程度计算响应截止时间(特急=即刻, 急=10min, 普通=24h);
     * 冗余回填申请/受邀科室与医师名称; 落库后 SSE 通知受邀科室。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpConsultation apply(HisInpConsultation dto) {
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (dto.getTargetDeptId() == null) {
            throw new BizException(400, "受邀科室不能为空");
        }
        if (!StringUtils.hasText(dto.getApplyReason())) {
            throw new BizException(400, "申请理由不能为空");
        }
        /* 就诊类型与双ID归一: visitType 缺省住院; visitId/inpVisitId 互为回填 */
        int visitType = dto.getVisitType() == null ? 1 : dto.getVisitType();
        if (visitType != 1 && visitType != 2) {
            throw new BizException(400, "就诊类型无效(1住院 2门诊): " + visitType);
        }
        Long visitId = dto.getVisitId() != null ? dto.getVisitId() : dto.getInpVisitId();
        if (visitId == null) {
            throw new BizException(400, "就诊ID(visitId/inpVisitId)不能为空");
        }
        Long inpVisitId = visitType == 1 ? visitId : null;

        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        /* 申请科室: 入参 > 当前登录科室 > 住院就诊科室 */
        Long applyDeptId = dto.getApplyDeptId();
        HisInpVisit inpVisit = null;
        if (applyDeptId == null) {
            applyDeptId = lu.getDeptId();
        }
        if (visitType == 1) {
            inpVisit = inpVisitMapper.selectById(inpVisitId);
            if (inpVisit == null) {
                throw new BizException(404, "住院就诊不存在");
            }
            if (applyDeptId == null) {
                applyDeptId = inpVisit.getDeptId();
            }
        }
        if (applyDeptId == null) {
            throw new BizException(400, "申请科室不能为空(入参/登录科室/就诊科室均未取到)");
        }
        if (applyDeptId.equals(dto.getTargetDeptId())) {
            throw new BizException(400, "受邀科室不能与申请科室相同");
        }
        /* 申请医师: 入参 > 当前登录职工 */
        Long applyDoctorId = dto.getApplyDoctorId() != null ? dto.getApplyDoctorId() : lu.getStaffId();
        if (applyDoctorId == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法发起会诊申请");
        }

        HisInpConsultation c = new HisInpConsultation();
        c.setOrgId(resolveOrgId(visitType, inpVisit));
        c.setVisitType(visitType);
        c.setVisitId(visitId);
        c.setInpVisitId(inpVisitId);
        c.setConsultType(dto.getConsultType() != null ? dto.getConsultType() : 1);
        c.setConsultCategory(dto.getConsultCategory());
        c.setApplyDeptId(applyDeptId);
        c.setApplyDoctorId(applyDoctorId);
        c.setTargetDeptId(dto.getTargetDeptId());
        c.setTargetDoctorId(dto.getTargetDoctorId());
        c.setApplyReason(dto.getApplyReason());
        c.setApplySummary(dto.getApplySummary());
        c.setUrgencyLevel(dto.getUrgencyLevel() != null ? dto.getUrgencyLevel() : URGENCY_NORMAL);
        c.setApplyTime(LocalDateTime.now());
        c.setStatus(STATUS_APPLY);
        c.setResponseDeadline(computeDeadline(c.getUrgencyLevel()));
        /* 冗余名称回填(科室/医师), 展示层免联查 */
        fillRedundantNames(c);
        consultationMapper.insert(c);
        log.info("会诊申请创建: id={}, visitType={}, visitId={}, targetDeptId={}, urgency={}, deadline={}",
                c.getId(), visitType, visitId, c.getTargetDeptId(), c.getUrgencyLevel(), c.getResponseDeadline());
        notifyDept(c.getTargetDeptId(), "会诊申请",
                String.format("收到%s会诊申请(%s), 请及时响应", urgencyLabel(c.getUrgencyLevel()), c.getApplyDeptName()), c, "apply");
        return c;
    }

    /**
     * 科室受理(1申请→2受理): 记录受理时间, 指定受邀专家(回填姓名), SSE 通知申请方。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpConsultation accept(Long id, Long targetDoctorId) {
        HisInpConsultation exist = requireConsultation(id);
        if (exist.getStatus() == null || exist.getStatus() != STATUS_APPLY) {
            throw new BizException("仅申请状态的会诊可受理, 当前状态: " + statusLabel(exist.getStatus()));
        }
        String targetDoctorName = targetDoctorId != null ? staffName(targetDoctorId) : null;
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, id)
                .eq(HisInpConsultation::getStatus, STATUS_APPLY)
                .set(HisInpConsultation::getStatus, STATUS_ACCEPTED)
                .set(HisInpConsultation::getResponseTime, LocalDateTime.now())
                .set(targetDoctorId != null, HisInpConsultation::getTargetDoctorId, targetDoctorId)
                .set(StringUtils.hasText(targetDoctorName), HisInpConsultation::getTargetDoctorName, targetDoctorName));
        if (affected == 0) {
            throw new BizException("会诊状态已变化请刷新后重试");
        }
        log.info("会诊受理: id={}, targetDoctorId={}", id, targetDoctorId);
        HisInpConsultation fresh = requireConsultation(id);
        notifyDept(fresh.getApplyDeptId(), "会诊已受理",
                String.format("%s已受理会诊申请%s", fresh.getTargetDeptName(),
                        StringUtils.hasText(targetDoctorName) ? "(专家:" + targetDoctorName + ")" : ""), fresh, "accept");
        return fresh;
    }

    /**
     * 完成会诊(2受理→3完成): 记录会诊时间与意见;
     * 住院会诊(visitType=1)自动生成会诊记录文书并回填 consult_record_id
     * (文书生成失败仅告警不阻断会诊完成主流程); SSE 通知申请方。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpConsultation complete(Long id, String opinion) {
        if (!StringUtils.hasText(opinion)) {
            throw new BizException(400, "会诊意见不能为空");
        }
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, id)
                .eq(HisInpConsultation::getStatus, STATUS_ACCEPTED)
                .set(HisInpConsultation::getStatus, STATUS_COMPLETED)
                .set(HisInpConsultation::getConsultTime, LocalDateTime.now())
                .set(HisInpConsultation::getConsultOpinion, opinion));
        if (affected == 0) {
            throw new BizException("仅已受理的会诊可完成, 状态已变化请刷新后重试");
        }
        log.info("会诊完成: id={}", id);
        HisInpConsultation fresh = requireConsultation(id);
        try {
            autoCreateRecord(id);
        } catch (Exception e) {
            log.warn("会诊记录文书自动生成失败(不阻断会诊完成): consultId={}, 原因={}", id, e.getMessage(), e);
        }
        notifyDept(fresh.getApplyDeptId(), "会诊已完成",
                String.format("%s已完成会诊并出具意见", fresh.getTargetDeptName()), fresh, "complete");
        return requireConsultation(id);
    }

    /**
     * 拒绝(1申请或2受理→4拒绝): 拒绝原因写入会诊意见字段留痕(与旧链路口径一致), SSE 通知申请方。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpConsultation reject(Long id, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "拒绝原因不能为空");
        }
        HisInpConsultation exist = requireConsultation(id);
        if (exist.getStatus() == null
                || (exist.getStatus() != STATUS_APPLY && exist.getStatus() != STATUS_ACCEPTED)) {
            throw new BizException("仅申请/受理状态的会诊可拒绝, 当前状态: " + statusLabel(exist.getStatus()));
        }
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, id)
                .in(HisInpConsultation::getStatus, STATUS_APPLY, STATUS_ACCEPTED)
                .set(HisInpConsultation::getStatus, STATUS_REJECTED)
                .set(HisInpConsultation::getResponseTime, LocalDateTime.now())
                .set(HisInpConsultation::getConsultOpinion, "【拒绝原因】" + reason));
        if (affected == 0) {
            throw new BizException("会诊状态已变化请刷新后重试");
        }
        log.info("会诊拒绝: id={}, reason={}", id, reason);
        HisInpConsultation fresh = requireConsultation(id);
        notifyDept(fresh.getApplyDeptId(), "会诊被拒绝", String.format("%s拒绝了会诊申请: %s",
                fresh.getTargetDeptName(), reason), fresh, "reject");
        return fresh;
    }

    /**
     * 取消(1申请→5取消): 仅申请方可取消且未受理。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpConsultation cancel(Long id) {
        HisInpConsultation exist = requireConsultation(id);
        if (exist.getStatus() == null || exist.getStatus() != STATUS_APPLY) {
            throw new BizException("仅申请状态且未受理的会诊可取消, 当前状态: " + statusLabel(exist.getStatus()));
        }
        Long currentStaff = currentStaffIdOrNull();
        /* 严格归属: 存量无申请医师的记录放行取消(无归属方), 其余仅申请人本人 */
        if (exist.getApplyDoctorId() != null && !exist.getApplyDoctorId().equals(currentStaff)) {
            throw new BizException(403, "仅申请人本人可取消该会诊申请");
        }
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, id)
                .eq(HisInpConsultation::getStatus, STATUS_APPLY)
                .set(HisInpConsultation::getStatus, STATUS_CANCELLED));
        if (affected == 0) {
            throw new BizException("会诊状态已变化请刷新后重试");
        }
        log.info("会诊取消: id={}, applyDoctorId={}", id, exist.getApplyDoctorId());
        HisInpConsultation fresh = requireConsultation(id);
        notifyDept(fresh.getTargetDeptId(), "会诊已取消",
                String.format("%s取消了会诊申请", fresh.getApplyDeptName()), fresh, "cancel");
        return fresh;
    }

    /* ==================== 文书/医嘱/评价扩展 ==================== */

    /**
     * 会诊完成后自动生成会诊记录文书(仅住院会诊 visitType=1):
     * record_type=15(会诊记录), 预填病情摘要/申请理由/会诊意见/医师科室等信息为纯文本内容,
     * 经 InpMedRecordService.create 标准链路落库(草稿态+质控+要素同步), 成功后回填 consult_record_id;
     * 幂等: 已有 consult_record_id 直接返回。非住院/缺就诊ID返回 null 不建档。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long autoCreateRecord(Long consultId) {
        HisInpConsultation c = requireConsultation(consultId);
        if (c.getConsultRecordId() != null) {
            return c.getConsultRecordId();
        }
        if (c.getVisitType() == null || c.getVisitType() != 1) {
            log.info("门诊会诊不自动生成住院文书: consultId={}, visitType={}", consultId, c.getVisitType());
            return null;
        }
        Long inpVisitId = c.getInpVisitId() != null ? c.getInpVisitId() : c.getVisitId();
        if (inpVisitId == null) {
            log.warn("住院会诊缺就诊ID, 跳过文书生成: consultId={}", consultId);
            return null;
        }
        InpMedRecordDTO dto = new InpMedRecordDTO();
        dto.setInpVisitId(inpVisitId);
        dto.setRecordType(RECORD_TYPE_CONSULT);
        dto.setTitle(buildRecordTitle(c));
        dto.setContent(buildRecordContent(c));
        HisInpMedicalRecord rec = medRecordService.create(dto);
        consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, consultId)
                .set(HisInpConsultation::getConsultRecordId, rec.getId()));
        log.info("会诊记录文书自动生成: consultId={}, recordId={}, visitId={}", consultId, rec.getId(), inpVisitId);
        return rec.getId();
    }

    /** 会诊医嘱关联: 回写 order_id(会诊意见落实为医嘱的溯源关系)。 */
    @Transactional(rollbackFor = Exception.class)
    public void linkToOrder(Long consultId, Long orderId) {
        if (consultId == null) {
            throw new BizException(400, "consultId不能为空");
        }
        if (orderId == null) {
            throw new BizException(400, "orderId不能为空");
        }
        requireConsultation(consultId);
        consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, consultId)
                .set(HisInpConsultation::getOrderId, orderId));
        log.info("会诊医嘱关联: consultId={}, orderId={}", consultId, orderId);
    }

    /**
     * 双向评价: evaluatorType=applicant 写发起方评分, invitee 写受邀方评分;
     * 仅已完成(status=3)可评价, 评分 1-5; 同方重复评价以最后一次为准; 评价时间取当前。
     */
    @Transactional(rollbackFor = Exception.class)
    public void evaluate(Long id, String evaluatorType, Integer score, String note) {
        if (!"applicant".equals(evaluatorType) && !"invitee".equals(evaluatorType)) {
            throw new BizException(400, "评价方类型无效(applicant/invitee): " + evaluatorType);
        }
        if (score == null || score < 1 || score > 5) {
            throw new BizException(400, "评分必须为1-5的整数");
        }
        HisInpConsultation exist = requireConsultation(id);
        if (exist.getStatus() == null || exist.getStatus() != STATUS_COMPLETED) {
            throw new BizException("仅已完成的会诊可评价, 当前状态: " + statusLabel(exist.getStatus()));
        }
        LambdaUpdateWrapper<HisInpConsultation> uw = new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, id)
                .set(HisInpConsultation::getEvalTime, LocalDateTime.now());
        if ("applicant".equals(evaluatorType)) {
            uw.set(HisInpConsultation::getEvalByApplicant, score)
                    .set(HisInpConsultation::getEvalByApplicantNote, note);
        } else {
            uw.set(HisInpConsultation::getEvalByInvitee, score)
                    .set(HisInpConsultation::getEvalByInviteeNote, note);
        }
        consultationMapper.update(null, uw);
        log.info("会诊评价: id={}, evaluatorType={}, score={}", id, evaluatorType, score);
    }

    /**
     * 催促会诊(1申请或2受理): 申请方向受邀科室重发催促提醒(SSE), 通知即留痕(日志);
     * 不改状态/不改响应时限, 已完成/拒绝/取消的会诊不可催促。
     */
    public void urge(Long id) {
        HisInpConsultation exist = requireConsultation(id);
        if (exist.getStatus() == null
                || (exist.getStatus() != STATUS_APPLY && exist.getStatus() != STATUS_ACCEPTED)) {
            throw new BizException("仅申请/受理状态的会诊可催促, 当前状态: " + statusLabel(exist.getStatus()));
        }
        notifyDept(exist.getTargetDeptId(), "会诊催促提醒",
                String.format("%s催促贵科尽快处理会诊申请(%s, 申请时间 %s)", exist.getApplyDeptName(),
                        urgencyLabel(exist.getUrgencyLevel()),
                        exist.getApplyTime() == null ? "-" : TS_FMT.format(exist.getApplyTime())), exist, "urge");
        log.info("会诊催促: id={}, applyDeptId={}, targetDeptId={}", id, exist.getApplyDeptId(), exist.getTargetDeptId());
    }

    /* ==================== 超时预警调度 ==================== */

    /**
     * 超时检查(每分钟): 扫描 status IN (1,2) 且已过响应截止时间且未通知的会诊,
     * 标记 timeout_notified=1 并向申请科室/受邀科室推送超时预警 SSE。
     * 调度线程无请求上下文, 按 JdbcTemplate 扫描出的租户逐个显式设置/清理 TenantContext
     * (与 SurgeryNotifyJob 同模式), 单租户/单条失败互不影响。
     */
    @Scheduled(fixedRate = 60_000)
    public void checkTimeout() {
        List<Long> tenants;
        try {
            tenants = jdbcTemplate.queryForList(
                    "SELECT DISTINCT tenant_id FROM his_inp_consultation"
                            + " WHERE deleted = 0 AND tenant_id > 0 AND status IN (1, 2)"
                            + " AND response_deadline IS NOT NULL AND response_deadline < NOW()"
                            + " AND (timeout_notified IS NULL OR timeout_notified = 0)"
                            + " ORDER BY tenant_id", Long.class);
        } catch (Exception e) {
            log.warn("会诊超时调度跳过(表未就绪): {}", e.getMessage());
            return;
        }
        if (tenants.isEmpty()) {
            return;
        }
        int total = 0;
        for (Long tenantId : tenants) {
            try {
                TenantContext.set(tenantId);
                total += notifyTimeoutOfTenant();
            } catch (Exception e) {
                log.error("【会诊超时告警】租户{}扫描失败: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        if (total > 0) {
            log.info("会诊超时预警完成: 本轮通知{}条", total);
        }
    }

    /** 单租户超时扫描: 逐条乐观标记 + SSE 预警, 单条 try-catch 独立处理。 */
    private int notifyTimeoutOfTenant() {
        List<HisInpConsultation> overdue = consultationMapper.selectList(
                new LambdaQueryWrapper<HisInpConsultation>()
                        .in(HisInpConsultation::getStatus, STATUS_APPLY, STATUS_ACCEPTED)
                        .isNotNull(HisInpConsultation::getResponseDeadline)
                        .lt(HisInpConsultation::getResponseDeadline, LocalDateTime.now())
                        .and(w -> w.eq(HisInpConsultation::getTimeoutNotified, 0)
                                .or().isNull(HisInpConsultation::getTimeoutNotified)));
        if (overdue.isEmpty()) {
            return 0;
        }
        int notified = 0;
        for (HisInpConsultation c : overdue) {
            try {
                int affected = consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                        .eq(HisInpConsultation::getId, c.getId())
                        .eq(HisInpConsultation::getStatus, c.getStatus())
                        .and(w -> w.eq(HisInpConsultation::getTimeoutNotified, 0).or().isNull(HisInpConsultation::getTimeoutNotified))
                        .set(HisInpConsultation::getTimeoutNotified, 1));
                if (affected == 0) {
                    continue;
                }
                String msg = String.format("会诊%s已超过响应时限(%s), %s仍未%s",
                        urgencyLabel(c.getUrgencyLevel()),
                        c.getResponseDeadline() == null ? "-" : TS_FMT.format(c.getResponseDeadline()),
                        c.getTargetDeptName(),
                        c.getStatus() != null && c.getStatus() == STATUS_ACCEPTED ? "完成会诊" : "受理");
                notifyDept(c.getApplyDeptId(), "会诊超时预警", msg, c, "timeout");
                notifyDept(c.getTargetDeptId(), "会诊超时预警", msg, c, "timeout");
                notified++;
            } catch (Exception e) {
                log.error("【会诊超时告警】单条处理失败: consultId={}, 原因={}", c.getId(), e.getMessage(), e);
            }
        }
        return notified;
    }

    /* ==================== 查询与统计 ==================== */

    /** 按就诊查询: visit_type + visit_id 口径, 住院类型兼容旧 inp_visit_id 存量数据。 */
    public List<HisInpConsultation> listByVisit(Integer visitType, Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "visitId不能为空");
        }
        LambdaQueryWrapper<HisInpConsultation> qw = new LambdaQueryWrapper<HisInpConsultation>()
                .orderByDesc(HisInpConsultation::getApplyTime);
        if (visitType == null) {
            qw.and(w -> w.eq(HisInpConsultation::getVisitId, visitId)
                    .or().eq(HisInpConsultation::getInpVisitId, visitId));
        } else if (visitType == 1) {
            qw.and(w -> w.eq(HisInpConsultation::getVisitType, 1)
                    .and(w2 -> w2.eq(HisInpConsultation::getVisitId, visitId)
                            .or().eq(HisInpConsultation::getInpVisitId, visitId)));
        } else {
            qw.eq(HisInpConsultation::getVisitType, visitType)
                    .eq(HisInpConsultation::getVisitId, visitId);
        }
        return consultationMapper.selectList(qw);
    }

    /**
     * 列表查询(多条件分页): 就诊类型/申请科室/受邀科室/会诊分类/紧急程度/状态/申请时间区间, 申请时间倒序。
     */
    public IPage<HisInpConsultation> list(Integer visitType, Long applyDeptId, Long targetDeptId,
                                          String consultCategory, Integer urgencyLevel, Integer status,
                                          String startDate, String endDate, int page, int size) {
        LambdaQueryWrapper<HisInpConsultation> qw = new LambdaQueryWrapper<HisInpConsultation>()
                .orderByDesc(HisInpConsultation::getApplyTime);
        qw.eq(visitType != null, HisInpConsultation::getVisitType, visitType);
        qw.eq(applyDeptId != null, HisInpConsultation::getApplyDeptId, applyDeptId);
        qw.eq(targetDeptId != null, HisInpConsultation::getTargetDeptId, targetDeptId);
        qw.eq(StringUtils.hasText(consultCategory), HisInpConsultation::getConsultCategory, consultCategory);
        qw.eq(urgencyLevel != null, HisInpConsultation::getUrgencyLevel, urgencyLevel);
        qw.eq(status != null, HisInpConsultation::getStatus, status);
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (start != null) {
            qw.ge(HisInpConsultation::getApplyTime, start.atStartOfDay());
        }
        if (end != null) {
            qw.le(HisInpConsultation::getApplyTime, end.atTime(23, 59, 59));
        }
        return consultationMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)), qw);
    }

    /**
     * 会诊运营统计(JdbcTemplate 直查, 显式 tenant_id 过滤):
     * 总数/状态分布/类型分布/紧急程度分布/平均响应分钟数/超时率/申请与受邀科室 Top10/申请医师 Top10/
     * 急会诊10分钟响应达标率/双向评价均分。日期区间按申请时间过滤; deptId 命中申请或受邀任一侧。
     */
    public Map<String, Object> statistics(Long deptId, String startDate, String endDate) {
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        StringBuilder where = new StringBuilder(" WHERE deleted = 0");
        List<Object> args = new ArrayList<>();
        Long tid = TenantContext.get();
        if (tid != null) {
            where.append(" AND tenant_id = ?");
            args.add(tid);
        }
        if (start != null) {
            where.append(" AND apply_time >= ?");
            args.add(start.atStartOfDay());
        }
        if (end != null) {
            where.append(" AND apply_time <= ?");
            args.add(end.atTime(23, 59, 59));
        }
        if (deptId != null) {
            where.append(" AND (apply_dept_id = ? OR target_dept_id = ?)");
            args.add(deptId);
            args.add(deptId);
        }
        String w = where.toString();
        Map<String, Object> result = new LinkedHashMap<>();
        /* 总数与状态/类型/紧急程度分布 */
        result.put("total", count(jdbcTemplate, "SELECT COUNT(*) FROM his_inp_consultation" + w, args));
        result.put("byStatus", jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) cnt FROM his_inp_consultation" + w + " GROUP BY status ORDER BY status", args.toArray()));
        result.put("byType", jdbcTemplate.queryForList(
                "SELECT consult_type, COUNT(*) cnt FROM his_inp_consultation" + w + " GROUP BY consult_type ORDER BY consult_type", args.toArray()));
        result.put("byUrgency", jdbcTemplate.queryForList(
                "SELECT urgency_level, COUNT(*) cnt FROM his_inp_consultation" + w + " GROUP BY urgency_level ORDER BY urgency_level", args.toArray()));
        /* 响应效率: 平均响应分钟数(受理或拒绝即视为已响应) */
        Double avgResponse = queryDouble(jdbcTemplate,
                "SELECT AVG(TIMESTAMPDIFF(MINUTE, apply_time, response_time)) FROM his_inp_consultation"
                        + w + " AND response_time IS NOT NULL", args);
        result.put("avgResponseMinutes", avgResponse == null ? 0 : Math.round(avgResponse * 10) / 10.0);
        /* 超时率: 超时已通知占比 */
        long totalCnt = count(jdbcTemplate, "SELECT COUNT(*) FROM his_inp_consultation" + w, args);
        long timeoutCnt = count(jdbcTemplate, "SELECT COUNT(*) FROM his_inp_consultation" + w
                + " AND timeout_notified = 1", args);
        result.put("timeoutCount", timeoutCnt);
        result.put("timeoutRate", totalCnt == 0 ? 0.0 : Math.round(timeoutCnt * 1000.0 / totalCnt) / 10.0);
        /* 科室/医师排行(Top10): 冗余名称列直接分组展示 */
        result.put("topApplyDepts", jdbcTemplate.queryForList(
                "SELECT apply_dept_id deptId, apply_dept_name deptName, COUNT(*) cnt FROM his_inp_consultation"
                        + w + " AND apply_dept_id IS NOT NULL GROUP BY apply_dept_id, apply_dept_name ORDER BY cnt DESC LIMIT 10", args.toArray()));
        result.put("topTargetDepts", jdbcTemplate.queryForList(
                "SELECT target_dept_id deptId, target_dept_name deptName, COUNT(*) cnt FROM his_inp_consultation"
                        + w + " AND target_dept_id IS NOT NULL GROUP BY target_dept_id, target_dept_name ORDER BY cnt DESC LIMIT 10", args.toArray()));
        result.put("topDoctors", jdbcTemplate.queryForList(
                "SELECT apply_doctor_id doctorId, apply_doctor_name doctorName, COUNT(*) cnt FROM his_inp_consultation"
                        + w + " AND apply_doctor_name IS NOT NULL GROUP BY apply_doctor_id, apply_doctor_name ORDER BY cnt DESC LIMIT 10", args.toArray()));
        /* 急会诊(急/特急)10分钟响应达标率: 已响应(受理)中 10 分钟内受理占比 */
        long urgentResponded = count(jdbcTemplate, "SELECT COUNT(*) FROM his_inp_consultation" + w
                + " AND urgency_level IN (2, 3) AND response_time IS NOT NULL", args);
        long urgentOnTime = count(jdbcTemplate, "SELECT COUNT(*) FROM his_inp_consultation" + w
                + " AND urgency_level IN (2, 3) AND response_time IS NOT NULL"
                + " AND TIMESTAMPDIFF(MINUTE, apply_time, response_time) <= 10", args);
        result.put("urgentResponded", urgentResponded);
        result.put("urgentOnTime", urgentOnTime);
        result.put("urgentOnTimeRate", urgentResponded == 0 ? 100.0 : Math.round(urgentOnTime * 1000.0 / urgentResponded) / 10.0);
        /* 双向评价均分(1-5) */
        Double avgApplicant = queryDouble(jdbcTemplate,
                "SELECT AVG(eval_by_applicant) FROM his_inp_consultation" + w
                        + " AND eval_by_applicant IS NOT NULL", args);
        Double avgInvitee = queryDouble(jdbcTemplate,
                "SELECT AVG(eval_by_invitee) FROM his_inp_consultation" + w
                        + " AND eval_by_invitee IS NOT NULL", args);
        result.put("avgEvalApplicant", avgApplicant == null ? null : Math.round(avgApplicant * 10) / 10.0);
        result.put("avgEvalInvitee", avgInvitee == null ? null : Math.round(avgInvitee * 10) / 10.0);
        return result;
    }

    /** 详情(不存在抛 404)。 */
    public HisInpConsultation detail(Long id) {
        return requireConsultation(id);
    }

    /* ==================== 私有助手 ==================== */

    /** 会诊单必存(404 兜底)。 */
    private HisInpConsultation requireConsultation(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        HisInpConsultation c = consultationMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "会诊记录不存在");
        }
        return c;
    }

    /** 机构归属: 住院跟随就诊机构, 其余(门诊)回落当前登录机构。 */
    private Long resolveOrgId(int visitType, HisInpVisit inpVisit) {
        if (visitType == 1 && inpVisit != null && inpVisit.getOrgId() != null) {
            return inpVisit.getOrgId();
        }
        return guard.currentOrgId();
    }

    /** 响应截止时间: 特急=即刻, 急=10分钟, 普通=24小时。 */
    private LocalDateTime computeDeadline(Integer urgencyLevel) {
        int u = urgencyLevel == null ? URGENCY_NORMAL : urgencyLevel;
        LocalDateTime now = LocalDateTime.now();
        if (u == URGENCY_EXTRA) {
            return now;
        }
        if (u == URGENCY_URGENT) {
            return now.plusMinutes(URGENT_DEADLINE_MINUTES);
        }
        return now.plusHours(NORMAL_DEADLINE_HOURS);
    }

    /** 冗余名称回填: 申请/受邀科室名 + 申请/受邀医师名(查不到留空, 不阻断)。 */
    private void fillRedundantNames(HisInpConsultation c) {
        if (c.getApplyDeptId() != null) {
            HisDept dept = deptMapper.selectById(c.getApplyDeptId());
            c.setApplyDeptName(dept != null ? dept.getDeptName() : null);
        }
        if (c.getTargetDeptId() != null) {
            HisDept dept = deptMapper.selectById(c.getTargetDeptId());
            c.setTargetDeptName(dept != null ? dept.getDeptName() : null);
        }
        if (c.getApplyDoctorId() != null) {
            c.setApplyDoctorName(staffName(c.getApplyDoctorId()));
        }
        if (c.getTargetDoctorId() != null) {
            c.setTargetDoctorName(staffName(c.getTargetDoctorId()));
        }
    }

    /** 职工姓名(查不到返回 null)。 */
    private String staffName(Long staffId) {
        if (staffId == null) {
            return null;
        }
        HisStaff s = staffMapper.selectById(staffId);
        return s != null ? s.getStaffName() : null;
    }

    /** SSE 科室定向通知: 经 EmrEventPublisher 领域事件解耦(CONSULTATION_UPDATE, 失败静默)。 */
    private void notifyDept(Long deptId, String title, String message, HisInpConsultation c, String action) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("action", action);
            data.put("title", title);
            data.put("message", message);
            data.put("consultId", c.getId());
            data.put("visitType", c.getVisitType());
            data.put("visitId", c.getVisitId());
            data.put("urgencyLevel", c.getUrgencyLevel());
            data.put("status", c.getStatus());
            eventPublisher.publishToDept(deptId, EmrEventType.CONSULTATION_UPDATE, data);
        } catch (Exception e) {
            log.debug("会诊SSE通知失败(静默): deptId={}, consultId={}, 原因={}", deptId, c.getId(), e.getMessage());
        }
    }

    /** 会诊记录文书标题: 会诊记录-受邀科室-yyyy-MM-dd HH:mm。 */
    private String buildRecordTitle(HisInpConsultation c) {
        String dept = StringUtils.hasText(c.getTargetDeptName()) ? c.getTargetDeptName() : "会诊";
        return "会诊记录-" + dept + "-" + TS_FMT.format(c.getConsultTime() != null ? c.getConsultTime() : LocalDateTime.now());
    }

    /** 会诊记录文书纯文本内容(不以'<'开头, 走非HTML通道原样落库)。 */
    private String buildRecordContent(HisInpConsultation c) {
        StringBuilder sb = new StringBuilder();
        sb.append("【会诊记录】\n");
        sb.append("会诊类型: ").append(consultTypeLabel(c.getConsultType()))
                .append(c.getConsultCategory() != null ? "(" + c.getConsultCategory() + ")" : "").append("\n");
        sb.append("紧急程度: ").append(urgencyLabel(c.getUrgencyLevel())).append("\n");
        sb.append("申请科室: ").append(nvl(c.getApplyDeptName())).append("  申请医师: ").append(nvl(c.getApplyDoctorName())).append("\n");
        sb.append("受邀科室: ").append(nvl(c.getTargetDeptName())).append("  受邀医师: ").append(nvl(c.getTargetDoctorName())).append("\n");
        sb.append("申请时间: ").append(c.getApplyTime() == null ? "-" : TS_FMT.format(c.getApplyTime())).append("\n");
        sb.append("受理时间: ").append(c.getResponseTime() == null ? "-" : TS_FMT.format(c.getResponseTime())).append("\n");
        sb.append("会诊时间: ").append(c.getConsultTime() == null ? "-" : TS_FMT.format(c.getConsultTime())).append("\n");
        if (StringUtils.hasText(c.getApplySummary())) {
            sb.append("病情摘要: ").append(c.getApplySummary()).append("\n");
        }
        sb.append("申请理由: ").append(nvl(c.getApplyReason())).append("\n");
        sb.append("会诊意见: ").append(nvl(c.getConsultOpinion())).append("\n");
        return sb.toString();
    }

    /** 当前登录职工ID(未登录/无职工关联返回 null, 供归属校验)。 */
    private Long currentStaffIdOrNull() {
        LoginUser lu = UserContext.get();
        return lu != null ? lu.getStaffId() : null;
    }

    /** 日期参数解析(yyyy-MM-dd, 空返回 null, 非法抛 400)。 */
    private LocalDate parseDate(String text, String param) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (DateTimeParseException e) {
            throw new BizException(400, param + "格式无效, 应为yyyy-MM-dd: " + text);
        }
    }

    private static String nvl(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    /** 紧急程度中文标签。 */
    private static String urgencyLabel(Integer urgencyLevel) {
        if (urgencyLevel == null) {
            return "普通";
        }
        switch (urgencyLevel) {
            case URGENCY_URGENT:
                return "急会诊";
            case URGENCY_EXTRA:
                return "特急会诊";
            default:
                return "普通";
        }
    }

    /** 会诊类型中文标签。 */
    private static String consultTypeLabel(Integer consultType) {
        if (consultType == null) {
            return "普通会诊";
        }
        switch (consultType) {
            case 2:
                return "急会诊";
            case 3:
                return "MDT多学科会诊";
            default:
                return "普通会诊";
        }
    }

    /** 状态中文标签。 */
    private static String statusLabel(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case STATUS_APPLY:
                return "申请";
            case STATUS_ACCEPTED:
                return "受理";
            case STATUS_COMPLETED:
                return "完成";
            case STATUS_REJECTED:
                return "拒绝";
            case STATUS_CANCELLED:
                return "取消";
            default:
                return String.valueOf(status);
        }
    }

    /** JdbcTemplate 单值计数。 */
    private static long count(JdbcTemplate jt, String sql, List<Object> args) {
        Long v = jt.queryForObject(sql, Long.class, args.toArray());
        return v != null ? v : 0L;
    }

    /** JdbcTemplate 单值数值(空集返回 null)。 */
    private static Double queryDouble(JdbcTemplate jt, String sql, List<Object> args) {
        List<Double> rows = jt.queryForList(sql, Double.class, args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }
}
