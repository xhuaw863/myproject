package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.TransferApplyDTO;
import com.yb.hi.entity.inpatient.HisBed;
import com.yb.hi.entity.inpatient.HisInpTransfer;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisBedMapper;
import com.yb.hi.mapper.inpatient.HisInpTransferMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 住院转科/转床/加床服务: 申请→批准→执行闭环(1申请 2批准 3拒绝 4已执行 5取消)。
 * 执行顺序与 InpVisitService.transfer 一致: 先占新床 → 再释旧床, 占用失败整体回滚不丢床位。
 */
@Slf4j
@Service
public class InpTransferService {

    private final HisInpTransferMapper transferMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisBedMapper bedMapper;
    private final InpBedService bedService;
    private final OrgAccessGuard guard;

    public InpTransferService(HisInpTransferMapper transferMapper, HisInpVisitMapper visitMapper,
                              HisBedMapper bedMapper, InpBedService bedService, OrgAccessGuard guard) {
        this.transferMapper = transferMapper;
        this.visitMapper = visitMapper;
        this.bedMapper = bedMapper;
        this.bedService = bedService;
        this.guard = guard;
    }

    /** 转科/转床申请分页(visitId/status 可选筛选, 申请时间倒序) */
    public R<IPage<HisInpTransfer>> list(Long visitId, Integer status, Page<HisInpTransfer> page) {
        Page<HisInpTransfer> p = page != null ? page : new Page<>(1, 10);
        LambdaQueryWrapper<HisInpTransfer> qw = new LambdaQueryWrapper<HisInpTransfer>()
                .orderByDesc(HisInpTransfer::getApplyTime);
        if (visitId != null) {
            qw.eq(HisInpTransfer::getInpVisitId, visitId);
        }
        if (status != null) {
            qw.eq(HisInpTransfer::getStatus, status);
        }
        IPage<HisInpTransfer> result = transferMapper.selectPage(p, qw);
        return R.ok(result);
    }

    /** 发起转科/转床/加床申请(status=1, 原病区/床位/科室由就诊在院信息带出) */
    @Transactional(rollbackFor = Exception.class)
    public R<HisInpTransfer> apply(TransferApplyDTO dto) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "inpVisitId不能为空");
        }
        Integer type = dto.getTransferType();
        if (type == null || type < 1 || type > 3) {
            throw new BizException(400, "申请类型不合法(1转科 2转床 3加床)");
        }
        if ((type == 2 || type == 3) && dto.getToBedId() == null) {
            throw new BizException(400, "转床/加床需指定目标床位");
        }
        if (type == 1 && dto.getToDeptId() == null) {
            throw new BizException(400, "转科需指定目标科室");
        }
        HisInpVisit visit = visitMapper.selectById(dto.getInpVisitId());
        if (visit == null) {
            throw new BizException(404, "住院就诊不存在");
        }
        HisInpTransfer t = new HisInpTransfer();
        t.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : guard.currentOrgId());
        t.setInpVisitId(dto.getInpVisitId());
        t.setTransferType(type);
        t.setFromWardId(visit.getWardId());
        t.setFromBedId(visit.getBedId());
        t.setFromDeptId(visit.getDeptId());
        t.setToWardId(dto.getToWardId());
        t.setToBedId(dto.getToBedId());
        t.setToDeptId(dto.getToDeptId());
        t.setReason(dto.getReason());
        t.setApplyDoctorId(dto.getApplyDoctorId() != null ? dto.getApplyDoctorId() : currentStaffId());
        t.setApplyTime(LocalDateTime.now());
        t.setStatus(1);
        transferMapper.insert(t);
        log.info("发起{}申请: id={}, visitId={}, fromBedId={}, toBedId={}, toDeptId={}",
                transferTypeName(type), t.getId(), dto.getInpVisitId(),
                t.getFromBedId(), t.getToBedId(), t.getToDeptId());
        return R.ok(t);
    }

    /** 批准(1申请→2批准, 记录审批人与审批时间) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> approve(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        int affected = transferMapper.update(null, new LambdaUpdateWrapper<HisInpTransfer>()
                .eq(HisInpTransfer::getId, id)
                .eq(HisInpTransfer::getStatus, 1)
                .set(HisInpTransfer::getStatus, 2)
                .set(HisInpTransfer::getApproveDoctorId, currentStaffId())
                .set(HisInpTransfer::getApproveTime, LocalDateTime.now()));
        if (affected == 0) {
            throw new BizException("仅申请状态的转科/转床申请可批准, 状态已变化请刷新后重试");
        }
        log.info("转科/转床申请批准: id={}", id);
        return R.ok();
    }

    /** 拒绝(1申请→3拒绝, 驳回原因追加至申请原因字段留痕) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> reject(Long id, String reason) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "拒绝原因不能为空");
        }
        HisInpTransfer exist = transferMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "转科/转床申请不存在");
        }
        String merged = (StringUtils.hasText(exist.getReason()) ? exist.getReason() + " " : "")
                + "【驳回原因】" + reason;
        int affected = transferMapper.update(null, new LambdaUpdateWrapper<HisInpTransfer>()
                .eq(HisInpTransfer::getId, id)
                .eq(HisInpTransfer::getStatus, 1)
                .set(HisInpTransfer::getStatus, 3)
                .set(HisInpTransfer::getReason, merged)
                .set(HisInpTransfer::getApproveDoctorId, currentStaffId())
                .set(HisInpTransfer::getApproveTime, LocalDateTime.now()));
        if (affected == 0) {
            throw new BizException("仅申请状态的转科/转床申请可拒绝, 状态已变化请刷新后重试");
        }
        log.info("转科/转床申请拒绝: id={}, reason={}", id, reason);
        return R.ok();
    }

    /**
     * 执行(仅已批准2→4): 先占新床 → 释旧床 → 更新就诊病区/床位/科室; 加床同时标记目标床位 is_extra_bed=1。
     */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> execute(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        HisInpTransfer t = transferMapper.selectById(id);
        if (t == null) {
            throw new BizException(404, "转科/转床申请不存在");
        }
        HisInpVisit visit = visitMapper.selectById(t.getInpVisitId());
        if (visit == null) {
            throw new BizException(404, "住院就诊不存在");
        }
        // 乐观占状态: 2批准→4已执行, 并发重复执行仅一次成功
        int affected = transferMapper.update(null, new LambdaUpdateWrapper<HisInpTransfer>()
                .eq(HisInpTransfer::getId, id)
                .eq(HisInpTransfer::getStatus, 2)
                .set(HisInpTransfer::getStatus, 4));
        if (affected == 0) {
            throw new BizException("仅已批准的申请可执行, 状态已变化请刷新后重试");
        }

        Long toBedId = t.getToBedId();
        Long fromBedId = t.getFromBedId();
        // 1) 先占新床(转床/加床必带新床; 转科带床时同): 占用失败抛异常整体回滚
        if (toBedId != null) {
            bedService.allocateBed(toBedId, visit.getPatientId(), visit.getId());
        }
        // 2) 再释旧床(旧床仍在患者名下时释放; 同床原占场景跳过)
        if (fromBedId != null && !fromBedId.equals(toBedId)) {
            bedService.releaseBed(fromBedId);
        }
        // 3) 更新就诊在院信息: 转科更新科室(可带病区/床位); 转床/加床更新床位(可带病区)
        LambdaUpdateWrapper<HisInpVisit> vw = new LambdaUpdateWrapper<HisInpVisit>()
                .eq(HisInpVisit::getId, visit.getId());
        boolean visitChanged = false;
        if (t.getTransferType() != null && t.getTransferType() == 1 && t.getToDeptId() != null) {
            vw.set(HisInpVisit::getDeptId, t.getToDeptId());
            visitChanged = true;
        }
        if (t.getToWardId() != null) {
            vw.set(HisInpVisit::getWardId, t.getToWardId());
            visitChanged = true;
        }
        if (toBedId != null) {
            vw.set(HisInpVisit::getBedId, toBedId);
            visitChanged = true;
        }
        if (visitChanged) {
            visitMapper.update(null, vw);
        }
        // 4) 加床场景标记目标床位为加床
        if (t.getTransferType() != null && t.getTransferType() == 3 && toBedId != null) {
            bedMapper.update(null, new LambdaUpdateWrapper<HisBed>()
                    .eq(HisBed::getId, toBedId)
                    .set(HisBed::getIsExtraBed, 1));
        }
        log.info("{}执行完成: id={}, visitId={}, fromBedId={}, toBedId={}, toDeptId={}",
                transferTypeName(t.getTransferType()), id, visit.getId(), fromBedId, toBedId, t.getToDeptId());
        return R.ok();
    }

    /** 取消(1申请→5取消, 仅申请人本人可取消) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> cancel(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        HisInpTransfer exist = transferMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "转科/转床申请不存在");
        }
        if (!currentStaffId().equals(exist.getApplyDoctorId())) {
            throw new BizException(403, "仅申请人本人可取消该申请");
        }
        int affected = transferMapper.update(null, new LambdaUpdateWrapper<HisInpTransfer>()
                .eq(HisInpTransfer::getId, id)
                .eq(HisInpTransfer::getStatus, 1)
                .set(HisInpTransfer::getStatus, 5));
        if (affected == 0) {
            throw new BizException("仅申请状态的转科/转床申请可取消, 状态已变化请刷新后重试");
        }
        log.info("转科/转床申请取消: id={}", id);
        return R.ok();
    }

    private String transferTypeName(Integer type) {
        if (type == null) {
            return "转科/转床";
        }
        switch (type) {
            case 1:
                return "转科";
            case 2:
                return "转床";
            case 3:
                return "加床";
            default:
                return "转科/转床";
        }
    }

    /** 当前登录职工ID(无职工关联的账号不能执行转科/转床操作) */
    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.getStaffId() == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行转科/转床操作");
        }
        return lu.getStaffId();
    }
}
