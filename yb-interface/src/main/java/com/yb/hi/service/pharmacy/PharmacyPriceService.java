package com.yb.hi.service.pharmacy;

import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.entity.community.HisDrugCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药房维度定价服务(批次驱动): 生效价 = 发药药房 FIFO 吃批次加权单价, 在库不足回落目录零售价。
 * 1) effectivePrice/Batch/chargePriceBatch: 开方计费与服务端重算价唯一口径; pharmacyId 为空一律目录价(兼容不绑药房存量);
 * 2) stockSummary/reservedQtyBatch: 按药房库存位聚合各药品可用量/已开未发预占量。
 * 注: 旧覆盖价层 his_pharmacy_drug_price 及其维护页(药房定价)已于 2026-10 下线(不符批次价业务实际), 不再参与任何定价。
 * JdbcTemplate 原生 SQL 不走租户插件, 显式 tenant_id 过滤。
 */
@Slf4j
@Service
public class PharmacyPriceService {

    private final HisDrugCatalogMapper drugCatalogMapper;
    private final PharmacyDefService pharmacyDefService;
    private final JdbcTemplate jdbcTemplate;

    public PharmacyPriceService(HisDrugCatalogMapper drugCatalogMapper,
                                PharmacyDefService pharmacyDefService, JdbcTemplate jdbcTemplate) {
        this.drugCatalogMapper = drugCatalogMapper;
        this.pharmacyDefService = pharmacyDefService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 划价/预览生效价(批次驱动) ================= */

    /** 单位价中间舍入精度(4 位), 明细金额再按 2 位单点舍入 */
    private static final int PRICE_SCALE = 4;

    /**
     * 单药生效价(批次驱动预览): 药房 FIFO 头一批零售价优先, 回落目录零售价; 无价返回 null。
     * 注: 旧覆盖价层 his_pharmacy_drug_price 及其维护页(药房定价)已于 2026-10 下线, 不再参与任何定价。
     */
    public BigDecimal effectivePrice(Long pharmacyId, Long drugCatalogId) {
        if (drugCatalogId == null) {
            return null;
        }
        Map<Long, BigDecimal> m = chargePriceBatch(pharmacyId,
                Collections.singletonMap(drugCatalogId, BigDecimal.ONE));
        return m.get(drugCatalogId);
    }

    /** 批量生效价(浏览/展示, 无数量维度): 每药按 FIFO 头一批零售价, 无库存回落目录价 */
    public Map<Long, BigDecimal> effectivePriceBatch(Long pharmacyId, Collection<Long> drugIds) {
        if (drugIds == null || drugIds.isEmpty()) {
            return new HashMap<>();
        }
        Map<Long, BigDecimal> qty = new LinkedHashMap<>();
        for (Long id : new java.util.LinkedHashSet<>(drugIds)) {
            qty.put(id, BigDecimal.ONE);
        }
        return chargePriceBatch(pharmacyId, qty);
    }

    /**
     * 划价计费价(批次驱动单源): 逐药按发药药房 FIFO 吃批次得"精确加权单价"(Σ批次价×消耗量/数量),
     * 在库不足或药房无库存位时回落目录零售价。数量口径=最小单位(min_unit), 与处方明细 quantity 一致。
     * FIFO 排序与价表达式必须与 {@code PharmacyService.estimateFifo} 严格一致, 二者同步维护。
     */
    public Map<Long, BigDecimal> chargePriceBatch(Long pharmacyId, Map<Long, BigDecimal> qtyByDrug) {
        Map<Long, BigDecimal> out = new HashMap<>();
        if (qtyByDrug == null || qtyByDrug.isEmpty()) {
            return out;
        }
        Long locId = pharmacyId == null ? null : stockLocationOf(pharmacyId);
        Map<Long, BigDecimal> catalog = catalogPriceBatch(qtyByDrug.keySet());
        for (Map.Entry<Long, BigDecimal> e : qtyByDrug.entrySet()) {
            Long drugId = e.getKey();
            BigDecimal price = locId == null ? null : fifoUnitPrice(locId, drugId, e.getValue());
            if (price == null) {
                price = catalog.get(drugId);
            }
            if (price != null) {
                out.put(drugId, price);
            }
        }
        return out;
    }

    /** 目录零售价批查: drugId -> retail_price(不含无价药) */
    private Map<Long, BigDecimal> catalogPriceBatch(Collection<Long> drugIds) {
        Map<Long, BigDecimal> out = new HashMap<>();
        if (drugIds == null || drugIds.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(drugIds));
        for (HisDrugCatalog c : drugCatalogMapper.selectBatchIds(ids)) {
            if (c.getRetailPrice() != null) {
                out.put(c.getId(), c.getRetailPrice());
            }
        }
        return out;
    }

    /**
     * 按 FIFO(效期升序, 同效期按入库序 id)吃批次消耗 need 个最小单位, 返回加权单价 = Σ(批次价×消耗量)/need。
     * 无库存位、need<=0、或在库不足(有缺口)一律返回 null 交调用方回落目录价。
     * 价表达式 IFNULL(retail_price, IFNULL(cost_price,0)) 与排序须与 PharmacyService.estimateFifo 一致。
     */
    private BigDecimal fifoUnitPrice(Long locId, Long drugId, BigDecimal need) {
        if (locId == null || drugId == null || need == null || need.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        List<Map<String, Object>> batches = jdbcTemplate.queryForList(
                "SELECT qty, IFNULL(retail_price, IFNULL(cost_price, 0)) price FROM his_drug_stock"
                        + " WHERE tenant_id = ? AND warehouse_id = ? AND drug_catalog_id = ?"
                        + " AND status = 1 AND deleted = 0 AND qty > 0"
                        + " ORDER BY COALESCE(exp_date, '9999-12-31'), id",
                tenantId(), locId, drugId);
        BigDecimal remaining = need;
        BigDecimal amount = BigDecimal.ZERO;
        for (Map<String, Object> b : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal bq = toBd(b.get("qty"));
            if (bq == null || bq.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal take = bq.min(remaining);
            BigDecimal bp = toBd(b.get("price"));
            amount = amount.add(take.multiply(bp == null ? BigDecimal.ZERO : bp));
            remaining = remaining.subtract(take);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            return null;
        }
        return amount.divide(need, PRICE_SCALE, BigDecimal.ROUND_HALF_UP);
    }

    /* ================= 药房库存 ================= */

    /** 药房库存位ID(两级库存记账维度): stock_location_id 优先, 回退旧 warehouse_id, 均空返回 null */
    public Long stockLocationOf(Long pharmacyId) {
        HisPharmacyDef def = pharmacyDefService.find(pharmacyId);
        if (def == null) {
            return null;
        }
        return def.getStockLocationId() != null ? def.getStockLocationId() : def.getWarehouseId();
    }

    /** 按药房库存位聚合各药品在库总量(正常状态批次): drugId->qty; 药房无库存位返回空Map */
    public Map<Long, BigDecimal> stockSummary(Long pharmacyId, Collection<Long> drugIds) {
        Map<Long, BigDecimal> out = new HashMap<>();
        Long locId = pharmacyId == null ? null : stockLocationOf(pharmacyId);
        if (locId == null || drugIds == null || drugIds.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(drugIds));
        StringBuilder sql = new StringBuilder(
                "SELECT drug_catalog_id, SUM(qty) qty FROM his_drug_stock"
                        + " WHERE tenant_id = ? AND warehouse_id = ? AND status = 1 AND deleted = 0 AND drug_catalog_id IN (");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(locId);
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            args.add(ids.get(i));
        }
        sql.append(") GROUP BY drug_catalog_id");
        for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            out.put(toLong(r.get("drug_catalog_id")), toBd(r.get("qty")));
        }
        return out;
    }

    /**
     * P3 库存软预占: 某药房已开未发(status=1)处方对各药的占用量合计 drugId->SUM(quantity)。
     * 已发药(2)/已退药(3)/已作废(-1) 不计; 药房归属由 pharmacy_id 天然收敛到机构。发药才实扣, 作废/退药自然退出占用。
     * JdbcTemplate 原生 SQL 不走租户插件, 显式 tenant_id + deleted=0。
     */
    public Map<Long, BigDecimal> reservedQtyBatch(Long pharmacyId, Collection<Long> drugIds) {
        Map<Long, BigDecimal> out = new HashMap<>();
        if (pharmacyId == null || drugIds == null || drugIds.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(drugIds));
        StringBuilder sql = new StringBuilder(
                "SELECT i.drug_id AS drug_id, SUM(i.quantity) AS qty FROM his_prescription_item i"
                        + " JOIN his_prescription p ON i.prescription_id = p.id"
                        + " WHERE p.tenant_id = ? AND p.pharmacy_id = ? AND p.status = 1 AND p.deleted = 0 AND i.deleted = 0"
                        + " AND i.drug_id IN (");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(pharmacyId);
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
            args.add(ids.get(i));
        }
        sql.append(") GROUP BY i.drug_id");
        for (Map<String, Object> r : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            out.put(toLong(r.get("drug_id")), toBd(r.get("qty")));
        }
        return out;
    }

    /* ================= 内部 ================= */

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static Long toLong(Object v) {
        return v == null ? null : (v instanceof Number ? ((Number) v).longValue() : Long.valueOf(v.toString()));
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
}
