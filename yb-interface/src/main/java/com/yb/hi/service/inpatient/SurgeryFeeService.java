package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.dto.inpatient.SurgeryFeeDTO;
import com.yb.hi.entity.doctor.HisOrder;
import com.yb.hi.entity.doctor.HisOrderItem;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.entity.inpatient.HisSurgeryFee;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisOrderItemMapper;
import com.yb.hi.mapper.doctor.HisOrderMapper;
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
 * 手术费用服务(核心): 费用记账双写(住院: his_surgery_fee + his_inp_charge_detail 回写 surgery_id;
 * 门诊/日间 visit_type=2/3: 双写 his_order/his_order_item 自动进入门诊收费处待缴费),
 * 批量记账、退费冲销(住院: 明细置退费态+回减总费用; 门诊: 明细逻辑删+回减单据金额)、
 * 按类别汇总、自动计时计费(麻醉费按30分钟/单位)。
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
    private final HisOrderMapper orderMapper;
    private final HisOrderItemMapper orderItemMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public SurgeryFeeService(HisSurgeryFeeMapper feeMapper, HisSurgeryMapper surgeryMapper,
                             HisInpChargeDetailMapper chargeDetailMapper, HisInpVisitMapper visitMapper,
                             HisOrderMapper orderMapper, HisOrderItemMapper orderItemMapper,
                             OrgAccessGuard guard, JdbcTemplate jdbcTemplate) {
        this.feeMapper = feeMapper;
        this.surgeryMapper = surgeryMapper;
        this.chargeDetailMapper = chargeDetailMapper;
        this.visitMapper = visitMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 查询 / 汇总 ==================== */

    /** 一体化模块(P2d) -> 推荐费用类别默认可见集(不强制, 供前端记账预置): key=module_type(1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗) */
    private static final Map<Integer, int[]> MODULE_FEE_DEFAULTS = new LinkedHashMap<>();

    static {
        MODULE_FEE_DEFAULTS.put(1, new int[]{1, 2, 3, 4, 5, 6}); // 手术室: 全类别
        MODULE_FEE_DEFAULTS.put(2, new int[]{1, 3, 4, 6});        // DSA: 手术/监测/耗材/其他
        MODULE_FEE_DEFAULTS.put(3, new int[]{1, 4, 5, 6});        // 产科分娩: 手术/耗材/药品/其他
        MODULE_FEE_DEFAULTS.put(4, new int[]{1, 3, 4, 6});        // 内镜: 手术/监测/耗材/其他
        MODULE_FEE_DEFAULTS.put(5, new int[]{2, 3, 5, 6});        // 麻醉治疗: 麻醉/监测/药品/其他
    }

    /**
     * 按手术的 module_type 返回推荐费用类别默认归类与可见集(P2d, 不强制): {moduleType, defaultCategory, categories:[{feeCategory,feeCategoryName}]}。
     */
    public Map<String, Object> defaultFeeCategories(Long surgeryId) {
        HisSurgery s = requireSurgery(surgeryId);
        Integer moduleType = s.getModuleType() == null ? 1 : s.getModuleType();
        int[] visible = MODULE_FEE_DEFAULTS.getOrDefault(moduleType, new int[]{1, 2, 3, 4, 5, 6});
        List<Map<String, Object>> categories = new ArrayList<>();
        for (int cat : visible) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feeCategory", cat);
            m.put("feeCategoryName", FEE_CATEGORY_NAMES.getOrDefault(cat, "其他"));
            categories.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moduleType", moduleType);
        result.put("defaultCategory", visible.length > 0 ? visible[0] : 6);
        result.put("categories", categories);
        return result;
    }

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
        int vt = surgery.getVisitType() == null ? 1 : surgery.getVisitType();
        fee.setVisitType(vt);
        fee.setVisitId(vt == 1 ? null : surgery.getVisitId());
        fee.setInpVisitId(vt == 1 ? (dto.getInpVisitId() != null ? dto.getInpVisitId() : surgery.getInpVisitId()) : null);
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

        if (vt != 1) {
            writeOutpOrderDetail(fee);
        } else {
            writeChargeDetail(fee);
        }
        log.info("手术记账: feeId={}, surgeryId={}, visitType={}, item={}, qty={}, amount={}",
                fee.getId(), fee.getSurgeryId(), vt, fee.getItemName(), qty, amount);
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
        // 手麻P1: 手术已完成(5)的费项不可直接退, 须先"取消完成"回退至术后(4)再补退费(执行后不可退守卫)
        if (fee.getSurgeryId() != null) {
            HisSurgery surg = requireSurgery(fee.getSurgeryId());
            if (surg != null && Integer.valueOf(5).equals(surg.getStatus())) {
                throw new BizException("手术已完成, 费用不可直接退回, 请先取消完成(回退至术后)再补退费");
            }
        }
        int affected = feeMapper.update(null, new LambdaUpdateWrapper<HisSurgeryFee>()
                .set(HisSurgeryFee::getStatus, 2)
                .set(HisSurgeryFee::getDeleted, 1)
                .set(HisSurgeryFee::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryFee::getId, id)
                .eq(HisSurgeryFee::getStatus, 1));
        if (affected == 0) {
            throw new BizException("费用项已退费或状态已变化, 请刷新后重试");
        }
        if (fee.getVisitType() != null && fee.getVisitType() != 1) {
            refundOutpOrderDetail(fee);
            return;
        }
        refundChargeDetail(fee);
        log.info("手术费用退费: feeId={}, surgeryId={}, amount={}", id, fee.getSurgeryId(), fee.getAmount());
    }

    /* ==================== 门诊/日间双写(规范2.2.2.3.7.5) ==================== */

    /** 门诊双写: 同一手术复用同一 his_order(治疗单), 逐笔写 his_order_item, 收费处现有取数口径自动进入待缴费 */
    private void writeOutpOrderDetail(HisSurgeryFee fee) {
        if (fee.getVisitId() == null) {
            throw new BizException("门诊/日间手术缺少门诊就诊ID, 无法双写门诊费用单据");
        }
        List<Map<String, Object>> vs = jdbcTemplate.queryForList(
                "SELECT patient_id, patient_name, dept_id, dept_name FROM his_visit"
                        + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                fee.getVisitId(), tenantId());
        if (vs.isEmpty()) {
            throw new BizException("门诊就诊记录不存在, 无法双写门诊费用单据");
        }
        Map<String, Object> v = vs.get(0);
        Long orderId = findSurgeryOrderId(fee.getSurgeryId());
        if (orderId == null) {
            HisOrder order = new HisOrder();
            order.setVisitId(fee.getVisitId());
            order.setOrderNo("SZ" + fee.getId());
            order.setPatientId(toLong(v.get("patient_id")));
            order.setPatientName(str(v.get("patient_name")));
            order.setDeptId(toLong(v.get("dept_id")));
            order.setDeptName(str(v.get("dept_name")));
            order.setDrId(fee.getOperatorId());
            order.setDrName(operatorName(fee.getOperatorId()));
            order.setOrderType("治疗");
            order.setDiagName("手术麻醉记费");
            order.setTotalAmount(fee.getAmount());
            order.setStatus(1);
            order.setExecStatus(0);
            order.setPaidFlag(0);
            orderMapper.insert(order);
            orderId = order.getId();
        } else {
            orderMapper.update(null, new LambdaUpdateWrapper<HisOrder>()
                    .setSql("total_amount = IFNULL(total_amount, 0) + "
                            + fee.getAmount().setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString())
                    .eq(HisOrder::getId, orderId));
        }
        HisOrderItem item = new HisOrderItem();
        item.setOrderId(orderId);
        item.setItemId(fee.getChargeItemId());
        item.setItemCode(fee.getItemCode());
        item.setItemName(fee.getItemName());
        item.setUnit("次");
        item.setPrice(fee.getUnitPrice());
        item.setQuantity(fee.getQuantity());
        item.setAmount(fee.getAmount());
        orderItemMapper.insert(item);
        fee.setOrderId(orderId);
        fee.setOrderItemId(item.getId());
        feeMapper.update(null, new LambdaUpdateWrapper<HisSurgeryFee>()
                .set(HisSurgeryFee::getOrderId, orderId)
                .set(HisSurgeryFee::getOrderItemId, item.getId())
                .set(HisSurgeryFee::getUpdateTime, LocalDateTime.now())
                .eq(HisSurgeryFee::getId, fee.getId()));
        log.info("门诊手术费双写: feeId={}, orderId={}, orderItemId={}, amount={}",
                fee.getId(), orderId, item.getId(), fee.getAmount());
    }

    /** 同一手术已有门诊单据则复用(按早期未退记账行的 order_id 归组) */
    private Long findSurgeryOrderId(Long surgeryId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_id FROM his_surgery_fee"
                        + " WHERE surgery_id = ? AND order_id IS NOT NULL AND deleted = 0 AND tenant_id = ?"
                        + " ORDER BY id LIMIT 1",
                surgeryId, tenantId());
        return rows.isEmpty() ? null : toLong(rows.get(0).get("order_id"));
    }

    /** 门诊退费冲销: 已收费拦截; 明细逻辑删(收费口径 oi.deleted=0) + 回减单据金额, 无剩余明细置单据已退 */
    private void refundOutpOrderDetail(HisSurgeryFee fee) {
        if (fee.getOrderId() == null) {
            log.warn("门诊手术费退费未找到双写单据(可能未双写): feeId={}", fee.getId());
            return;
        }
        List<Map<String, Object>> ors = jdbcTemplate.queryForList(
                "SELECT paid_flag FROM his_order WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                fee.getOrderId(), tenantId());
        if (!ors.isEmpty() && Integer.valueOf(1).equals(toInt(ors.get(0).get("paid_flag")))) {
            throw new BizException("该门诊手术费用单据已收费, 请先到收费站冲销后再退费");
        }
        Long itemId = fee.getOrderItemId();
        if (itemId == null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM his_order_item WHERE order_id = ? AND item_name = ? AND amount = ?"
                            + " AND deleted = 0 AND tenant_id = ? ORDER BY id LIMIT 1",
                    fee.getOrderId(), fee.getItemName(), fee.getAmount(), tenantId());
            if (rows.isEmpty()) {
                log.warn("门诊手术费退费未定位到双写明细: feeId={}", fee.getId());
                return;
            }
            itemId = toLong(rows.get(0).get("id"));
        }
        orderItemMapper.deleteById(itemId); // @TableLogic 逻辑删, 收费待缴口径自动排除
        BigDecimal amt = fee.getAmount() == null ? BigDecimal.ZERO : fee.getAmount();
        orderMapper.update(null, new LambdaUpdateWrapper<HisOrder>()
                .setSql("total_amount = GREATEST(IFNULL(total_amount, 0) - "
                        + amt.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString() + ", 0)")
                .eq(HisOrder::getId, fee.getOrderId()));
        Long remain = orderItemMapper.selectCount(new LambdaQueryWrapper<HisOrderItem>()
                .eq(HisOrderItem::getOrderId, fee.getOrderId()));
        if (remain == null || remain == 0) {
            orderMapper.update(null, new LambdaUpdateWrapper<HisOrder>()
                    .set(HisOrder::getStatus, 3)
                    .set(HisOrder::getUpdateTime, LocalDateTime.now())
                    .eq(HisOrder::getId, fee.getOrderId()));
        }
        log.info("门诊手术费退费冲销: feeId={}, orderId={}, itemId={}", fee.getId(), fee.getOrderId(), itemId);
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

    private static Long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static Integer toInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    /** 操作员姓名(门诊单据医师栏留痕, 无职工档案回退登录名) */
    private String operatorName(Long staffId) {
        if (staffId != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT staff_name FROM his_staff WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    staffId, tenantId());
            if (!rows.isEmpty()) {
                return str(rows.get(0).get("staff_name"));
            }
        }
        LoginUser lu = UserContext.get();
        return lu == null ? null : (StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername());
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
