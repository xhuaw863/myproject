package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yb.hi.dto.inpatient.InpOrderDTO;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.entity.inpatient.HisSurgeryApply;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.inpatient.HisInpOrderMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryApplyMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 手术医嘱编排服务(手麻P1): 复用住院医嘱开立链(InpOrderService.createOrder), 附加手术/申请单关联与阶段解析;
 * 提供按手术/申请单查询、发送药房(send_pharm_status 0/2->1)与撤回(1->2, 已发药拒绝)闸门。
 * 门诊/日间手术无住院就诊, 其费用走 SurgeryFee 双写, 不进住院医嘱链, 故仅住院手术可开手术医嘱。
 * 机构隔离: 写以 currentOrgId 校验, 读按手术/申请归属机构。
 */
@Slf4j
@Service
public class SurgeryOrderService {

    private final InpOrderService inpOrderService;
    private final HisSurgeryMapper surgeryMapper;
    private final HisSurgeryApplyMapper applyMapper;
    private final HisInpOrderMapper orderMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public SurgeryOrderService(InpOrderService inpOrderService, HisSurgeryMapper surgeryMapper,
                               HisSurgeryApplyMapper applyMapper, HisInpOrderMapper orderMapper,
                               OrgAccessGuard guard, JdbcTemplate jdbcTemplate) {
        this.inpOrderService = inpOrderService;
        this.surgeryMapper = surgeryMapper;
        this.applyMapper = applyMapper;
        this.orderMapper = orderMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按手术查询手术医嘱(术中/术后, 按阶段与ID排序) */
    public List<HisInpOrder> listBySurgery(Long surgeryId) {
        if (surgeryId == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        return orderMapper.selectList(new LambdaQueryWrapper<HisInpOrder>()
                .eq(HisInpOrder::getSurgeryId, surgeryId)
                .orderByAsc(HisInpOrder::getOrderPhase)
                .orderByDesc(HisInpOrder::getId));
    }

    /** 按申请单查询术前医嘱 */
    public List<HisInpOrder> listByApply(Long applyId) {
        if (applyId == null) {
            throw new BizException(400, "申请单ID不能为空");
        }
        return orderMapper.selectList(new LambdaQueryWrapper<HisInpOrder>()
                .eq(HisInpOrder::getSurgeryApplyId, applyId)
                .orderByAsc(HisInpOrder::getOrderPhase)
                .orderByDesc(HisInpOrder::getId));
    }

    /**
     * 开立手术医嘱: 依 surgeryId(术中/术后)或 surgeryApplyId(术前)回填住院就诊ID后委托标准开立链。
     * 门诊/日间手术无住院就诊, 拒绝走医嘱链(改由 SurgeryFee 记账)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpOrder addOrder(InpOrderDTO dto) {
        if (dto == null) {
            throw new BizException(400, "医嘱参数不能为空");
        }
        if (dto.getSurgeryId() != null) {
            HisSurgery s = surgeryMapper.selectById(dto.getSurgeryId());
            if (s == null) {
                throw new BizException(404, "手术记录不存在");
            }
            requireSameOrg(s.getOrgId());
            if (s.getVisitType() != null && s.getVisitType() != 1) {
                throw new BizException("门诊/日间手术不进住院医嘱链, 其用药请走手术费用记账");
            }
            dto.setInpVisitId(s.getInpVisitId());
            dto.setSurgeryApplyId(null);
        } else if (dto.getSurgeryApplyId() != null) {
            HisSurgeryApply a = applyMapper.selectById(dto.getSurgeryApplyId());
            if (a == null) {
                throw new BizException(404, "手术申请单不存在");
            }
            requireSameOrg(a.getOrgId());
            if (a.getVisitType() != null && a.getVisitType() != 1) {
                throw new BizException("门诊/日间手术申请不进住院医嘱链, 其用药请走手术费用记账");
            }
            dto.setInpVisitId(a.getInpVisitId());
            dto.setSurgeryId(null);
        } else {
            throw new BizException(400, "手术医嘱须关联手术ID或申请单ID");
        }
        HisInpOrder o = inpOrderService.createOrder(dto);
        log.info("开立手术医嘱: id={}, surgeryId={}, applyId={}, phase={}, visitId={}",
                o.getId(), o.getSurgeryId(), o.getSurgeryApplyId(), o.getOrderPhase(), o.getInpVisitId());
        return o;
    }

    /** 发送药房: 手术类药品医嘱 0/2->1, 置后方进入发药队列; 已发药不可重复发送 */
    @Transactional(rollbackFor = Exception.class)
    public int sendPharmacy(Long orderId) {
        HisInpOrder o = requireSurgeryDrugOrder(orderId);
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_order SET send_pharm_status = 1, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0"
                        + " AND dispense_status = 0 AND (send_pharm_status IS NULL OR send_pharm_status IN (0, 2))",
                orderId, tenantId(), o.getOrgId());
        if (affected == 0) {
            throw new BizException("医嘱已发送或已发药, 状态已变化, 请刷新后重试");
        }
        log.info("手术医嘱发送药房: orderId={}, orgId={}", orderId, o.getOrgId());
        return affected;
    }

    /** 撤回发送: 1->2, 仅药房未发药(dispense_status=0)可撤回; 已发药须走退药 */
    @Transactional(rollbackFor = Exception.class)
    public int recallPharmacy(Long orderId) {
        HisInpOrder o = requireSurgeryDrugOrder(orderId);
        int affected = jdbcTemplate.update(
                "UPDATE his_inp_order SET send_pharm_status = 2, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0"
                        + " AND dispense_status = 0 AND send_pharm_status = 1",
                orderId, tenantId(), o.getOrgId());
        if (affected == 0) {
            Integer dispensed = jdbcTemplate.queryForObject(
                    "SELECT dispense_status FROM his_inp_order WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    Integer.class, orderId, tenantId());
            if (Integer.valueOf(1).equals(dispensed)) {
                throw new BizException("药房已发药, 不可撤回发送, 请走退药流程");
            }
            throw new BizException("医嘱未处于可撤回状态(仅已发送且未发药可撤回), 请刷新后重试");
        }
        log.info("手术医嘱撤回发送: orderId={}, orgId={}", orderId, o.getOrgId());
        return affected;
    }

    /** 校验目标为手术类药品医嘱且归属当前机构 */
    private HisInpOrder requireSurgeryDrugOrder(Long orderId) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        HisInpOrder o = orderMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(404, "医嘱不存在");
        }
        requireSameOrg(o.getOrgId());
        if (o.getSurgeryId() == null && o.getSurgeryApplyId() == null) {
            throw new BizException("普通住院医嘱自动进入药房队列, 无需发送/撤回操作");
        }
        if (o.getOrderCategory() == null || o.getOrderCategory() != 1 || o.getDrugId() == null) {
            throw new BizException("仅手术药品医嘱支持发送药房/撤回");
        }
        return o;
    }

    private void requireSameOrg(Long orgId) {
        Long cur = guard.currentOrgId();
        if (cur == null || orgId == null || !cur.equals(orgId)) {
            throw new BizException(403, "无权操作其他机构的手术医嘱");
        }
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
