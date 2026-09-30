package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.InpChargeQueryDTO;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpChargeDetailMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院费用服务: 费用明细分页查询、按类别汇总、手动补录(服务端取价防篡改)、医嘱->费用转换。
 * 定价口径与医生站开单一致: his_charge_item 按机构 sys_org.price_lv 取 price_l1/l2/l3 档,
 * 缺档回退默认 price; 药品医嘱取 his_drug_catalog.retail_price。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class InpChargeService {

    /** 费用类别: 1西药 2中药 3检查 4检验 5治疗 6护理 7材料 8床位 9其他 */
    private static final Map<Integer, String> FEE_TYPE_NAMES = new LinkedHashMap<>();

    static {
        FEE_TYPE_NAMES.put(1, "西药");
        FEE_TYPE_NAMES.put(2, "中药");
        FEE_TYPE_NAMES.put(3, "检查");
        FEE_TYPE_NAMES.put(4, "检验");
        FEE_TYPE_NAMES.put(5, "治疗");
        FEE_TYPE_NAMES.put(6, "护理");
        FEE_TYPE_NAMES.put(7, "材料");
        FEE_TYPE_NAMES.put(8, "床位");
        FEE_TYPE_NAMES.put(9, "其他");
    }

    private final HisInpChargeDetailMapper chargeMapper;
    private final HisInpVisitMapper visitMapper;
    private final InpFeeAlertService feeAlertService;
    private final JdbcTemplate jdbcTemplate;

    public InpChargeService(HisInpChargeDetailMapper chargeMapper, HisInpVisitMapper visitMapper,
                            InpFeeAlertService feeAlertService, JdbcTemplate jdbcTemplate) {
        this.chargeMapper = chargeMapper;
        this.visitMapper = visitMapper;
        this.feeAlertService = feeAlertService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 查询 ==================== */

    /** 费用明细列表(分页; 按就诊+记账日期区间+费用类别筛选, 最近记账在前) */
    public IPage<HisInpChargeDetail> listCharges(InpChargeQueryDTO dto, Long orgId) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        int page = dto.getPage() == null || dto.getPage() < 1 ? 1 : dto.getPage();
        int size = dto.getSize() == null || dto.getSize() < 1 ? 20 : Math.min(dto.getSize(), 200);
        LambdaQueryWrapper<HisInpChargeDetail> q = new LambdaQueryWrapper<>();
        q.eq(HisInpChargeDetail::getInpVisitId, dto.getInpVisitId())
                .eq(orgId != null, HisInpChargeDetail::getOrgId, orgId)
                .ge(dto.getStartDate() != null, HisInpChargeDetail::getChargeDate, dto.getStartDate())
                .le(dto.getEndDate() != null, HisInpChargeDetail::getChargeDate, dto.getEndDate())
                .eq(dto.getFeeType() != null, HisInpChargeDetail::getFeeType, dto.getFeeType())
                .orderByDesc(HisInpChargeDetail::getChargeDate)
                .orderByDesc(HisInpChargeDetail::getId);
        return chargeMapper.selectPage(new Page<>(page, size), q);
    }

    /** 费用汇总(按 fee_type 分组 SUM, 仅 status=1 正常明细; 返回含类别名称与合计行) */
    public Map<String, Object> chargeSummary(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT fee_type, COALESCE(SUM(quantity), 0) total_qty, COALESCE(SUM(amount), 0) total_amount"
                        + " FROM his_inp_charge_detail"
                        + " WHERE inp_visit_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?"
                        + " GROUP BY fee_type ORDER BY fee_type",
                visitId, tenantId());
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            int feeType = ((Number) row.get("fee_type")).intValue();
            BigDecimal amt = new BigDecimal(String.valueOf(row.get("total_amount")));
            BigDecimal qty = new BigDecimal(String.valueOf(row.get("total_qty")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feeType", feeType);
            m.put("feeTypeName", FEE_TYPE_NAMES.getOrDefault(feeType, "其他"));
            m.put("totalQuantity", qty);
            m.put("totalAmount", amt);
            items.add(m);
            totalAmount = totalAmount.add(amt);
            totalQty = totalQty.add(qty);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visitId", visitId);
        result.put("items", items);
        result.put("totalQuantity", totalQty);
        result.put("totalAmount", totalAmount);
        return result;
    }

    /* ==================== 记账 ==================== */

    /**
     * 手动补录费用: 客户端单价不可信, 传 chargeItemId 时服务端按收费目录重算
     * (名称/编码/单价以目录为准, 执行价按机构 price_lv 取档); 未传项目则要求显式单价。
     * 金额 = 单价 x 数量(服务端计算), 同时累加 visit.total_cost。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpChargeDetail addCharge(HisInpChargeDetail detail, Long orgId) {
        if (detail == null || detail.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(detail.getInpVisitId());
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        if (visit.getVisitStatus() == null || visit.getVisitStatus() == 4 || visit.getVisitStatus() == 5) {
            throw new BizException("该就诊已出院或已取消, 不能补录费用");
        }
        if (detail.getQuantity() == null || detail.getQuantity().signum() <= 0) {
            throw new BizException(400, "数量必须大于0");
        }
        BigDecimal qty = detail.getQuantity();
        BigDecimal price = detail.getUnitPrice();
        String itemName = detail.getItemName();
        String itemCode = detail.getItemCode();
        // 传了收费项目则服务端按目录取数定价(客户端价格仅作展示回显, 不作为记账依据)
        if (detail.getChargeItemId() != null) {
            Map<String, Object> cat = loadChargeItem(detail.getChargeItemId(), orgId);
            itemName = str(cat.get("item_name"));
            itemCode = str(cat.get("item_code"));
            price = execPrice(cat, orgId);
        }
        if (!StringUtils.hasText(itemName)) {
            throw new BizException(400, "项目名称不能为空");
        }
        if (price == null || price.signum() < 0) {
            throw new BizException(400, "项目单价无效, 请维护收费目录价格或显式传入单价");
        }
        detail.setOrgId(orgId);
        detail.setItemName(itemName.trim());
        detail.setItemCode(StringUtils.hasText(itemCode) ? itemCode.trim() : null);
        detail.setUnitPrice(price);
        detail.setAmount(price.multiply(qty).setScale(2, BigDecimal.ROUND_HALF_UP));
        if (detail.getChargeDate() == null) {
            detail.setChargeDate(LocalDate.now());
        }
        if (detail.getFeeType() == null) {
            detail.setFeeType(9);
        }
        detail.setStatus(1);
        detail.setOperatorId(currentStaffId());
        chargeMapper.insert(detail);

        // 累加就诊总费用(原子更新防并发丢失)
        jdbcTemplate.update(
                "UPDATE his_inp_visit SET total_cost = IFNULL(total_cost, 0) + ?, update_time = NOW()"
                        + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                detail.getAmount(), detail.getInpVisitId(), tenantId());
        log.info("补录住院费用: visitId={}, item={}, qty={}, amount={}",
                detail.getInpVisitId(), detail.getItemName(), qty, detail.getAmount());
        // 限额控制: 明细自带日/总限额时校验并联动预警
        applyLimitControl(detail);
        return detail;
    }

    /**
     * 医嘱->费用转换(医嘱执行链路记账入口):
     * - 幂等: 同一医嘱已生成过费用明细则直接返回既有明细(重复执行/重放安全);
     * - 取价: 优先 order.unit_price(开嘱带价), 缺价时药品查 his_drug_catalog.retail_price,
     *   诊疗类查 his_charge_item(按机构 price_lv 取档);
     * - fee_type 由医嘱分类映射: 1药品->1西药 2检查->3 3检验->4 4治疗->5 5护理->6 6膳食/7其他->9。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpChargeDetail generateFromOrder(HisInpOrder order) {
        if (order == null || order.getId() == null || order.getInpVisitId() == null) {
            throw new BizException(400, "医嘱信息不完整, 无法生成费用");
        }
        // 幂等: 同医嘱已有明细则不重复生成
        List<HisInpChargeDetail> exist = chargeMapper.selectList(
                new LambdaQueryWrapper<HisInpChargeDetail>()
                        .eq(HisInpChargeDetail::getOrderId, order.getId())
                        .eq(HisInpChargeDetail::getStatus, 1));
        if (!exist.isEmpty()) {
            return exist.get(0);
        }
        Long orgId = order.getOrgId();
        BigDecimal qty = order.getQuantity() == null ? BigDecimal.ONE : order.getQuantity();
        if (qty.signum() <= 0) {
            throw new BizException(400, "医嘱数量无效: " + qty);
        }

        String itemName = null;
        String itemCode = null;
        BigDecimal price = order.getUnitPrice();
        // 收费项目医嘱: 目录取数(名称/编码), 开嘱价缺失时按机构档取价
        if (order.getChargeItemId() != null) {
            Map<String, Object> cat = loadChargeItem(order.getChargeItemId(), orgId);
            itemName = str(cat.get("item_name"));
            itemCode = str(cat.get("item_code"));
            if (price == null || price.signum() <= 0) {
                price = execPrice(cat, orgId);
            }
        }
        // 药品医嘱: 药品目录零售价(最小单位)
        if (order.getDrugId() != null) {
            List<Map<String, Object>> drugs = jdbcTemplate.queryForList(
                    "SELECT drug_code, trade_name, generic_name, retail_price FROM his_drug_catalog"
                            + " WHERE id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?",
                    order.getDrugId(), tenantId());
            if (drugs.isEmpty()) {
                throw new BizException(400, "医嘱药品不存在或已停用, 无法计费");
            }
            Map<String, Object> drug = drugs.get(0);
            if (!StringUtils.hasText(itemName)) {
                String trade = str(drug.get("trade_name"));
                itemName = StringUtils.hasText(trade) ? trade : str(drug.get("generic_name"));
                itemCode = str(drug.get("drug_code"));
            }
            if (price == null || price.signum() <= 0) {
                price = toBd(drug.get("retail_price"));
            }
        }
        // 无目录关联的医嘱(如膳食/护理嘱托): 以医嘱内容为名称, 必须带价
        if (!StringUtils.hasText(itemName)) {
            itemName = StringUtils.hasText(order.getOrderContent()) ? order.getOrderContent().trim() : "医嘱计费项";
        }
        if (price == null || price.signum() < 0) {
            throw new BizException(400, "医嘱未关联可计费项目且未带单价, 无法生成费用: " + itemName);
        }
        if (price.signum() == 0) {
            // 单价为0的嘱托类医嘱(不计费)直接跳过, 返回null由调用方判断
            log.info("医嘱单价为0, 不生成费用: orderId={}, content={}", order.getId(), itemName);
            return null;
        }

        HisInpChargeDetail d = new HisInpChargeDetail();
        d.setOrgId(orgId);
        d.setInpVisitId(order.getInpVisitId());
        d.setChargeItemId(order.getChargeItemId());
        d.setItemName(itemName);
        d.setItemCode(itemCode);
        d.setQuantity(qty);
        d.setUnitPrice(price);
        d.setAmount(price.multiply(qty).setScale(2, BigDecimal.ROUND_HALF_UP));
        d.setChargeDate(order.getStartTime() == null ? LocalDate.now() : order.getStartTime().toLocalDate());
        d.setOrderId(order.getId());
        d.setFeeType(mapFeeType(order.getOrderCategory()));
        d.setStatus(1);
        d.setOperatorId(order.getDoctorId() != null ? order.getDoctorId() : currentStaffId());
        chargeMapper.insert(d);

        // 累加就诊总费用
        jdbcTemplate.update(
                "UPDATE his_inp_visit SET total_cost = IFNULL(total_cost, 0) + ?, update_time = NOW()"
                        + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                d.getAmount(), d.getInpVisitId(), tenantId());
        log.info("医嘱生成费用: orderId={}, item={}, qty={}, amount={}",
                order.getId(), d.getItemName(), qty, d.getAmount());
        // 限额控制: 明细自带日/总限额时校验并联动预警
        applyLimitControl(d);
        return d;
    }

    /* ==================== 限额控制/费用审核(模型增强) ==================== */

    /**
     * 限额控制: 明细自带日限额/总限额时校验当日/累计费用(含本次, 仅 status=1 正常明细),
     * 超限则生成 fee_alert(type=1日限额/2总限额)并将明细置待审(approval_status=1)。
     */
    private void applyLimitControl(HisInpChargeDetail d) {
        if (d == null || d.getId() == null) {
            return;
        }
        BigDecimal dailyLimit = d.getDailyLimit();
        BigDecimal totalLimit = d.getTotalLimit();
        if (dailyLimit == null && totalLimit == null) {
            return;
        }
        boolean exceeded = false;
        if (dailyLimit != null && dailyLimit.signum() > 0) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT COALESCE(SUM(amount), 0) day_sum FROM his_inp_charge_detail"
                            + " WHERE inp_visit_id = ? AND charge_date = ? AND status = 1"
                            + " AND deleted = 0 AND tenant_id = ?",
                    d.getInpVisitId(), d.getChargeDate(), tenantId());
            BigDecimal daySum = toBd(rows.get(0).get("day_sum"));
            if (daySum != null && daySum.compareTo(dailyLimit) > 0) {
                feeAlertService.createAlert(d.getInpVisitId(), 1, d.getId(), daySum, dailyLimit);
                log.warn("日限额超标: visitId={}, daySum={}, dailyLimit={}",
                        d.getInpVisitId(), daySum, dailyLimit);
                exceeded = true;
            }
        }
        if (totalLimit != null && totalLimit.signum() > 0) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT COALESCE(SUM(amount), 0) total_sum FROM his_inp_charge_detail"
                            + " WHERE inp_visit_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?",
                    d.getInpVisitId(), tenantId());
            BigDecimal totalSum = toBd(rows.get(0).get("total_sum"));
            if (totalSum != null && totalSum.compareTo(totalLimit) > 0) {
                feeAlertService.createAlert(d.getInpVisitId(), 2, d.getId(), totalSum, totalLimit);
                log.warn("总限额超标: visitId={}, totalSum={}, totalLimit={}",
                        d.getInpVisitId(), totalSum, totalLimit);
                exceeded = true;
            }
        }
        if (exceeded) {
            chargeMapper.update(null, new LambdaUpdateWrapper<HisInpChargeDetail>()
                    .eq(HisInpChargeDetail::getId, d.getId())
                    .set(HisInpChargeDetail::getApprovalStatus, 1));
            d.setApprovalStatus(1);
        }
    }

    /** 费用审核(仅待审1可审): 通过→2, 拒绝→3并记录原因; 拒绝后由业务侧自行冲销 */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> approveCharge(Long id, boolean approved, String reason) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        if (!approved && !StringUtils.hasText(reason)) {
            throw new BizException(400, "拒绝时必须填写原因");
        }
        LambdaUpdateWrapper<HisInpChargeDetail> uw = new LambdaUpdateWrapper<HisInpChargeDetail>()
                .eq(HisInpChargeDetail::getId, id)
                .eq(HisInpChargeDetail::getApprovalStatus, 1)
                .set(HisInpChargeDetail::getApprovalStatus, approved ? 2 : 3);
        if (StringUtils.hasText(reason)) {
            uw.set(HisInpChargeDetail::getLimitOverrideReason, reason);
        }
        int affected = chargeMapper.update(null, uw);
        if (affected == 0) {
            throw new BizException("仅待审状态的费用可审核, 状态已变化请刷新后重试");
        }
        log.info("费用审核: id={}, approved={}, reason={}", id, approved, reason);
        return R.ok();
    }

    /** 待审核费用分页(approval_status=1, 机构可选筛选) */
    public R<IPage<HisInpChargeDetail>> getPendingApprovals(Long orgId, Page<HisInpChargeDetail> page) {
        Page<HisInpChargeDetail> p = page != null ? page : new Page<>(1, 10);
        LambdaQueryWrapper<HisInpChargeDetail> q = new LambdaQueryWrapper<HisInpChargeDetail>()
                .eq(HisInpChargeDetail::getApprovalStatus, 1)
                .eq(orgId != null, HisInpChargeDetail::getOrgId, orgId)
                .orderByDesc(HisInpChargeDetail::getId);
        return R.ok(chargeMapper.selectPage(p, q));
    }

    /** 某日费用清单(date=null 取当天, 含类别名称, 按明细ID升序) */
    public R<List<Map<String, Object>>> getDailyChargeList(Long visitId, LocalDate date) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        LocalDate day = date != null ? date : LocalDate.now();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, item_name, item_code, fee_type, quantity, unit_price, amount, charge_date,"
                        + " status, approval_status, order_id FROM his_inp_charge_detail"
                        + " WHERE inp_visit_id = ? AND charge_date = ? AND deleted = 0 AND tenant_id = ?"
                        + " ORDER BY id",
                visitId, day, tenantId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            int ft = row.get("fee_type") == null ? 9 : ((Number) row.get("fee_type")).intValue();
            m.put("fee_type_name", FEE_TYPE_NAMES.getOrDefault(ft, "其他"));
            items.add(m);
        }
        return R.ok(items);
    }

    /* ==================== 定价工具 ==================== */

    /** 收费项目启用行(名称/编码/各档价格), 不存在或停用抛异常 */
    private Map<String, Object> loadChargeItem(Long chargeItemId, Long orgId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT item_name, item_code, price, price_l1, price_l2, price_l3 FROM his_charge_item"
                        + " WHERE id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?",
                chargeItemId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "收费项目不存在或已停用, 无法计费");
        }
        return rows.get(0);
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

    /** 医嘱分类 -> 费用类别(1药品->1西药 2检查->3 3检验->4 4治疗->5 5护理->6 6膳食/7其他->9) */
    private static int mapFeeType(Integer orderCategory) {
        if (orderCategory == null) {
            return 9;
        }
        switch (orderCategory) {
            case 1:
                return 1;
            case 2:
                return 3;
            case 3:
                return 4;
            case 4:
                return 5;
            case 5:
                return 6;
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
