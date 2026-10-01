package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.warehouse.StockInItemReq;
import com.yb.hi.dto.warehouse.StockInReq;
import com.yb.hi.entity.warehouse.HisPurchaseOrder;
import com.yb.hi.entity.warehouse.HisPurchaseOrderItem;
import com.yb.hi.entity.warehouse.HisPurchasePlan;
import com.yb.hi.entity.warehouse.HisPurchasePlanItem;
import com.yb.hi.entity.warehouse.HisPurchaseRule;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisSupplier;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.warehouse.HisPurchaseOrderItemMapper;
import com.yb.hi.mapper.warehouse.HisPurchaseOrderMapper;
import com.yb.hi.mapper.warehouse.HisPurchasePlanItemMapper;
import com.yb.hi.mapper.warehouse.HisPurchasePlanMapper;
import com.yb.hi.mapper.warehouse.HisPurchaseRuleMapper;
import com.yb.hi.mapper.warehouse.HisSupplierMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药品采购服务(批次A): 供应商主数据 + 智能采购规则 + 采购计划(智能生成/审批) + 采购订单(转单/引入入库/集采上传占位)。
 * - 单号: 计划 CH / 订单 CO, 前缀+yyyyMMdd+4位, synchronized + 唯一键回读兜底;
 * - 智能生成: 读 his_drug_stock 当前库存 + 上月已确认出入库 + 药房发药量, 按 his_purchase_rule 高低储/权重计算建议量, ABC 按消耗金额占比现算;
 * - tenant_id 由租户插件注入(Mapper)/JdbcTemplate 显式带 tenant_id; 引入入库复用 {@link DrugStockService#createStockIn}。
 */
@Slf4j
@Service
public class PurchaseService {

    private final HisSupplierMapper supplierMapper;
    private final HisPurchaseRuleMapper ruleMapper;
    private final HisPurchasePlanMapper planMapper;
    private final HisPurchasePlanItemMapper planItemMapper;
    private final HisPurchaseOrderMapper orderMapper;
    private final HisPurchaseOrderItemMapper orderItemMapper;
    private final DrugStockService drugStockService;
    private final JdbcTemplate jdbcTemplate;

    public PurchaseService(HisSupplierMapper supplierMapper, HisPurchaseRuleMapper ruleMapper,
                           HisPurchasePlanMapper planMapper, HisPurchasePlanItemMapper planItemMapper,
                           HisPurchaseOrderMapper orderMapper, HisPurchaseOrderItemMapper orderItemMapper,
                           DrugStockService drugStockService, JdbcTemplate jdbcTemplate) {
        this.supplierMapper = supplierMapper;
        this.ruleMapper = ruleMapper;
        this.planMapper = planMapper;
        this.planItemMapper = planItemMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.drugStockService = drugStockService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 供应商主数据 ================= */

    public IPage<HisSupplier> supplierPage(Long keyword, String kw, long page, long size) {
        LambdaQueryWrapper<HisSupplier> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(kw)) {
            String k = kw.trim();
            w.and(q -> q.like(HisSupplier::getSupplierName, k).or().like(HisSupplier::getSupplierCode, k));
        }
        w.orderByDesc(HisSupplier::getId);
        return supplierMapper.selectPage(new Page<>(page, size), w);
    }

    public List<HisSupplier> supplierList(Boolean jtOnly) {
        LambdaQueryWrapper<HisSupplier> w = new LambdaQueryWrapper<>();
        w.eq(HisSupplier::getStatus, 1);
        if (Boolean.TRUE.equals(jtOnly)) {
            w.eq(HisSupplier::getJtFlag, 1);
        }
        w.orderByAsc(HisSupplier::getSupplierCode);
        return supplierMapper.selectList(w);
    }

    public HisSupplier supplierGet(Long id) {
        HisSupplier s = supplierMapper.selectById(id);
        if (s == null) {
            throw new BizException(400, "供应商不存在");
        }
        return s;
    }

    @Transactional(rollbackFor = Exception.class)
    public HisSupplier saveSupplier(HisSupplier s) {
        if (s == null || !StringUtils.hasText(s.getSupplierCode()) || !StringUtils.hasText(s.getSupplierName())) {
            throw new BizException(400, "供应商编码与名称不能为空");
        }
        Long dup = supplierMapper.selectCount(new LambdaQueryWrapper<HisSupplier>()
                .eq(HisSupplier::getSupplierCode, s.getSupplierCode().trim())
                .ne(s.getId() != null, HisSupplier::getId, s.getId()));
        if (dup != null && dup > 0) {
            throw new BizException(400, "供应商编码已存在: " + s.getSupplierCode());
        }
        s.setSupplierCode(s.getSupplierCode().trim());
        if (s.getStatus() == null) {
            s.setStatus(1);
        }
        if (s.getJtFlag() == null) {
            s.setJtFlag(0);
        }
        if (s.getId() == null) {
            supplierMapper.insert(s);
        } else {
            supplierMapper.updateById(s);
        }
        return s;
    }

    @Transactional(rollbackFor = Exception.class)
    public void toggleSupplier(Long id, boolean enabled) {
        HisSupplier s = supplierGet(id);
        HisSupplier upd = new HisSupplier();
        upd.setId(s.getId());
        upd.setStatus(enabled ? 1 : 0);
        supplierMapper.updateById(upd);
    }

    /* ================= 智能采购规则 ================= */

    public List<HisPurchaseRule> ruleList(Long orgId, Long warehouseId) {
        LambdaQueryWrapper<HisPurchaseRule> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisPurchaseRule::getOrgId, orgId)
                .eq(warehouseId != null, HisPurchaseRule::getWarehouseId, warehouseId)
                .orderByDesc(HisPurchaseRule::getId);
        return ruleMapper.selectList(w);
    }

    @Transactional(rollbackFor = Exception.class)
    public HisPurchaseRule saveRule(HisPurchaseRule r) {
        if (r == null) {
            throw new BizException(400, "规则不能为空");
        }
        if (r.getEnabled() == null) {
            r.setEnabled(1);
        }
        if (r.getReferMonths() == null || r.getReferMonths() <= 0) {
            r.setReferMonths(1);
        }
        if (r.getId() == null) {
            ruleMapper.insert(r);
        } else {
            ruleMapper.updateById(r);
        }
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(Long id) {
        if (ruleMapper.deleteById(id) == 0) {
            throw new BizException(400, "规则不存在");
        }
    }

    /* ================= 采购计划 ================= */

    public IPage<HisPurchasePlan> planPage(Long orgId, Long warehouseId, Integer status, String startDate, String endDate, long page, long size) {
        LambdaQueryWrapper<HisPurchasePlan> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisPurchasePlan::getOrgId, orgId)
                .eq(warehouseId != null, HisPurchasePlan::getWarehouseId, warehouseId)
                .eq(status != null, HisPurchasePlan::getStatus, status);
        LocalDateTime[] range = parseDateRange(startDate, endDate);
        if (range[0] != null) {
            w.ge(HisPurchasePlan::getCreateTime, range[0]);
        }
        if (range[1] != null) {
            w.lt(HisPurchasePlan::getCreateTime, range[1]);
        }
        w.orderByDesc(HisPurchasePlan::getId);
        return planMapper.selectPage(new Page<>(page, size), w);
    }

    public Map<String, Object> planDetail(Long id) {
        HisPurchasePlan main = planMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "采购计划不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("items", planItemMapper.selectList(new LambdaQueryWrapper<HisPurchasePlanItem>()
                .eq(HisPurchasePlanItem::getPlanId, id).orderByAsc(HisPurchasePlanItem::getId)));
        return out;
    }

    /**
     * 智能生成采购计划(草稿): 按目标药库在仓药品 + 上月出入库/发药量 + 采购规则计算建议量, ABC 现算。
     * warehouseId 为空则汇总本机构(或牵头全部)全部在仓药品; majorClass 非空则按大类模糊筛选候选。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisPurchasePlan autoGeneratePlan(Long orgId, Long warehouseId, String majorClass, String remark) {
        LocalDate today = LocalDate.now();
        LocalDateTime monthBegin = today.withDayOfMonth(1).minusMonths(1).atStartOfDay();
        LocalDateTime monthEnd = today.withDayOfMonth(1).atStartOfDay();
        long tenant = tenantId();

        // 候选: 目标药库在仓药品 + 当前库存 + 目录价格/大类
        StringBuilder cs = new StringBuilder("SELECT s.drug_catalog_id, MAX(s.drug_code) drug_code, MAX(s.drug_name) drug_name,"
                + " MAX(s.spec) spec, MAX(s.manufacturer) manufacturer, MAX(dc.major_class) major_class,"
                + " MAX(dc.purchase_price) purchase_price, MAX(dc.retail_price) retail_price, MAX(s.warn_qty) warn_qty,"
                + " SUM(s.qty) cur_stock FROM his_drug_stock s"
                + " LEFT JOIN his_drug_catalog dc ON dc.id = s.drug_catalog_id AND dc.deleted = 0"
                + " WHERE s.tenant_id = ? AND s.deleted = 0 AND s.status = 1");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (orgId != null) {
            cs.append(" AND s.org_id = ?");
            args.add(orgId);
        }
        if (warehouseId != null) {
            cs.append(" AND s.warehouse_id = ?");
            args.add(warehouseId);
        }
        if (StringUtils.hasText(majorClass)) {
            cs.append(" AND dc.major_class LIKE ?");
            args.add("%" + majorClass.trim() + "%");
        }
        cs.append(" GROUP BY s.drug_catalog_id");
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList(cs.toString(), args.toArray());
        if (CollectionUtils.isEmpty(candidates)) {
            throw new BizException("该范围无在仓药品, 无法生成采购计划");
        }

        Map<Long, BigDecimal> inMap = monthlyFlow(true, tenant, orgId, warehouseId, monthBegin, monthEnd);
        Map<Long, BigDecimal> outMap = monthlyFlow(false, tenant, orgId, warehouseId, monthBegin, monthEnd);
        Map<Long, BigDecimal> dispMap = dispenseQty(tenant, orgId, monthBegin, monthEnd);

        // 采购规则(取本机构该药库启用规则; 无则全院默认 warehouse_id IS NULL; 再无则内置)
        HisPurchaseRule rule = resolveRule(orgId, warehouseId);
        BigDecimal dispW = nvl(rule == null ? null : rule.getDispenseWeight(), BigDecimal.ONE);
        BigDecimal stockW = nvl(rule == null ? null : rule.getStockoutWeight(), BigDecimal.ONE);
        int referMonths = rule == null || rule.getReferMonths() == null || rule.getReferMonths() <= 0 ? 1 : rule.getReferMonths();
        BigDecimal aRatio = nvl(rule == null ? null : rule.getAbcARatio(), new BigDecimal("0.80"));
        BigDecimal bRatio = nvl(rule == null ? null : rule.getAbcBRatio(), new BigDecimal("0.95"));

        // 逐候选计算预测消耗/建议量, 累计消耗金额用于 ABC
        List<Map<String, Object>> calc = new ArrayList<>();
        BigDecimal totalConsumeAmt = BigDecimal.ZERO;
        for (Map<String, Object> c : candidates) {
            Long did = asLong(c.get("drug_catalog_id"));
            BigDecimal cur = nvl(asDecimal(c.get("cur_stock")), BigDecimal.ZERO);
            BigDecimal lastIn = inMap.getOrDefault(did, BigDecimal.ZERO);
            BigDecimal lastOut = outMap.getOrDefault(did, BigDecimal.ZERO);
            BigDecimal disp = dispMap.getOrDefault(did, BigDecimal.ZERO);
            BigDecimal purchasePrice = nvl(asDecimal(c.get("purchase_price")), asDecimal(c.get("retail_price")));
            if (purchasePrice == null) {
                purchasePrice = BigDecimal.ZERO;
            }
            // 月预测消耗 = 上月出库*出库权重 + 药房发药*发药权重(取大者兜底避免为0)
            BigDecimal monthlyUsage = lastOut.multiply(stockW).add(disp.multiply(dispW));
            if (monthlyUsage.compareTo(BigDecimal.ZERO) <= 0 && lastIn.compareTo(BigDecimal.ZERO) > 0) {
                monthlyUsage = lastIn;
            }
            BigDecimal lo = rule != null && rule.getLoQty() != null ? rule.getLoQty() : nvl(asDecimal(c.get("warn_qty")), BigDecimal.ZERO);
            BigDecimal hi = rule != null && rule.getHiQty() != null ? rule.getHiQty()
                    : (lo.compareTo(BigDecimal.ZERO) > 0 ? lo.multiply(new BigDecimal("2")) : monthlyUsage.multiply(new BigDecimal(referMonths)));
            // 目标储备 = max(高储, 月消耗*参考月数); 建议量 = 目标 - 当前库存, 仅当库存<=补货点(低储)或低于目标时为正
            BigDecimal target = hi.max(monthlyUsage.multiply(new BigDecimal(referMonths)));
            BigDecimal suggest = target.subtract(cur);
            if (cur.compareTo(lo) > 0 && suggest.compareTo(BigDecimal.ZERO) <= 0) {
                suggest = BigDecimal.ZERO;
            }
            if (suggest.compareTo(BigDecimal.ZERO) < 0) {
                suggest = BigDecimal.ZERO;
            }
            suggest = suggest.setScale(2, RoundingMode.CEILING);
            BigDecimal consumeAmt = monthlyUsage.multiply(purchasePrice);
            totalConsumeAmt = totalConsumeAmt.add(consumeAmt);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("did", did);
            row.put("cur", cur);
            row.put("lastIn", lastIn);
            row.put("lastOut", lastOut);
            row.put("disp", disp);
            row.put("lo", lo);
            row.put("hi", hi);
            row.put("suggest", suggest);
            row.put("price", purchasePrice);
            row.put("consumeAmt", consumeAmt);
            row.putAll(c);
            calc.add(row);
        }

        // ABC: 按消耗金额降序累计占比切分 A/B/C
        calc.sort((x, y) -> asDecimal(y.get("consumeAmt")).compareTo(asDecimal(x.get("consumeAmt"))));
        BigDecimal cum = BigDecimal.ZERO;
        for (Map<String, Object> row : calc) {
            BigDecimal amt = asDecimal(row.get("consumeAmt"));
            cum = cum.add(amt);
            String abc = "C";
            if (totalConsumeAmt.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ratio = cum.divide(totalConsumeAmt, 4, RoundingMode.HALF_UP);
                if (ratio.compareTo(aRatio) <= 0) {
                    abc = "A";
                } else if (ratio.compareTo(bRatio) <= 0) {
                    abc = "B";
                }
            }
            row.put("abc", abc);
        }

        // 落草稿计划(仅纳入建议量>0的药品)
        HisPurchasePlan plan = new HisPurchasePlan();
        plan.setOrgId(orgId);
        plan.setWarehouseId(warehouseId);
        plan.setPlanNo(generatePlanNo());
        plan.setGenType("auto");
        plan.setStatus(0);
        plan.setTotalAmount(BigDecimal.ZERO);
        plan.setRemark(remark);
        planMapper.insert(plan);

        BigDecimal total = BigDecimal.ZERO;
        int cnt = 0;
        for (Map<String, Object> row : calc) {
            BigDecimal suggest = asDecimal(row.get("suggest"));
            if (suggest.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal price = nvl(asDecimal(row.get("price")), BigDecimal.ZERO);
            BigDecimal amount = suggest.multiply(price).setScale(2, RoundingMode.HALF_UP);
            HisPurchasePlanItem item = new HisPurchasePlanItem();
            item.setPlanId(plan.getId());
            item.setDrugCatalogId(asLong(row.get("did")));
            item.setDrugCode(asStr(row.get("drug_code")));
            item.setDrugName(asStr(row.get("drug_name")));
            item.setSpec(asStr(row.get("spec")));
            item.setManufacturer(asStr(row.get("manufacturer")));
            item.setMajorClass(asStr(row.get("major_class")));
            item.setCurStock(asDecimal(row.get("cur")));
            item.setLoQty(asDecimal(row.get("lo")));
            item.setHiQty(asDecimal(row.get("hi")));
            item.setLastMonthIn(asDecimal(row.get("lastIn")));
            item.setLastMonthOut(asDecimal(row.get("lastOut")));
            item.setDispenseQty(asDecimal(row.get("disp")));
            item.setAbcClass(asStr(row.get("abc")));
            item.setQtySuggest(suggest);
            item.setPrice(price);
            item.setAmount(amount);
            planItemMapper.insert(item);
            total = total.add(amount);
            cnt++;
        }
        HisPurchasePlan upd = new HisPurchasePlan();
        upd.setId(plan.getId());
        upd.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        planMapper.updateById(upd);
        plan.setTotalAmount(upd.getTotalAmount());
        log.info("智能生成采购计划: id={}, planNo={}, items={}, total={}", plan.getId(), plan.getPlanNo(), cnt, plan.getTotalAmount());
        return plan;
    }

    /** 手工新增/更新计划明细(仅草稿态): items 全量覆盖该计划明细 */
    @Transactional(rollbackFor = Exception.class)
    public HisPurchasePlan savePlanItems(Long planId, List<HisPurchasePlanItem> items) {
        HisPurchasePlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException(400, "采购计划不存在");
        }
        if (plan.getStatus() == null || plan.getStatus() != 0) {
            throw new BizException("仅草稿态计划可编辑明细: " + plan.getPlanNo());
        }
        planItemMapper.delete(new LambdaQueryWrapper<HisPurchasePlanItem>().eq(HisPurchasePlanItem::getPlanId, planId));
        BigDecimal total = BigDecimal.ZERO;
        if (!CollectionUtils.isEmpty(items)) {
            for (HisPurchasePlanItem it : items) {
                it.setId(null);
                it.setPlanId(planId);
                if (it.getQtySuggest() == null) {
                    it.setQtySuggest(BigDecimal.ZERO);
                }
                BigDecimal price = nvl(it.getPrice(), BigDecimal.ZERO);
                if (it.getAmount() == null) {
                    it.setAmount(it.getQtySuggest().multiply(price).setScale(2, RoundingMode.HALF_UP));
                }
                planItemMapper.insert(it);
                total = total.add(nvl(it.getAmount()));
            }
        }
        HisPurchasePlan upd = new HisPurchasePlan();
        upd.setId(planId);
        upd.setGenType("manual");
        upd.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        planMapper.updateById(upd);
        return plan;
    }

    /** 新建空白手工计划(草稿) */
    @Transactional(rollbackFor = Exception.class)
    public HisPurchasePlan createManualPlan(Long orgId, Long warehouseId, Long supplierId, String remark) {
        HisPurchasePlan plan = new HisPurchasePlan();
        plan.setOrgId(orgId);
        plan.setWarehouseId(warehouseId);
        plan.setSupplierId(supplierId);
        plan.setPlanNo(generatePlanNo());
        plan.setGenType("manual");
        plan.setStatus(0);
        plan.setTotalAmount(BigDecimal.ZERO);
        plan.setRemark(remark);
        planMapper.insert(plan);
        return plan;
    }

    /** 提交审批: 草稿(0)→待审(1), 需有明细 */
    @Transactional(rollbackFor = Exception.class)
    public void submitPlan(Long id) {
        HisPurchasePlan plan = requireStatus(id, 0, "仅草稿态计划可提交");
        Long cnt = planItemMapper.selectCount(new LambdaQueryWrapper<HisPurchasePlanItem>().eq(HisPurchasePlanItem::getPlanId, id));
        if (cnt == null || cnt == 0) {
            throw new BizException("计划无明细, 不可提交: " + plan.getPlanNo());
        }
        HisPurchasePlan upd = new HisPurchasePlan();
        upd.setId(id);
        upd.setStatus(1);
        upd.setSubmitBy(currentUserName());
        upd.setSubmitTime(LocalDateTime.now());
        planMapper.updateById(upd);
    }

    /** 审批通过: 待审(1)→已审(2) */
    @Transactional(rollbackFor = Exception.class)
    public void approvePlan(Long id) {
        requireStatus(id, 1, "仅待审态计划可审批");
        HisPurchasePlan upd = new HisPurchasePlan();
        upd.setId(id);
        upd.setStatus(2);
        upd.setApproveBy(currentUserName());
        upd.setApproveTime(LocalDateTime.now());
        planMapper.updateById(upd);
    }

    /** 审批驳回: 待审(1)→已驳回(3) */
    @Transactional(rollbackFor = Exception.class)
    public void rejectPlan(Long id, String reason) {
        requireStatus(id, 1, "仅待审态计划可驳回");
        HisPurchasePlan plan = planMapper.selectById(id);
        HisPurchasePlan upd = new HisPurchasePlan();
        upd.setId(id);
        upd.setStatus(3);
        upd.setApproveBy(currentUserName());
        upd.setApproveTime(LocalDateTime.now());
        upd.setRemark(StringUtils.hasText(reason) ? reason : plan.getRemark());
        planMapper.updateById(upd);
    }

    /** 作废计划: 草稿/待审/已驳回可作废(已转订单的不可) */
    @Transactional(rollbackFor = Exception.class)
    public void voidPlan(Long id) {
        HisPurchasePlan plan = planMapper.selectById(id);
        if (plan == null) {
            throw new BizException(400, "采购计划不存在");
        }
        if (plan.getStatus() != null && plan.getStatus() == 9) {
            throw new BizException("已转订单的计划不可作废: " + plan.getPlanNo());
        }
        if (plan.getStatus() != null && plan.getStatus() == -2) {
            return;
        }
        HisPurchasePlan upd = new HisPurchasePlan();
        upd.setId(id);
        upd.setStatus(-2);
        planMapper.updateById(upd);
    }

    /**
     * 计划转采购订单: 已审(2)计划 → 生成订单(草稿0) + 明细继承, 计划置已转订单(9)。
     * supplierId 为空则取计划供应商; 仅建议量>0 的明细转订单。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisPurchaseOrder convertPlanToOrder(Long planId, Long supplierId) {
        HisPurchasePlan plan = requireStatus(planId, 2, "仅已审计划可转订单");
        Long useSupplier = supplierId != null ? supplierId : plan.getSupplierId();
        List<HisPurchasePlanItem> items = planItemMapper.selectList(new LambdaQueryWrapper<HisPurchasePlanItem>()
                .eq(HisPurchasePlanItem::getPlanId, planId).gt(HisPurchasePlanItem::getQtySuggest, BigDecimal.ZERO));
        if (CollectionUtils.isEmpty(items)) {
            throw new BizException("计划无建议采购数量>0 的明细, 不可转订单");
        }
        HisPurchaseOrder order = new HisPurchaseOrder();
        order.setOrgId(plan.getOrgId());
        order.setWarehouseId(plan.getWarehouseId());
        order.setPlanId(planId);
        order.setSupplierId(useSupplier);
        order.setOrderNo(generateOrderNo());
        order.setStatus(0);
        order.setUploadStatus(0);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setRemark("由计划 " + plan.getPlanNo() + " 转生");
        orderMapper.insert(order);

        BigDecimal total = BigDecimal.ZERO;
        for (HisPurchasePlanItem pi : items) {
            HisPurchaseOrderItem oi = new HisPurchaseOrderItem();
            oi.setOrderId(order.getId());
            oi.setPlanItemId(pi.getId());
            oi.setDrugCatalogId(pi.getDrugCatalogId());
            oi.setDrugCode(pi.getDrugCode());
            oi.setDrugName(pi.getDrugName());
            oi.setSpec(pi.getSpec());
            oi.setManufacturer(pi.getManufacturer());
            oi.setQty(pi.getQtySuggest());
            oi.setQtyReceived(BigDecimal.ZERO);
            oi.setPrice(pi.getPrice());
            BigDecimal amount = nvl(pi.getAmount());
            oi.setAmount(amount);
            orderItemMapper.insert(oi);
            total = total.add(amount);
        }
        HisPurchaseOrder upd = new HisPurchaseOrder();
        upd.setId(order.getId());
        upd.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        orderMapper.updateById(upd);
        order.setTotalAmount(upd.getTotalAmount());

        HisPurchasePlan planUpd = new HisPurchasePlan();
        planUpd.setId(planId);
        planUpd.setStatus(9);
        planMapper.updateById(planUpd);
        log.info("计划转订单: planId={}, orderId={}, orderNo={}", planId, order.getId(), order.getOrderNo());
        return order;
    }

    /* ================= 采购订单 ================= */

    public IPage<HisPurchaseOrder> orderPage(Long orgId, Long warehouseId, Long supplierId, Integer status, String startDate, String endDate, long page, long size) {
        LambdaQueryWrapper<HisPurchaseOrder> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisPurchaseOrder::getOrgId, orgId)
                .eq(warehouseId != null, HisPurchaseOrder::getWarehouseId, warehouseId)
                .eq(supplierId != null, HisPurchaseOrder::getSupplierId, supplierId)
                .eq(status != null, HisPurchaseOrder::getStatus, status);
        LocalDateTime[] range = parseDateRange(startDate, endDate);
        if (range[0] != null) {
            w.ge(HisPurchaseOrder::getCreateTime, range[0]);
        }
        if (range[1] != null) {
            w.lt(HisPurchaseOrder::getCreateTime, range[1]);
        }
        w.orderByDesc(HisPurchaseOrder::getId);
        return orderMapper.selectPage(new Page<>(page, size), w);
    }

    public Map<String, Object> orderDetail(Long id) {
        HisPurchaseOrder main = orderMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "采购订单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("items", orderItemMapper.selectList(new LambdaQueryWrapper<HisPurchaseOrderItem>()
                .eq(HisPurchaseOrderItem::getOrderId, id).orderByAsc(HisPurchaseOrderItem::getId)));
        return out;
    }

    /** 订单下单: 草稿(0)→已下单(1) */
    @Transactional(rollbackFor = Exception.class)
    public void placeOrder(Long id) {
        HisPurchaseOrder order = requireOrderStatus(id, 0, "仅草稿态订单可下单");
        HisPurchaseOrder upd = new HisPurchaseOrder();
        upd.setId(order.getId());
        upd.setStatus(1);
        orderMapper.updateById(upd);
    }

    /** 作废订单: 草稿/已下单可作废 */
    @Transactional(rollbackFor = Exception.class)
    public void voidOrder(Long id) {
        HisPurchaseOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException(400, "采购订单不存在");
        }
        if (order.getStatus() != null && (order.getStatus() == 3 || order.getStatus() == -2)) {
            throw new BizException("已完成/已作废订单不可作废");
        }
        HisPurchaseOrder upd = new HisPurchaseOrder();
        upd.setId(id);
        upd.setStatus(-2);
        orderMapper.updateById(upd);
    }

    /** 集采/统采上传(Mock 占位): 写 upload_status=9 + 回执 */
    @Transactional(rollbackFor = Exception.class)
    public HisPurchaseOrder uploadOrder(Long id) {
        HisPurchaseOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException(400, "采购订单不存在");
        }
        HisPurchaseOrder upd = new HisPurchaseOrder();
        upd.setId(id);
        upd.setUploadStatus(9);
        upd.setUploadTime(LocalDateTime.now());
        upd.setUploadReceipt("MOCK-UPLOAD-" + order.getOrderNo());
        orderMapper.updateById(upd);
        order.setUploadStatus(9);
        order.setUploadTime(upd.getUploadTime());
        order.setUploadReceipt(upd.getUploadReceipt());
        log.info("集采上传(Mock): orderId={}, orderNo={}", id, order.getOrderNo());
        return order;
    }

    /**
     * 引入入库: 按订单明细预填 采购入库单(草稿, in_type=1), 复用 DrugStockService.createStockIn。
     * 供应商名从 his_supplier 带出; 单价取订单进价(costPrice), 数量取订购量。返回入库单(需另行确认入库)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisStockIn importToStockIn(Long orderId) {
        HisPurchaseOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(400, "采购订单不存在");
        }
        List<HisPurchaseOrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<HisPurchaseOrderItem>()
                .eq(HisPurchaseOrderItem::getOrderId, orderId));
        if (CollectionUtils.isEmpty(items)) {
            throw new BizException("订单无明细, 不可引入入库");
        }
        String supplierName = null;
        String supplierContact = null;
        if (order.getSupplierId() != null) {
            HisSupplier sup = supplierMapper.selectById(order.getSupplierId());
            if (sup != null) {
                supplierName = sup.getSupplierName();
                supplierContact = sup.getContact() + (StringUtils.hasText(sup.getPhone()) ? " " + sup.getPhone() : "");
            }
        }
        StockInReq req = new StockInReq();
        req.setOrgId(order.getOrgId());
        req.setWarehouseId(order.getWarehouseId());
        req.setInType(1);
        req.setSupplier(supplierName);
        req.setSupplierId(order.getSupplierId());
        req.setPurchaseOrderId(orderId);
        req.setSupplierContact(supplierContact);
        req.setRemark("采购订单引入: " + order.getOrderNo());
        List<StockInItemReq> inItems = new ArrayList<>();
        for (HisPurchaseOrderItem oi : items) {
            if (nvl(oi.getQty()).compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            StockInItemReq sir = new StockInItemReq();
            sir.setDrugCatalogId(oi.getDrugCatalogId());
            sir.setDrugCode(oi.getDrugCode());
            sir.setDrugName(oi.getDrugName());
            sir.setSpec(oi.getSpec());
            sir.setManufacturer(oi.getManufacturer());
            sir.setBatchNo("PO" + order.getOrderNo() + "-" + oi.getId());
            sir.setQty(oi.getQty());
            sir.setCostPrice(oi.getPrice());
            inItems.add(sir);
        }
        if (inItems.isEmpty()) {
            throw new BizException("订单明细数量均为0, 不可引入入库");
        }
        req.setItems(inItems);
        HisStockIn stockIn = drugStockService.createStockIn(req);
        log.info("订单引入入库单: orderId={}, stockInId={}, inNo={}", orderId, stockIn.getId(), stockIn.getInNo());
        return stockIn;
    }

    /* ================= 内部辅助 ================= */

    private HisPurchaseRule resolveRule(Long orgId, Long warehouseId) {
        LambdaQueryWrapper<HisPurchaseRule> w = new LambdaQueryWrapper<>();
        w.eq(HisPurchaseRule::getEnabled, 1).eq(HisPurchaseRule::getOrgId, orgId);
        if (warehouseId != null) {
            w.eq(HisPurchaseRule::getWarehouseId, warehouseId);
        }
        w.orderByDesc(HisPurchaseRule::getWarehouseId).orderByDesc(HisPurchaseRule::getId).last("LIMIT 1");
        return ruleMapper.selectOne(w);
    }

    private Map<Long, BigDecimal> monthlyFlow(boolean inbound, long tenant, Long orgId, Long warehouseId,
                                              LocalDateTime begin, LocalDateTime end) {
        String item = inbound ? "his_stock_in_item" : "his_stock_out_item";
        String main = inbound ? "his_stock_in" : "his_stock_out";
        StringBuilder sql = new StringBuilder("SELECT i.drug_catalog_id did, SUM(i.qty) q FROM " + item + " i"
                + " JOIN " + main + " m ON m.id = i." + (inbound ? "stock_in_id" : "stock_out_id")
                + " WHERE m.tenant_id = ? AND m.status = 1 AND m.deleted = 0 AND i.deleted = 0"
                + " AND m.confirm_time >= ? AND m.confirm_time < ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.add(begin);
        args.add(end);
        if (orgId != null) {
            sql.append(" AND m.org_id = ?");
            args.add(orgId);
        }
        if (warehouseId != null) {
            sql.append(" AND m.warehouse_id = ?");
            args.add(warehouseId);
        }
        sql.append(" GROUP BY i.drug_catalog_id");
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            map.put(asLong(r.get("did")), nvl(asDecimal(r.get("q")), BigDecimal.ZERO));
        }
        return map;
    }

    private Map<Long, BigDecimal> dispenseQty(long tenant, Long orgId, LocalDateTime begin, LocalDateTime end) {
        StringBuilder sql = new StringBuilder("SELECT pi.drug_id did, SUM(pi.quantity) q FROM his_dispense d"
                + " JOIN his_prescription_item pi ON pi.prescription_id = d.prescription_id AND pi.deleted = 0 AND pi.drug_id IS NOT NULL"
                + " WHERE d.tenant_id = ? AND d.status = 2 AND d.deleted = 0 AND d.dispense_time >= ? AND d.dispense_time < ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.add(begin);
        args.add(end);
        if (orgId != null) {
            sql.append(" AND d.org_id = ?");
            args.add(orgId);
        }
        sql.append(" GROUP BY pi.drug_id");
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            map.put(asLong(r.get("did")), nvl(asDecimal(r.get("q")), BigDecimal.ZERO));
        }
        return map;
    }

    private HisPurchasePlan requireStatus(Long id, int expect, String msg) {
        HisPurchasePlan plan = planMapper.selectById(id);
        if (plan == null) {
            throw new BizException(400, "采购计划不存在");
        }
        if (plan.getStatus() == null || plan.getStatus() != expect) {
            throw new BizException(msg);
        }
        return plan;
    }

    private HisPurchaseOrder requireOrderStatus(Long id, int expect, String msg) {
        HisPurchaseOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException(400, "采购订单不存在");
        }
        if (order.getStatus() == null || order.getStatus() != expect) {
            throw new BizException(msg);
        }
        return order;
    }

    private synchronized String generatePlanNo() {
        return generateNo("CH", true);
    }

    private synchronized String generateOrderNo() {
        return generateNo("CO", false);
    }

    private String generateNo(String prefix, boolean plan) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long seq = 1;
        String no = prefix + today + String.format("%04d", seq);
        while (noExists(no, plan)) {
            seq++;
            no = prefix + today + String.format("%04d", seq);
        }
        return no;
    }

    private boolean noExists(String no, boolean plan) {
        if (plan) {
            return planMapper.selectCount(new LambdaQueryWrapper<HisPurchasePlan>().eq(HisPurchasePlan::getPlanNo, no)) > 0;
        }
        return orderMapper.selectCount(new LambdaQueryWrapper<HisPurchaseOrder>().eq(HisPurchaseOrder::getOrderNo, no)) > 0;
    }

    private LocalDateTime[] parseDateRange(String startDate, String endDate) {
        LocalDateTime begin = null;
        LocalDateTime end = null;
        if (StringUtils.hasText(startDate)) {
            begin = LocalDate.parse(startDate.trim()).atStartOfDay();
        }
        if (StringUtils.hasText(endDate)) {
            end = LocalDate.parse(endDate.trim()).plusDays(1).atStartOfDay();
        }
        return new LocalDateTime[]{begin, end};
    }

    private long tenantId() {
        Long t = TenantContext.get();
        if (t == null) {
            throw new BizException(401, "租户上下文未初始化");
        }
        return t;
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static BigDecimal nvl(BigDecimal v, BigDecimal def) {
        return v != null ? v : def;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof Number ? ((Number) o).longValue() : Long.parseLong(o.toString());
    }

    private static BigDecimal asDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        return new BigDecimal(o.toString());
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }
}
