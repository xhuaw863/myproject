package com.yb.hi.service.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.pharmacy.HisPharmacyDrugPrice;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.pharmacy.HisPharmacyDrugPriceMapper;
import com.yb.hi.entity.community.HisDrugCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药房维度定价服务(三期): 生效价=药房覆盖价 his_pharmacy_drug_price, 未覆盖回落目录零售价。
 * 1) effectivePrice/Batch: 开方计费与服务端重算价唯一口径; pharmacyId 为空一律目录价(兼容不绑药房存量);
 * 2) stockSummary: 按药房库存位(stock_location_id, 空回退旧 warehouse_id)聚合各药品可用量;
 * 3) 覆盖价维护 save 幂等 upsert; clear 物理删除(唯一键下软删残行会撞键, 且"回落目录价"本应是无状态操作)。
 * JdbcTemplate 原生 SQL 不走租户插件, 显式 tenant_id 过滤。
 */
@Slf4j
@Service
public class PharmacyPriceService {

    private final HisPharmacyDrugPriceMapper priceMapper;
    private final HisDrugCatalogMapper drugCatalogMapper;
    private final PharmacyDefService pharmacyDefService;
    private final JdbcTemplate jdbcTemplate;

    public PharmacyPriceService(HisPharmacyDrugPriceMapper priceMapper, HisDrugCatalogMapper drugCatalogMapper,
                                PharmacyDefService pharmacyDefService, JdbcTemplate jdbcTemplate) {
        this.priceMapper = priceMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.pharmacyDefService = pharmacyDefService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 生效价 ================= */

    /** 单药生效价: 药房覆盖价优先, 回落目录零售价; 目录也无价返回 null(调用方决定兜底) */
    public BigDecimal effectivePrice(Long pharmacyId, Long drugCatalogId) {
        if (drugCatalogId == null) {
            return null;
        }
        BigDecimal override = pharmacyId == null ? null : overridePrice(pharmacyId, drugCatalogId);
        if (override != null) {
            return override;
        }
        HisDrugCatalog drug = drugCatalogMapper.selectById(drugCatalogId);
        return drug == null ? null : drug.getRetailPrice();
    }

    /** 批量生效价: 一次覆盖价批查 + 一次目录批查, 避免 N+1; 返回 drugId->价(无价条目不含) */
    public Map<Long, BigDecimal> effectivePriceBatch(Long pharmacyId, Collection<Long> drugIds) {
        Map<Long, BigDecimal> out = new HashMap<>();
        if (drugIds == null || drugIds.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>(new java.util.LinkedHashSet<>(drugIds));
        Map<Long, BigDecimal> overrides = pharmacyId == null
                ? Collections.emptyMap() : overrideBatch(pharmacyId, ids);
        List<HisDrugCatalog> catalogs = drugCatalogMapper.selectBatchIds(ids);
        for (HisDrugCatalog c : catalogs) {
            BigDecimal p = overrides.get(c.getId());
            if (p == null) {
                p = c.getRetailPrice();
            }
            if (p != null) {
                out.put(c.getId(), p);
            }
        }
        return out;
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

    /* ================= 覆盖价维护 ================= */

    /** 定价页(按药房): 目录启用药品分页 + 覆盖价/生效价/该房库存; keyword 匹配名称/编码/简码 */
    public Map<String, Object> pricePage(Long orgId, Long pharmacyId, String keyword, long page, long size) {
        HisPharmacyDef pharmacy = pharmacyDefService.requireEnabled(pharmacyId, orgId);
        long p = page < 1 ? 1 : page;
        long s = size < 1 ? 20 : Math.min(size, 200);
        long tenant = tenantId();
        StringBuilder where = new StringBuilder(" WHERE c.tenant_id = ? AND c.status = 1 AND c.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (c.generic_name LIKE ? OR c.trade_name LIKE ? OR c.drug_code LIKE ? OR c.py_code LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_drug_catalog c" + where, Long.class, args.toArray());
        Long locId = pharmacy.getStockLocationId() != null ? pharmacy.getStockLocationId() : pharmacy.getWarehouseId();
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        // 覆盖价/库存位聚合均为标量子查询: 定价页数据量小, 换取分页语义简单可靠
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.id, c.drug_code, c.generic_name, c.trade_name, c.spec, c.min_unit, c.pack_ratio,"
                        + " c.retail_price catalog_price,"
                        + " (SELECT p.retail_price FROM his_pharmacy_drug_price p"
                        + "  WHERE p.tenant_id = ? AND p.pharmacy_id = ? AND p.drug_catalog_id = c.id AND p.deleted = 0) override_price,"
                        + " (SELECT IFNULL(SUM(k.qty), 0) FROM his_drug_stock k"
                        + "  WHERE k.tenant_id = ? AND k.warehouse_id = ? AND k.drug_catalog_id = c.id"
                        + "   AND k.status = 1 AND k.deleted = 0) stock_qty"
                        + " FROM his_drug_catalog c" + where
                        + " ORDER BY c.id DESC LIMIT ?, ?",
                buildArgs(tenant, pharmacyId, tenant, locId, dataArgs));
        for (Map<String, Object> r : rows) {
            BigDecimal ov = toBd(r.get("override_price"));
            r.put("eff_price", ov != null ? ov : toBd(r.get("catalog_price")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("total", total == null ? 0L : total);
        out.put("page", p);
        out.put("size", s);
        out.put("pharmacyName", pharmacy.getName());
        return out;
    }

    /** 保存覆盖价(幂等 upsert): 校验药房归属 + 药品存在 + 价非负 */
    @Transactional(rollbackFor = Exception.class)
    public HisPharmacyDrugPrice save(Long orgId, Long pharmacyId, Long drugCatalogId, BigDecimal retailPrice) {
        pharmacyDefService.requireEnabled(pharmacyId, orgId);
        if (drugCatalogId == null || retailPrice == null || retailPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(400, "药品与零售价(非负)不能为空");
        }
        if (drugCatalogMapper.selectById(drugCatalogId) == null) {
            throw new BizException(400, "药品目录不存在: " + drugCatalogId);
        }
        HisPharmacyDrugPrice exist = priceMapper.selectOne(Wrappers.<HisPharmacyDrugPrice>lambdaQuery()
                .eq(HisPharmacyDrugPrice::getPharmacyId, pharmacyId)
                .eq(HisPharmacyDrugPrice::getDrugCatalogId, drugCatalogId)
                .last("LIMIT 1"));
        if (exist != null) {
            exist.setRetailPrice(retailPrice);
            priceMapper.updateById(exist);
            log.info("药房覆盖价更新: pharmacyId={}, drugId={}, price={}", pharmacyId, drugCatalogId, retailPrice);
            return exist;
        }
        HisPharmacyDrugPrice e = new HisPharmacyDrugPrice();
        e.setOrgId(orgId);
        e.setPharmacyId(pharmacyId);
        e.setDrugCatalogId(drugCatalogId);
        e.setRetailPrice(retailPrice);
        priceMapper.insert(e);
        log.info("药房覆盖价新增: pharmacyId={}, drugId={}, price={}", pharmacyId, drugCatalogId, retailPrice);
        return e;
    }

    /** 清空覆盖价(物理删除, 回落目录价): 无覆盖行时静默成功 */
    @Transactional(rollbackFor = Exception.class)
    public void clear(Long pharmacyId, Long drugCatalogId) {
        if (pharmacyId == null || drugCatalogId == null) {
            throw new BizException(400, "药房与药品不能为空");
        }
        int n = priceMapper.physicalDelete(pharmacyId, drugCatalogId);
        log.info("药房覆盖价清空(回落目录价): pharmacyId={}, drugId={}, affected={}", pharmacyId, drugCatalogId, n);
    }

    /* ================= 内部 ================= */

    private BigDecimal overridePrice(Long pharmacyId, Long drugCatalogId) {
        HisPharmacyDrugPrice p = priceMapper.selectOne(Wrappers.<HisPharmacyDrugPrice>lambdaQuery()
                .eq(HisPharmacyDrugPrice::getPharmacyId, pharmacyId)
                .eq(HisPharmacyDrugPrice::getDrugCatalogId, drugCatalogId)
                .last("LIMIT 1"));
        return p == null ? null : p.getRetailPrice();
    }

    private Map<Long, BigDecimal> overrideBatch(Long pharmacyId, List<Long> drugIds) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (HisPharmacyDrugPrice p : priceMapper.selectList(Wrappers.<HisPharmacyDrugPrice>lambdaQuery()
                .eq(HisPharmacyDrugPrice::getPharmacyId, pharmacyId)
                .in(HisPharmacyDrugPrice::getDrugCatalogId, drugIds))) {
            out.put(p.getDrugCatalogId(), p.getRetailPrice());
        }
        return out;
    }

    private static Object[] buildArgs(long tenant, Long pharmacyId, long tenant2, Long locId, List<Object> tail) {
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.add(pharmacyId);
        args.add(tenant2);
        args.add(locId);
        args.addAll(tail);
        return args.toArray();
    }

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
