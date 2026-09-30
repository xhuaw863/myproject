package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.dto.inpatient.SurgeryMaterialDTO;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.entity.inpatient.HisSurgeryMaterial;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpChargeDetailMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMaterialMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手术耗材服务: 高值耗材逐台登记(批号/供应商可追溯), 同步双写 his_inp_charge_detail(费用类别=7材料),
 * 退回耗材时逻辑删除并联动明细退费与就诊总费用回减。
 */
@Slf4j
@Service
public class SurgeryMaterialService {

    private final HisSurgeryMaterialMapper materialMapper;
    private final HisSurgeryMapper surgeryMapper;
    private final HisInpChargeDetailMapper chargeDetailMapper;
    private final HisInpVisitMapper visitMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public SurgeryMaterialService(HisSurgeryMaterialMapper materialMapper, HisSurgeryMapper surgeryMapper,
                                  HisInpChargeDetailMapper chargeDetailMapper, HisInpVisitMapper visitMapper,
                                  OrgAccessGuard guard, JdbcTemplate jdbcTemplate) {
        this.materialMapper = materialMapper;
        this.surgeryMapper = surgeryMapper;
        this.chargeDetailMapper = chargeDetailMapper;
        this.visitMapper = visitMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 查询 / 汇总 ==================== */

    /** 耗材列表(按手术, 最近登记在前) */
    public List<HisSurgeryMaterial> listMaterials(Long surgeryId) {
        requireSurgery(surgeryId);
        return materialMapper.selectList(new LambdaQueryWrapper<HisSurgeryMaterial>()
                .eq(HisSurgeryMaterial::getSurgeryId, surgeryId)
                .orderByDesc(HisSurgeryMaterial::getId));
    }

    /** 耗材汇总(SUM amount / SUM quantity / 条数) */
    public Map<String, Object> summary(Long surgeryId) {
        requireSurgery(surgeryId);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(quantity), 0) total_quantity, COALESCE(SUM(amount), 0) total_amount,"
                        + " COUNT(*) total_count"
                        + " FROM his_surgery_material"
                        + " WHERE surgery_id = ? AND deleted = 0 AND tenant_id = ?",
                surgeryId, tenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("surgeryId", surgeryId);
        result.put("totalCount", ((Number) row.get("total_count")).intValue());
        result.put("totalQuantity", new BigDecimal(String.valueOf(row.get("total_quantity"))));
        result.put("totalAmount", new BigDecimal(String.valueOf(row.get("total_amount"))));
        return result;
    }

    /* ==================== 登记 / 退回 ==================== */

    /**
     * 添加耗材: 创建 his_surgery_material 记录, 同步双写 his_inp_charge_detail(feeType=7材料, 回写 surgery_id),
     * 并累加住院就诊 total_cost。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryMaterial addMaterial(SurgeryMaterialDTO dto, Long orgId) {
        if (dto == null || dto.getSurgeryId() == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        if (!StringUtils.hasText(dto.getMaterialName())) {
            throw new BizException(400, "耗材名称不能为空");
        }
        HisSurgery surgery = requireSurgery(dto.getSurgeryId());
        BigDecimal qty = dto.getQuantity() == null ? BigDecimal.ONE : dto.getQuantity();
        if (qty.signum() <= 0) {
            throw new BizException(400, "数量必须大于0");
        }
        BigDecimal price = dto.getUnitPrice();
        BigDecimal amount = dto.getAmount();
        if (amount == null) {
            if (price == null) {
                throw new BizException(400, "耗材单价或金额必须提供");
            }
            amount = price.multiply(qty).setScale(2, BigDecimal.ROUND_HALF_UP);
        } else {
            amount = amount.setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        if (price == null) {
            price = amount.divide(qty, 2, BigDecimal.ROUND_HALF_UP);
        }

        HisSurgeryMaterial m = new HisSurgeryMaterial();
        m.setOrgId(surgery.getOrgId() != null ? surgery.getOrgId() : orgId);
        m.setSurgeryId(surgery.getId());
        m.setMaterialName(dto.getMaterialName().trim());
        m.setMaterialCode(StringUtils.hasText(dto.getMaterialCode()) ? dto.getMaterialCode().trim() : null);
        m.setSpec(StringUtils.hasText(dto.getSpec()) ? dto.getSpec().trim() : null);
        m.setBatchNo(StringUtils.hasText(dto.getBatchNo()) ? dto.getBatchNo().trim() : null);
        m.setQuantity(qty);
        m.setUnitPrice(price);
        m.setAmount(amount);
        m.setSupplier(StringUtils.hasText(dto.getSupplier()) ? dto.getSupplier().trim() : null);
        materialMapper.insert(m);

        // 双写费用明细(材料费), 累加就诊总费用
        HisInpChargeDetail detail = new HisInpChargeDetail();
        detail.setOrgId(m.getOrgId());
        detail.setInpVisitId(surgery.getInpVisitId());
        detail.setSurgeryId(surgery.getId());
        detail.setItemName(m.getMaterialName());
        detail.setItemCode(m.getMaterialCode());
        detail.setQuantity(qty);
        detail.setUnitPrice(price);
        detail.setAmount(amount);
        detail.setChargeDate(LocalDate.now());
        detail.setFeeType(7);
        detail.setStatus(1);
        detail.setOperatorId(currentStaffId());
        chargeDetailMapper.insert(detail);
        accumulateTotalCost(surgery.getInpVisitId(), amount);
        log.info("手术耗材登记: materialId={}, surgeryId={}, name={}, qty={}, amount={}",
                m.getId(), surgery.getId(), m.getMaterialName(), qty, amount);
        return m;
    }

    /**
     * 退回耗材: 逻辑删除 his_surgery_material,
     * 对应 his_inp_charge_detail 标记退费(status=2)并回减就诊总费用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMaterial(Long id) {
        HisSurgeryMaterial m = requireMaterial(id);
        int affected = materialMapper.update(null, new LambdaUpdateWrapper<HisSurgeryMaterial>()
                .set(HisSurgeryMaterial::getDeleted, 1)
                .set(HisSurgeryMaterial::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryMaterial::getId, id));
        if (affected == 0) {
            throw new BizException("耗材记录状态已变化, 请刷新后重试");
        }

        // 联动费用明细退费: 按 手术+名称+金额 定位未退明细(多笔时先退最早一笔)
        List<HisInpChargeDetail> cds = chargeDetailMapper.selectList(new LambdaQueryWrapper<HisInpChargeDetail>()
                .eq(HisInpChargeDetail::getSurgeryId, m.getSurgeryId())
                .eq(HisInpChargeDetail::getItemName, m.getMaterialName())
                .eq(HisInpChargeDetail::getAmount, m.getAmount())
                .eq(HisInpChargeDetail::getStatus, 1)
                .orderByAsc(HisInpChargeDetail::getId)
                .last("LIMIT 1"));
        if (cds.isEmpty()) {
            log.warn("耗材退回未找到对应费用明细(可能已退费): materialId={}, surgeryId={}, name={}",
                    id, m.getSurgeryId(), m.getMaterialName());
        } else {
            HisInpChargeDetail cd = cds.get(0);
            int refunded = chargeDetailMapper.update(null, new LambdaUpdateWrapper<HisInpChargeDetail>()
                    .set(HisInpChargeDetail::getStatus, 2)
                    .set(HisInpChargeDetail::getUpdateTime, LocalDateTime.now())
                    .eq(HisInpChargeDetail::getId, cd.getId())
                    .eq(HisInpChargeDetail::getStatus, 1));
            if (refunded > 0) {
                accumulateTotalCost(cd.getInpVisitId(),
                        cd.getAmount() == null ? BigDecimal.ZERO : cd.getAmount().negate());
            }
        }
        log.info("手术耗材退回: materialId={}, surgeryId={}, amount={}", id, m.getSurgeryId(), m.getAmount());
    }

    /* ==================== 校验 / 工具 ==================== */

    /** 耗材记录存在性 + 经手术做机构归属校验 */
    private HisSurgeryMaterial requireMaterial(Long id) {
        if (id == null) {
            throw new BizException(400, "耗材ID不能为空");
        }
        HisSurgeryMaterial m = materialMapper.selectById(id);
        if (m == null) {
            throw new BizException(404, "耗材记录不存在");
        }
        if (m.getSurgeryId() != null) {
            requireSurgery(m.getSurgeryId());
        }
        return m;
    }

    /** 手术存在性 + 机构归属校验(非牵头机构仅本机构可访问) */
    private HisSurgery requireSurgery(Long surgeryId) {
        if (surgeryId == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        HisSurgery s = surgeryMapper.selectById(surgeryId);
        if (s == null) {
            throw new BizException(404, "手术记录不存在");
        }
        Long scope = guard.scopeOrgId(s.getOrgId());
        if (scope == null || !scope.equals(s.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的手术数据");
        }
        return s;
    }

    /** 就诊总费用原子累加(delta 可为负, 退回冲减用); 金额为纯数字拼接无注入风险 */
    private void accumulateTotalCost(Long visitId, BigDecimal delta) {
        if (visitId == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        visitMapper.update(null, new LambdaUpdateWrapper<HisInpVisit>()
                .setSql("total_cost = IFNULL(total_cost, 0) + "
                        + delta.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString())
                .eq(HisInpVisit::getId, visitId));
    }

    /** 当前登录用户关联职工ID(操作员留痕) */
    private static Long currentStaffId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getStaffId();
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
