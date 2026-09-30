package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.dto.inpatient.SurgeryFeeDTO;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.entity.inpatient.HisSurgeryFee;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpChargeDetailMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryFeeMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手术费用服务(核心): 费用记账双写(his_surgery_fee + his_inp_charge_detail 回写 surgery_id)、
 * 批量记账、退费冲销(明细置退费态 + 就诊总费用回减)、按类别汇总、自动计时计费(麻醉费按30分钟/单位)。
 * 费用分类映射: 1手术费/2麻醉费/3监测费 -> 5治疗, 4耗材费 -> 7材料, 5药品费 -> 1西药, 6其他 -> 9其他。
 */
@Slf4j
@Service
public class SurgeryFeeService {

    /** 手术费用分类名称 */
    private static final Map<Integer, String> FEE_CATEGORY_NAMES = new LinkedHashMap<>();

    static {
        FEE_CATEGORY_NAMES.put(1, "手术费");
        FEE_CATEGORY_NAMES.put(2, "麻醉费");
        FEE_CATEGORY_NAMES.put(3, "监测费");
        FEE_CATEGORY_NAMES.put(4, "耗材费");
        FEE_CATEGORY_NAMES.put(5, "药品费");
        FEE_CATEGORY_NAMES.put(6, "其他");
    }

    /** 麻醉费计费单位时长(分钟): 每30分钟一个计费单位, 不足30分按30分计 */
    private static final int ANESTHESIA_UNIT_MINUTES = 30;
    /** 自动计时麻醉费兜底单价(收费目录无"麻醉"项目时使用) */
    private static final BigDecimal DEFAULT_ANESTHESIA_UNIT_PRICE = new BigDecimal("300.00");

    private final HisSurgeryFeeMapper feeMapper;
    private final HisSurgeryMapper surgeryMapper;
    private final HisInpChargeDetailMapper chargeDetailMapper;
    private final HisInpVisitMapper visitMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public SurgeryFeeService(HisSurgeryFeeMapper feeMapper, HisSurgeryMapper surgeryMapper,
                             HisInpChargeDetailMapper chargeDetailMapper, HisInpVisitMapper visitMapper,
                             OrgAccessGuard guard, JdbcTemplate jdbcTemplate) {
        this.feeMapper = feeMapper;
        this.surgeryMapper = surgeryMapper;
        this.chargeDetailMapper = chargeDetailMapper;
        this.visitMapper = visitMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 查询 / 汇总 ==================== */

    /** 手术费用明细列表(含已退费行, status 标识 1正常 2退费; 最近的在前) */
    public List<HisSurgeryFee> listFees(Long surgeryId) {
        requireSurgery(surgeryId);
        return feeMapper.selectList(new LambdaQueryWrapper<HisSurgeryFee>()
                .eq(HisSurgeryFee::getSurgeryId, surgeryId)
                .orderByDesc(HisSurgeryFee::getId));
    }

    /** 费用汇总(按 fee_category 分组 SUM, 仅 status=1 正常明细) */
    public Map<String, Object> summary(Long surgeryId) {
        requireSurgery(surgeryId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT fee_category, COALESCE(SUM(quantity), 0) total_quantity, COALESCE(SUM(amount), 0) total_amount"
                        + " FROM his_surgery_fee"
                        + " WHERE surgery_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?"
                        + " GROUP BY fee_category ORDER BY fee_category",
                surgeryId, tenantId());
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            Object catObj = row.get("fee_category");
            int cat = catObj == null ? 6 : ((Number) catObj).intValue();
            BigDecimal amt = new BigDecimal(String.valueOf(row.get("total_amount")));
            BigDecimal qty = new BigDecimal(String.valueOf(row.get("total_quantity")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feeCategory", cat);
            m.put("feeCategoryName", FEE_CATEGORY_NAMES.getOrDefault(cat, "其他"));
            m.put("totalQuantity", qty);
            m.put("totalAmount", amt);
            items.add(m);
            totalAmount = totalAmount.add(amt);
            totalQty = totalQty.add(qty);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("surgeryId", surgeryId);
        result.put("items", items);
        result.put("totalQuantity", totalQty);
        result.put("totalAmount", totalAmount);
        return result;
    }

    /* ==================== 记账(双写) ==================== */

    /**
     * 添加费用项: 创建 his_surgery_fee 记录, 同步双写 his_inp_charge_detail(关联 surgery_id),
     * 并累加住院就诊 total_cost。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryFee addFee(SurgeryFeeDTO dto, Long orgId) {
        return doAddFee(dto, orgId);
    }

    /** 批量添加费用(整批一个事务, 任一条失败全部回滚) */
    @Transactional(rollbackFor = Exception.class)
    public List<HisSurgeryFee> batchAddFee(List<SurgeryFeeDTO> dtos, Long orgId) {
        if (CollectionUtils.isEmpty(dtos)) {
            throw new BizException(400, "批量费用不能为空");
        }
        List<HisSurgeryFee> out = new ArrayList<>(dtos.size());
        for (SurgeryFeeDTO dto : dtos) {
            out.add(doAddFee(dto, orgId));
        }
        log.info("批量手术记账: {}条, orgId={}", out.size(), orgId);
        return out;
    }

    /** 记账内部实现(校验 -> 主表插入 -> 费用明细双写 -> 累加总费用) */
    private HisSurgeryFee doAddFee(SurgeryFeeDTO dto, Long orgId) {
        validateFeeDto(dto);
        HisSurgery surgery = requireSurgery(dto.getSurgeryId());
        BigDecimal qty = dto.getQuantity() == null ? BigDecimal.ONE : dto.getQuantity();
        BigDecimal price = dto.getUnitPrice();
        BigDecimal amount = dto.getAmount() != null
                ? dto.getAmount().setScale(2, BigDecimal.ROUND_HALF_UP)
                : price.multiply(qty).setScale(2, BigDecimal.ROUND_HALF_UP);

        HisSurgeryFee fee = new HisSurgeryFee();
        fee.setOrgId(surgery.getOrgId() != null ? surgery.getOrgId() : orgId);
        fee.setSurgeryId(surgery.getId());
        fee.setInpVisitId(dto.getInpVisitId() != null ? dto.getInpVisitId() : surgery.getInpVisitId());
        fee.setChargeItemId(dto.getChargeItemId());
        fee.setItemName(dto.getItemName().trim());
        fee.setItemCode(StringUtils.hasText(dto.getItemCode()) ? dto.getItemCode().trim() : null);
        fee.setFeeCategory(dto.getFeeCategory() == null ? 6 : dto.getFeeCategory());
        fee.setQuantity(qty);
        fee.setUnitPrice(price);
        fee.setAmount(amount);
        fee.setChargeTime(LocalDateTime.now());
        fee.setAutoFlag(dto.getAutoFlag() == null ? 0 : dto.getAutoFlag());
        fee.setDurationMinutes(dto.getDurationMinutes());
        fee.setOperatorId(currentStaffId());
        fee.setStatus(1);
        feeMapper.insert(fee);

        writeChargeDetail(fee);
        log.info("手术记账: feeId={}, surgeryId={}, item={}, qty={}, amount={}",
                fee.getId(), fee.getSurgeryId(), fee.getItemName(), qty, amount);
        return fee;
    }

    /** 费用明细双写: his_inp_charge_detail 回写 surgery_id, 费用类别按手术分类映射, 累加就诊总费用 */
    private void writeChargeDetail(HisSurgeryFee fee) {
        HisInpChargeDetail detail = new HisInpChargeDetail();
        detail.setOrgId(fee.getOrgId());
        detail.setInpVisitId(fee.getInpVisitId());
        detail.setSurgeryId(fee.getSurgeryId());
        detail.setChargeItemId(fee.getChargeItemId());
        detail.setItemName(fee.getItemName());
        detail.setItemCode(fee.getItemCode());
        detail.setQuantity(fee.getQuantity());
        detail.setUnitPrice(fee.getUnitPrice());
        detail.setAmount(fee.getAmount());
        detail.setChargeDate(LocalDate.now());
        detail.setFeeType(mapFeeCategory(fee.getFeeCategory()));
        detail.setStatus(1);
        detail.setOperatorId(fee.getOperatorId());
        chargeDetailMapper.insert(detail);
        accumulateTotalCost(fee.getInpVisitId(), fee.getAmount());
    }

    /* ==================== 退费 ==================== */

    /**
     * 删除费用项: 逻辑删除 his_surgery_fee 并置退费态(2),
     * 对应 his_inp_charge_detail 标记退费(status=2)并回减就诊总费用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFee(Long id) {
        HisSurgeryFee fee = requireFee(id);
        int affected = feeMapper.update(null, new LambdaUpdateWrapper<HisSurgeryFee>()
                .set(HisSurgeryFee::getStatus, 2)
                .set(HisSurgeryFee::getDeleted, 1)
                .set(HisSurgeryFee::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryFee::getId, id)
                .eq(HisSurgeryFee::getStatus, 1));
        if (affected == 0) {
            throw new BizException("费用项已退费或状态已变化, 请刷新后重试");
        }
        refundChargeDetail(fee);
        log.info("手术费用退费: feeId={}, surgeryId={}, amount={}", id, fee.getSurgeryId(), fee.getAmount());
    }

    /** 联带费用明细退费: 按 手术+项目名+金额 定位未退明细(多笔时先退最早一笔), 冲减就诊总费用 */
    private void refundChargeDetail(HisSurgeryFee fee) {
        List<HisInpChargeDetail> cds = chargeDetailMapper.selectList(new LambdaQueryWrapper<HisInpChargeDetail>()
                .eq(HisInpChargeDetail::getSurgeryId, fee.getSurgeryId())
                .eq(HisInpChargeDetail::getItemName, fee.getItemName())
                .eq(HisInpChargeDetail::getAmount, fee.getAmount())
                .eq(HisInpChargeDetail::getStatus, 1)
                .orderByAsc(HisInpChargeDetail::getId)
                .last("LIMIT 1"));
        if (cds.isEmpty()) {
            log.warn("手术费用退费未找到对应费用明细(可能已退费): feeId={}, surgeryId={}, item={}",
                    fee.getId(), fee.getSurgeryId(), fee.getItemName());
            return;
        }
        HisInpChargeDetail cd = cds.get(0);
        int affected = chargeDetailMapper.update(null, new LambdaUpdateWrapper<HisInpChargeDetail>()
                .set(HisInpChargeDetail::getStatus, 2)
                .set(HisInpChargeDetail::getUpdateTime, LocalDateTime.now())
                .eq(HisInpChargeDetail::getId, cd.getId())
                .eq(HisInpChargeDetail::getStatus, 1));
        if (affected > 0) {
            accumulateTotalCost(cd.getInpVisitId(),
                    cd.getAmount() == null ? BigDecimal.ZERO : cd.getAmount().negate());
        }
    }

    /* ==================== 自动计时计费 ==================== */

    /**
     * 自动计时计费(麻醉费): 读取手术 start_time/end_time 计算时长(分钟),
     * 计费单位数 = ceil(分钟/30)(不足30分按30分计, 至少1单位),
     * 创建 auto_flag=1 / duration_minutes=实际分钟 的计费记录并同步双写;
     * 同一手术已有未退自动计时记录时拦截, 防止重复计费。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> autoCalcTimeFee(Long surgeryId, Long orgId) {
        HisSurgery surgery = requireSurgery(surgeryId);
        if (surgery.getStartTime() == null || surgery.getEndTime() == null) {
            throw new BizException("手术尚未开始或未结束, 无法自动计时计费");
        }
        long minutes = Duration.between(surgery.getStartTime(), surgery.getEndTime()).toMinutes();
        if (minutes < 0) {
            throw new BizException("手术结束时间早于开始时间, 无法计费");
        }
        int units = (int) Math.max(1L, (long) Math.ceil(minutes / (double) ANESTHESIA_UNIT_MINUTES));

        long existAuto = feeMapper.selectCount(new LambdaQueryWrapper<HisSurgeryFee>()
                .eq(HisSurgeryFee::getSurgeryId, surgeryId)
                .eq(HisSurgeryFee::getAutoFlag, 1)
                .eq(HisSurgeryFee::getStatus, 1));
        if (existAuto > 0) {
            throw new BizException("该手术已生成自动计时费, 请勿重复计费");
        }

        // 单价: 优先收费目录"麻醉"项目(按机构价格档), 无目录时用兜底单价
        BigDecimal price = DEFAULT_ANESTHESIA_UNIT_PRICE;
        Long chargeItemId = null;
        String itemName = "麻醉费(计时)";
        String itemCode = null;
        Map<String, Object> cat = findAnesthesiaChargeItem();
        if (cat != null) {
            chargeItemId = ((Number) cat.get("id")).longValue();
            itemName = str(cat.get("item_name"));
            itemCode = str(cat.get("item_code"));
            BigDecimal catalogPrice = execPrice(cat, orgId);
            if (catalogPrice != null && catalogPrice.signum() > 0) {
                price = catalogPrice;
            }
        }

        SurgeryFeeDTO dto = new SurgeryFeeDTO();
        dto.setSurgeryId(surgeryId);
        dto.setInpVisitId(surgery.getInpVisitId());
        dto.setChargeItemId(chargeItemId);
        dto.setItemName(itemName);
        dto.setItemCode(itemCode);
        dto.setFeeCategory(2);
        dto.setQuantity(new BigDecimal(units));
        dto.setUnitPrice(price);
        dto.setAmount(price.multiply(new BigDecimal(units)).setScale(2, BigDecimal.ROUND_HALF_UP));
        dto.setAutoFlag(1);
        dto.setDurationMinutes((int) minutes);

        HisSurgeryFee fee = doAddFee(dto, orgId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fee", fee);
        result.put("durationMinutes", minutes);
        result.put("unitMinutes", ANESTHESIA_UNIT_MINUTES);
        result.put("billingUnits", units);
        log.info("自动计时计费: surgeryId={}, 时长={}分钟, 计费单位={}, 金额={}",
                surgeryId, minutes, units, fee.getAmount());
        return result;
    }

    /** 收费目录"麻醉"项目(启用行, 缺失返回 null) */
    private Map<String, Object> findAnesthesiaChargeItem() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, item_name, item_code, price, price_l1, price_l2, price_l3 FROM his_charge_item"
                        + " WHERE status = 1 AND deleted = 0 AND tenant_id = ? AND item_name LIKE '%麻醉%'"
                        + " ORDER BY id LIMIT 1",
                tenantId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 执行价: 按机构 sys_org.price_lv 取 price_l1/l2/l3 档, 缺档回退默认 price */
    private BigDecimal execPrice(Map<String, Object> cat, Long orgId) {
        BigDecimal price = toBd(cat.get("price"));
        Integer priceLv = orgPriceLv(orgId);
        if (priceLv != null) {
            String col = priceLv == 1 ? "price_l1" : priceLv == 2 ? "price_l2" : priceLv == 3 ? "price_l3" : null;
            if (col != null) {
                BigDecimal v = toBd(cat.get(col));
                if (v != null) {
                    price = v;
                }
            }
        }
        return price;
    }

    /** 机构价格档(sys_org.price_lv, 未配置返回 null 走默认价) */
    private Integer orgPriceLv(Long orgId) {
        if (orgId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT price_lv FROM sys_org WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                orgId, tenantId());
        if (rows.isEmpty() || rows.get(0).get("price_lv") == null) {
            return null;
        }
        return ((Number) rows.get(0).get("price_lv")).intValue();
    }

    /* ==================== 校验 / 工具 ==================== */

    /** 费用入参校验 */
    private static void validateFeeDto(SurgeryFeeDTO dto) {
        if (dto == null || dto.getSurgeryId() == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        if (!StringUtils.hasText(dto.getItemName())) {
            throw new BizException(400, "费用项目名称不能为空");
        }
        if (dto.getQuantity() != null && dto.getQuantity().signum() <= 0) {
            throw new BizException(400, "数量必须大于0");
        }
        if (dto.getUnitPrice() == null || dto.getUnitPrice().signum() < 0) {
            throw new BizException(400, "费用单价无效, 请提供单价");
        }
    }

    /** 费用项存在性 + 经手术做机构归属校验 */
    private HisSurgeryFee requireFee(Long id) {
        if (id == null) {
            throw new BizException(400, "费用ID不能为空");
        }
        HisSurgeryFee fee = feeMapper.selectById(id);
        if (fee == null) {
            throw new BizException(404, "费用项不存在");
        }
        if (fee.getSurgeryId() != null) {
            requireSurgery(fee.getSurgeryId());
        }
        return fee;
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

    /** 就诊总费用原子累加(delta 可为负, 退费冲减用); 金额为纯数字拼接无注入风险 */
    private void accumulateTotalCost(Long visitId, BigDecimal delta) {
        if (visitId == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        visitMapper.update(null, new LambdaUpdateWrapper<HisInpVisit>()
                .setSql("total_cost = IFNULL(total_cost, 0) + "
                        + delta.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString())
                .eq(HisInpVisit::getId, visitId));
    }

    /** 手术费用分类 -> 住院费用类别(1手术费/2麻醉费/3监测费->5治疗, 4耗材费->7材料, 5药品费->1西药, 6其他->9其他) */
    private static Integer mapFeeCategory(Integer feeCategory) {
        if (feeCategory == null) {
            return 9;
        }
        switch (feeCategory) {
            case 1:
            case 2:
            case 3:
                return 5;
            case 4:
                return 7;
            case 5:
                return 1;
            default:
                return 9;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
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
