package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.warehouse.DrugPriceAdjustReq;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.community.HisPriceAdjust;
import com.yb.hi.entity.warehouse.HisDrugPriceAdjust;
import com.yb.hi.entity.warehouse.HisDrugPriceAdjustItem;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.community.HisPriceAdjustMapper;
import com.yb.hi.mapper.warehouse.HisDrugPriceAdjustItemMapper;
import com.yb.hi.mapper.warehouse.HisDrugPriceAdjustMapper;
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
 * 药品调价单服务(药库/药房统一): 预览受影响药品现有进/零售价与在库量 → 建草稿单 → 生效(更新目录当前价 + 在库零售价 + 写 his_price_adjust 留痕)。
 * 目录价格是医共体级(牵头统一维护), 故写操作限牵头(requireLeadWrite); 已开处方/已收费快照价不受影响(发药/收费时已快照, 无需回改)。
 * 单号 TJ+yyyyMMdd+4位; 在库金额影响 = (新零售价-原零售价) × 当前在库数量。作废仅可撤未生效(草稿)单。
 */
@Slf4j
@Service
public class DrugPriceAdjustService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HisDrugPriceAdjustMapper adjustMapper;
    private final HisDrugPriceAdjustItemMapper itemMapper;
    private final HisDrugCatalogMapper drugCatalogMapper;
    private final HisPriceAdjustMapper priceAdjustLogMapper;
    private final JdbcTemplate jdbcTemplate;

    private String seqDate;
    private int seqNo = 0;

    public DrugPriceAdjustService(HisDrugPriceAdjustMapper adjustMapper, HisDrugPriceAdjustItemMapper itemMapper,
                                  HisDrugCatalogMapper drugCatalogMapper, HisPriceAdjustMapper priceAdjustLogMapper,
                                  JdbcTemplate jdbcTemplate) {
        this.adjustMapper = adjustMapper;
        this.itemMapper = itemMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.priceAdjustLogMapper = priceAdjustLogMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 预览 ================= */

    /**
     * 预览调价影响(不落库): 对每个待调价药品, 取目录现有进/零售价、拟新价, 汇总当前在库数量与零售价变动带来的在库金额影响。
     */
    public Map<String, Object> preview(DrugPriceAdjustReq req) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalDiff = BigDecimal.ZERO;
        if (req != null && !CollectionUtils.isEmpty(req.getItems())) {
            String pvDomain = normDomain(req.getPriceDomain());
            Long pvWh = "WAREHOUSE".equals(pvDomain) ? req.getTargetWarehouseId()
                    : ("PHARMACY".equals(pvDomain) ? resolveStockLocWh(req.getTargetPharmacyId()) : null);
            for (DrugPriceAdjustReq.Item it : req.getItems()) {
                if (it.getDrugCatalogId() == null) {
                    continue;
                }
                HisDrugCatalog drug = drugCatalogMapper.selectById(it.getDrugCatalogId());
                if (drug == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("drugCatalogId", drug.getId());
                row.put("drugCode", drug.getDrugCode());
                row.put("drugName", displayName(drug));
                row.put("spec", drug.getSpec());
                row.put("oldPurchase", drug.getPurchasePrice());
                row.put("newPurchase", pickNew(it.getNewPurchase(), drug.getPurchasePrice()));
                row.put("oldRetail", drug.getRetailPrice());
                row.put("newRetail", pickNew(it.getNewRetail(), drug.getRetailPrice()));
                BigDecimal stockQty = scopeStockQty(drug.getId(), pvDomain, pvWh);
                row.put("impactStockQty", stockQty);
                BigDecimal diff = retailDiffAmount(drug.getRetailPrice(), it.getNewRetail(), stockQty);
                row.put("retailDiffAmount", diff);
                totalDiff = totalDiff.add(diff);
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("totalDiffAmount", totalDiff.setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    /* ================= 建草稿单 ================= */

    /** 建草稿调价单(状态0): 快照调价前后进/零售价与当前在库量, 汇总在库金额影响。生效需另调 confirm。 */
    @Transactional(rollbackFor = Exception.class)
    public HisDrugPriceAdjust create(DrugPriceAdjustReq req) {
        if (req == null || CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException(400, "调价明细不能为空");
        }
        if (req.getEffectiveDate() == null) {
            throw new BizException(400, "生效日期必须录入");
        }
        if (!StringUtils.hasText(req.getReason())) {
            throw new BizException(400, "调价原因必须录入");
        }
        // 三期分域: 解析调价域与目标库位(默认 ALL=旧全院刷价, 向后兼容)
        String domain = normDomain(req.getPriceDomain());
        Long targetWh = null;
        Long targetPh = null;
        if ("WAREHOUSE".equals(domain)) {
            if (req.getTargetWarehouseId() == null) {
                throw new BizException(400, "药库域调价必须指定目标药库");
            }
            targetWh = req.getTargetWarehouseId();
        } else if ("PHARMACY".equals(domain)) {
            if (req.getTargetPharmacyId() == null) {
                throw new BizException(400, "药房域调价必须指定目标药房");
            }
            targetPh = req.getTargetPharmacyId();
        }
        Long stockWh = "WAREHOUSE".equals(domain) ? targetWh : ("PHARMACY".equals(domain) ? resolveStockLocWh(targetPh) : null);
        HisDrugPriceAdjust main = new HisDrugPriceAdjust();
        main.setOrgId(req.getOrgId());
        main.setAdjustNo(generateNo());
        main.setScope(StringUtils.hasText(req.getScope()) ? req.getScope() : "DRUG");
        main.setPriceDomain(domain);
        main.setTargetWarehouseId(targetWh);
        main.setTargetPharmacyId(targetPh);
        main.setAutoEffect(req.getAutoEffect() != null && req.getAutoEffect() == 1 ? 1 : 0);
        main.setEffectiveDate(req.getEffectiveDate());
        main.setStatus(0);
        main.setReason(req.getReason());
        main.setOperator(currentUserName());
        main.setRemark(req.getRemark());
        main.setTotalDiffAmount(BigDecimal.ZERO);
        adjustMapper.insert(main);

        BigDecimal totalDiff = BigDecimal.ZERO;
        int validItems = 0;
        for (DrugPriceAdjustReq.Item it : req.getItems()) {
            if (it.getDrugCatalogId() == null) {
                throw new BizException(400, "调价明细缺少药品目录ID");
            }
            if (it.getNewPurchase() == null && it.getNewRetail() == null) {
                continue; // 两价都未填 = 无调价, 跳过
            }
            HisDrugCatalog drug = drugCatalogMapper.selectById(it.getDrugCatalogId());
            if (drug == null) {
                throw new BizException(400, "药品目录不存在: id=" + it.getDrugCatalogId());
            }
            validatePrice(it.getNewPurchase(), "进价");
            validatePrice(it.getNewRetail(), "零售价");
            HisDrugPriceAdjustItem item = new HisDrugPriceAdjustItem();
            item.setPriceAdjustId(main.getId());
            item.setDrugCatalogId(drug.getId());
            item.setDrugCode(drug.getDrugCode());
            item.setDrugName(displayName(drug));
            item.setSpec(drug.getSpec());
            item.setOldPurchase(drug.getPurchasePrice());
            item.setNewPurchase(pickNew(it.getNewPurchase(), drug.getPurchasePrice()));
            item.setOldRetail(drug.getRetailPrice());
            item.setNewRetail(pickNew(it.getNewRetail(), drug.getRetailPrice()));
            BigDecimal stockQty = scopeStockQty(drug.getId(), domain, stockWh);
            item.setImpactStockQty(stockQty);
            totalDiff = totalDiff.add(retailDiffAmount(drug.getRetailPrice(), it.getNewRetail(), stockQty));
            itemMapper.insert(item);
            validItems++;
        }
        if (validItems == 0) {
            throw new BizException(400, "无有效调价明细(每条至少填进价或零售价其一)");
        }
        main.setTotalDiffAmount(totalDiff.setScale(2, RoundingMode.HALF_UP));
        adjustMapper.updateById(main);
        log.info("创建调价单: id={}, adjustNo={}, items={}, totalDiff={}, effectiveDate={}",
                main.getId(), main.getAdjustNo(), validItems, main.getTotalDiffAmount(), main.getEffectiveDate());
        return adjustMapper.selectById(main.getId());
    }

    /* ================= 生效 ================= */

    /**
     * 生效调价单(三期分域): 原子认领草稿(0→3 防并发/调度重复) → 按调价域应用价格 → 置已生效(1)+生效时间。
     * CATALOG 只改目录基准价; WAREHOUSE/PHARMACY 只改目标库位内该药批次零售价(不动目录); ALL 目录+全院批次(旧行为)。历史处方/收费快照价不回改。
     * 手动 confirm 与调度器共用此入口(调度器需先置 TenantContext); 整个方法单事务, 失败则认领与改价一并回滚(状态回 0 待下次重试)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisDrugPriceAdjust confirm(Long id) {
        HisDrugPriceAdjust main = adjustMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "调价单不存在");
        }
        if (main.getStatus() == null || main.getStatus() != 0) {
            throw new BizException("仅草稿调价单可生效: " + main.getAdjustNo());
        }
        int claimed = jdbcTemplate.update(
                "UPDATE his_drug_price_adjust SET status = 3 WHERE id = ? AND tenant_id = ? AND status = 0",
                id, tenantId());
        if (claimed != 1) {
            throw new BizException("调价单状态已变更(疑似并发生效), 请刷新后重试: " + main.getAdjustNo());
        }
        applyPrices(main);
        jdbcTemplate.update(
                "UPDATE his_drug_price_adjust SET status = 1, effect_time = NOW() WHERE id = ? AND tenant_id = ? AND status = 3",
                id, tenantId());
        log.info("调价单生效完成: adjustNo={}, domain={}", main.getAdjustNo(), normDomain(main.getPriceDomain()));
        return adjustMapper.selectById(id);
    }

    /** 按调价域应用价格并逐变动字段写 his_price_adjust 留痕。 */
    private void applyPrices(HisDrugPriceAdjust main) {
        List<HisDrugPriceAdjustItem> items = listItems(main.getId());
        if (items.isEmpty()) {
            throw new BizException("调价单无明细, 无法生效");
        }
        String domain = normDomain(main.getPriceDomain());
        boolean catalogScope = "CATALOG".equals(domain) || "ALL".equals(domain);
        boolean batchScope = !"CATALOG".equals(domain);
        Long stockWh = domainBatchLocationWh(main, domain);
        for (HisDrugPriceAdjustItem it : items) {
            HisDrugCatalog drug = drugCatalogMapper.selectById(it.getDrugCatalogId());
            if (drug == null) {
                throw new BizException(400, "药品目录不存在, 无法生效: id=" + it.getDrugCatalogId());
            }
            boolean purchaseChanged = changed(it.getOldPurchase(), it.getNewPurchase());
            boolean retailChanged = changed(it.getOldRetail(), it.getNewRetail());
            if (catalogScope) {
                if (purchaseChanged) {
                    drug.setPurchasePrice(it.getNewPurchase());
                }
                if (retailChanged) {
                    drug.setRetailPrice(it.getNewRetail());
                }
                if (purchaseChanged || retailChanged) {
                    drugCatalogMapper.updateById(drug);
                }
            }
            if (retailChanged && batchScope) {
                if ("ALL".equals(domain)) {
                    jdbcTemplate.update("UPDATE his_drug_stock SET retail_price = ?, update_time = NOW()"
                                    + " WHERE tenant_id = ? AND drug_catalog_id = ? AND deleted = 0",
                            it.getNewRetail(), tenantId(), it.getDrugCatalogId());
                } else {
                    if (stockWh == null) {
                        throw new BizException("目标药库/药房无有效库存位, 无法按域调价: " + main.getAdjustNo());
                    }
                    jdbcTemplate.update("UPDATE his_drug_stock SET retail_price = ?, update_time = NOW()"
                                    + " WHERE tenant_id = ? AND drug_catalog_id = ? AND warehouse_id = ? AND deleted = 0",
                            it.getNewRetail(), tenantId(), it.getDrugCatalogId(), stockWh);
                }
            }
            if (purchaseChanged && catalogScope) {
                writeLog(drug, "purchase_price", "进货价(最小单位)", it.getOldPurchase(), it.getNewPurchase(), main);
            }
            if (retailChanged) {
                writeLog(drug, "retail_price", "零售价(最小单位)", it.getOldRetail(), it.getNewRetail(), main);
            }
        }
    }

    /** 调价域归一: 空/未知一律回落 ALL(向后兼容旧全院刷价)。 */
    private static String normDomain(String d) {
        if (!StringUtils.hasText(d)) {
            return "ALL";
        }
        String u = d.trim().toUpperCase();
        if ("CATALOG".equals(u) || "WAREHOUSE".equals(u) || "PHARMACY".equals(u) || "ALL".equals(u)) {
            return u;
        }
        return "ALL";
    }

    /** 批次改价目标库位: WAREHOUSE 取药库ID, PHARMACY 解析药房库存位, 其余(ALL/CATALOG) null=不限库位。 */
    private Long domainBatchLocationWh(HisDrugPriceAdjust main, String domain) {
        if ("WAREHOUSE".equals(domain)) {
            return main.getTargetWarehouseId();
        }
        if ("PHARMACY".equals(domain)) {
            return resolveStockLocWh(main.getTargetPharmacyId());
        }
        return null;
    }

    /** 药房库存位(his_pharmacy_def.stock_location_id 充当 his_drug_stock.warehouse_id); 无则回落其 warehouse_id。 */
    private Long resolveStockLocWh(Long pharmacyId) {
        if (pharmacyId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT stock_location_id, warehouse_id FROM his_pharmacy_def WHERE id = ? AND tenant_id = ? AND deleted = 0",
                pharmacyId, tenantId());
        if (rows.isEmpty()) {
            return null;
        }
        Object loc = rows.get(0).get("stock_location_id");
        if (loc != null) {
            return ((Number) loc).longValue();
        }
        Object wh = rows.get(0).get("warehouse_id");
        return wh == null ? null : ((Number) wh).longValue();
    }

    /** 分域在库量: CATALOG 不动批次=0; ALL 全院; WAREHOUSE/PHARMACY 仅目标库位内。 */
    private BigDecimal scopeStockQty(Long drugCatalogId, String domain, Long stockWh) {
        if ("CATALOG".equals(domain)) {
            return BigDecimal.ZERO;
        }
        if ("ALL".equals(domain)) {
            return stockQty(drugCatalogId);
        }
        if (stockWh == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal q = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM his_drug_stock"
                        + " WHERE tenant_id = ? AND drug_catalog_id = ? AND warehouse_id = ? AND status = 1 AND deleted = 0",
                BigDecimal.class, tenantId(), drugCatalogId, stockWh);
        return q == null ? BigDecimal.ZERO : q;
    }

    /** 作废草稿调价单(仅草稿可作废; 生效后不可撤) */
    @Transactional(rollbackFor = Exception.class)
    public HisDrugPriceAdjust voidOrder(Long id) {
        HisDrugPriceAdjust main = adjustMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "调价单不存在");
        }
        if (main.getStatus() == null || main.getStatus() != 0) {
            throw new BizException("仅草稿调价单可作废: " + main.getAdjustNo());
        }
        main.setStatus(2);
        adjustMapper.updateById(main);
        log.info("作废调价单: id={}, adjustNo={}", id, main.getAdjustNo());
        return main;
    }

    /* ================= 查询 ================= */

    public IPage<HisDrugPriceAdjust> page(Integer status, String keyword, long page, long size) {
        LambdaQueryWrapper<HisDrugPriceAdjust> w = Wrappers.<HisDrugPriceAdjust>lambdaQuery()
                .eq(status != null, HisDrugPriceAdjust::getStatus, status)
                .like(StringUtils.hasText(keyword), HisDrugPriceAdjust::getAdjustNo, keyword)
                .orderByDesc(HisDrugPriceAdjust::getId);
        return adjustMapper.selectPage(new Page<>(page, size), w);
    }

    public Map<String, Object> detail(Long id) {
        HisDrugPriceAdjust main = adjustMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "调价单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("items", listItems(id));
        return out;
    }

    /* ================= 内部实现 ================= */

    private List<HisDrugPriceAdjustItem> listItems(Long adjustId) {
        return itemMapper.selectList(Wrappers.<HisDrugPriceAdjustItem>lambdaQuery()
                .eq(HisDrugPriceAdjustItem::getPriceAdjustId, adjustId)
                .orderByAsc(HisDrugPriceAdjustItem::getId));
    }

    /** 全租户该药品当前在库数量合计(所有库位/机构/批次) */
    private BigDecimal stockQty(Long drugCatalogId) {
        BigDecimal q = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM his_drug_stock"
                        + " WHERE tenant_id = ? AND drug_catalog_id = ? AND status = 1 AND deleted = 0",
                BigDecimal.class, tenantId(), drugCatalogId);
        return q == null ? BigDecimal.ZERO : q;
    }

    private void writeLog(HisDrugCatalog drug, String priceField, String priceLabel,
                          BigDecimal oldPrice, BigDecimal newPrice, HisDrugPriceAdjust main) {
        HisPriceAdjust logRow = new HisPriceAdjust();
        logRow.setCatalogType("drug");
        logRow.setCatalogId(drug.getId());
        logRow.setCatalogName(displayName(drug));
        logRow.setPriceField(priceField);
        logRow.setPriceLabel(priceLabel);
        logRow.setOldPrice(oldPrice);
        logRow.setNewPrice(newPrice);
        logRow.setAdjustDocNo(main.getAdjustNo());
        logRow.setEffDate(main.getEffectiveDate());
        logRow.setReason(main.getReason());
        logRow.setOperatorName(main.getOperator());
        priceAdjustLogMapper.insert(logRow);
    }

    private void validatePrice(BigDecimal v, String label) {
        if (v != null && v.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(400, label + "不能为负数");
        }
    }

    private static BigDecimal pickNew(BigDecimal newVal, BigDecimal oldVal) {
        return newVal != null ? newVal : oldVal;
    }

    /** 是否实际变动(新价非空且与原价不等) */
    private static boolean changed(BigDecimal oldVal, BigDecimal newVal) {
        if (newVal == null) {
            return false;
        }
        if (oldVal == null) {
            return true;
        }
        return oldVal.compareTo(newVal) != 0;
    }

    /** 在库金额影响 = (新零售价-原零售价) × 在库量(仅零售价变动时非零) */
    private static BigDecimal retailDiffAmount(BigDecimal oldRetail, BigDecimal newRetail, BigDecimal stockQty) {
        if (newRetail == null || oldRetail == null || oldRetail.compareTo(newRetail) == 0) {
            return BigDecimal.ZERO;
        }
        return newRetail.subtract(oldRetail).multiply(stockQty).setScale(2, RoundingMode.HALF_UP);
    }

    private static String displayName(HisDrugCatalog d) {
        return StringUtils.hasText(d.getGenericName()) ? d.getGenericName() : d.getDrugCode();
    }

    /** 单号生成: TJ+yyyyMMdd+4位; synchronized 唯一, 跨日重置从DB回读当日最大序号兜底 */
    private synchronized String generateNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = "TJ" + today + String.format("%04d", seqNo);
        while (noExists(no)) {
            seqNo++;
            no = "TJ" + today + String.format("%04d", seqNo);
        }
        return no;
    }

    private int maxSeqFromDb(String today) {
        HisDrugPriceAdjust one = adjustMapper.selectOne(Wrappers.<HisDrugPriceAdjust>lambdaQuery()
                .likeRight(HisDrugPriceAdjust::getAdjustNo, "TJ" + today)
                .orderByDesc(HisDrugPriceAdjust::getAdjustNo)
                .last("LIMIT 1"));
        if (one == null || one.getAdjustNo() == null || one.getAdjustNo().length() < 14) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getAdjustNo().substring(10));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean noExists(String no) {
        return adjustMapper.selectCount(Wrappers.<HisDrugPriceAdjust>lambdaQuery()
                .eq(HisDrugPriceAdjust::getAdjustNo, no)) > 0;
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
