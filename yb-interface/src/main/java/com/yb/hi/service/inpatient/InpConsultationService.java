package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.ConsultationApplyDTO;
import com.yb.hi.entity.inpatient.HisInpConsultation;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpConsultationMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 住院会诊服务: 申请→受理→完成/拒绝/取消状态机闭环(1申请 2受理 3完成 4拒绝 5取消)。
 * 状态流转均采用乐观更新(WHERE status=旧值), 并发冲突提示刷新重试。
 */
@Slf4j
@Service
public class InpConsultationService {

    private final HisInpConsultationMapper consultationMapper;
    private final HisInpVisitMapper visitMapper;
    private final OrgAccessGuard guard;

    public InpConsultationService(HisInpConsultationMapper consultationMapper,
                                  HisInpVisitMapper visitMapper, OrgAccessGuard guard) {
        this.consultationMapper = consultationMapper;
        this.visitMapper = visitMapper;
        this.guard = guard;
    }

    /** 会诊分页(visitId/status 可选筛选, 申请时间倒序) */
    public R<IPage<HisInpConsultation>> list(Long visitId, Integer status, Page<HisInpConsultation> page) {
        Page<HisInpConsultation> p = page != null ? page : new Page<>(1, 10);
        LambdaQueryWrapper<HisInpConsultation> qw = new LambdaQueryWrapper<HisInpConsultation>()
                .orderByDesc(HisInpConsultation::getApplyTime);
        if (visitId != null) {
            qw.eq(HisInpConsultation::getInpVisitId, visitId);
        }
        if (status != null) {
            qw.eq(HisInpConsultation::getStatus, status);
        }
        IPage<HisInpConsultation> result = consultationMapper.selectPage(p, qw);
        return R.ok(result);
    }

    /** 发起会诊申请(status=1, 申请科室取就诊科室, 申请医师取当前登录职工) */
    @Transactional(rollbackFor = Exception.class)
    public R<HisInpConsultation> apply(ConsultationApplyDTO dto) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "inpVisitId不能为空");
        }
        if (dto.getTargetDeptId() == null) {
            throw new BizException(400, "受邀科室不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(dto.getInpVisitId());
        if (visit == null) {
            throw new BizException(404, "住院就诊不存在");
        }
        HisInpConsultation c = new HisInpConsultation();
        c.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : guard.currentOrgId());
        c.setInpVisitId(dto.getInpVisitId());
        c.setConsultType(dto.getConsultType() != null ? dto.getConsultType() : 1);
        c.setApplyDeptId(visit.getDeptId());
        c.setApplyDoctorId(currentStaffId());
        c.setTargetDeptId(dto.getTargetDeptId());
        c.setTargetDoctorId(dto.getTargetDoctorId());
        c.setApplyReason(dto.getApplyReason());
        c.setUrgencyLevel(dto.getUrgencyLevel() != null ? dto.getUrgencyLevel() : 1);
        c.setApplyTime(LocalDateTime.now());
        c.setStatus(1);
        consultationMapper.insert(c);
        log.info("发起会诊申请: id={}, visitId={}, type={}, targetDeptId={}",
                c.getId(), dto.getInpVisitId(), c.getConsultType(), dto.getTargetDeptId());
        return R.ok(c);
    }

    /** 受理(1申请→2受理, 记录受理时间) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> accept(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, id)
                .eq(HisInpConsultation::getStatus, 1)
                .set(HisInpConsultation::getStatus, 2)
                .set(HisInpConsultation::getResponseTime, LocalDateTime.now()));
        if (affected == 0) {
            throw new BizException("仅申请状态的会诊可受理, 状态已变化请刷新后重试");
        }
        log.info("会诊受理: id={}", id);
        return R.ok();
    }

    /** 完成(2受理→3完成, 记录会诊时间与意见) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> complete(Long id, String opinion) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        if (!StringUtils.hasText(opinion)) {
            throw new BizException(400, "会诊意见不能为空");
        }
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, id)
                .eq(HisInpConsultation::getStatus, 2)
                .set(HisInpConsultation::getStatus, 3)
                .set(HisInpConsultation::getConsultTime, LocalDateTime.now())
                .set(HisInpConsultation::getConsultOpinion, opinion));
        if (affected == 0) {
            throw new BizException("仅已受理的会诊可完成, 状态已变化请刷新后重试");
        }
        log.info("会诊完成: id={}", id);
        return R.ok();
    }

    /** 拒绝(1申请→4拒绝, 拒绝原因写入会诊意见字段留痕) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> reject(Long id, String reason) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "拒绝原因不能为空");
        }
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, id)
                .eq(HisInpConsultation::getStatus, 1)
                .set(HisInpConsultation::getStatus, 4)
                .set(HisInpConsultation::getResponseTime, LocalDateTime.now())
                .set(HisInpConsultation::getConsultOpinion, "【拒绝原因】" + reason));
        if (affected == 0) {
            throw new BizException("仅申请状态的会诊可拒绝, 状态已变化请刷新后重试");
        }
        log.info("会诊拒绝: id={}, reason={}", id, reason);
        return R.ok();
    }

    /** 取消(1申请→5取消, 仅申请人本人可取消) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> cancel(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        HisInpConsultation exist = consultationMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "会诊记录不存在");
        }
        if (!currentStaffId().equals(exist.getApplyDoctorId())) {
            throw new BizException(403, "仅申请人本人可取消该会诊申请");
        }
        int affected = consultationMapper.update(null, new LambdaUpdateWrapper<HisInpConsultation>()
                .eq(HisInpConsultation::getId, id)
                .eq(HisInpConsultation::getStatus, 1)
                .set(HisInpConsultation::getStatus, 5));
        if (affected == 0) {
            throw new BizException("仅申请状态的会诊可取消, 状态已变化请刷新后重试");
        }
        log.info("会诊取消: id={}, applyDoctorId={}", id, exist.getApplyDoctorId());
        return R.ok();
    }

    /** 当前登录职工ID(无职工关联的账号不能执行会诊操作) */
    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.getStaffId() == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行会诊操作");
        }
        return lu.getStaffId();
    }
}
