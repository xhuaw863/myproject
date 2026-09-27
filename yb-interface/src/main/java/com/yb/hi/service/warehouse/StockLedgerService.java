package com.yb.hi.service.warehouse;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进销存台账(药库/药房库存位通用, 只读): 按 (机构, 库位, 药品, 日期区间) 计算 期初结存 + 本期入库 - 本期出库 = 期末结存。
 * 数据源: his_drug_stock 当前快照(期末基准) + his_stock_in/_item、his_stock_out/_item 已确认(status=1)流水(按确认时间归期)。
 * 口径: 期初/期末数量按"倒轧+累计"精确对账 —— 基线 B = 当前快照 - 全部已确认净变动; 期初 = B + 区间前净变动; 期末 = 期初 + 本期入 - 本期出。
 * 金额: 入库金额取进价口径(stock_in_item.amount), 出库金额取零售价口径(stock_out_item.amount), 期末金额按当前进价估值(仅参考, 不做强对账)。
 * 读走 scopeOrgId(牵头可跨机构); 本服务不写库。
 */
@Slf4j
@Service
public class StockLedgerService {

    private final JdbcTemplate jdbcTemplate;

    public StockLedgerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 台账主查询: 返回 {rows:[每药品一行], summary:{合计入/出数量、期初期末数量、行数、库位/区间元信息}}。
     * warehouseId 为空则汇总本机构(或牵头全部)所有库位; drugCatalogId 为空则全部药品。
     */
    public Map<String, Object> ledger(Long orgId, Long warehouseId, Long drugCatalogId,
                                      LocalDate beginDate, LocalDate endDate) {
        LocalDate begin = beginDate != null ? beginDate : LocalDate.of(1970, 1, 1);
        LocalDate end = endDate != null ? endDate : LocalDate.of(9999, 12, 31);
        long tenant = tenantId();

        Map<Long, Map<String, Object>> byDrug = new LinkedHashMap<>();

        // 1) 当前快照(期末基准) + 药品基础信息 + 现价
        StringBuilder snapSql = new StringBuilder(
                "SELECT drug_catalog_id, MAX(drug_code) drug_code, MAX(drug_name) drug_name, MAX(spec) spec,"
                        + " SUM(qty) snap_qty, MAX(cost_price) cost_price, MAX(retail_price) retail_price"
                        + " FROM his_drug_stock"
                        + " WHERE tenant_id = ? AND status = 1 AND deleted = 0");
        List<Object> snapArgs = new ArrayList<>();
        snapArgs.add(tenant);
        if (orgId != null) {
            snapSql.append(" AND org_id = ?");
            snapArgs.add(orgId);
        }
        if (warehouseId != null) {
            snapSql.append(" AND warehouse_id = ?");
            snapArgs.add(warehouseId);
        }
        if (drugCatalogId != null) {
            snapSql.append(" AND drug_catalog_id = ?");
            snapArgs.add(drugCatalogId);
        }
        snapSql.append(" GROUP BY drug_catalog_id");
        for (Map<String, Object> r : jdbcTemplate.queryForList(snapSql.toString(), snapArgs.toArray())) {
            Long did = asLong(r.get("drug_catalog_id"));
            Map<String, Object> row = newRow(did, r);
            row.put("snapQty", asDecimal(r.get("snap_qty")));
            byDrug.put(did, row);
        }

        // 2) 入库累计(全部 / 区间前 / 本期) + 本期入库金额(进价)
        String inSql = "SELECT i.drug_catalog_id,"
                + " SUM(i.qty) in_all,"
                + " SUM(CASE WHEN DATE(o.confirm_time) < ? THEN i.qty ELSE 0 END) in_before,"
                + " SUM(CASE WHEN DATE(o.confirm_time) BETWEEN ? AND ? THEN i.qty ELSE 0 END) in_period,"
                + " SUM(CASE WHEN DATE(o.confirm_time) BETWEEN ? AND ? THEN COALESCE(i.amount,0) ELSE 0 END) in_amount"
                + " FROM his_stock_in o JOIN his_stock_in_item i ON i.stock_in_id = o.id"
                + " WHERE o.tenant_id = ? AND o.status = 1 AND o.deleted = 0 AND i.deleted = 0"
                + orgWhFilter(orgId, warehouseId, drugCatalogId, "o", "i")
                + " GROUP BY i.drug_catalog_id";
        List<Object> inArgs = new ArrayList<>();
        inArgs.add(begin);
        inArgs.add(begin);
        inArgs.add(end);
        inArgs.add(begin);
        inArgs.add(end);
        inArgs.add(tenant);
        appendOrgWhDrug(inArgs, orgId, warehouseId, drugCatalogId);
        for (Map<String, Object> r : jdbcTemplate.queryForList(inSql, inArgs.toArray())) {
            Map<String, Object> row = ensureRow(byDrug, r, tenant, orgId, warehouseId, drugCatalogId);
            row.put("inAll", asDecimal(r.get("in_all")));
            row.put("inBefore", asDecimal(r.get("in_before")));
            row.put("inPeriod", asDecimal(r.get("in_period")));
            row.put("inAmount", asDecimal(r.get("in_amount")));
        }

        // 3) 出库累计(全部 / 区间前 / 本期) + 本期出库金额(零售)
        String outSql = "SELECT t.drug_catalog_id,"
                + " SUM(t.qty) out_all,"
                + " SUM(CASE WHEN DATE(o.confirm_time) < ? THEN t.qty ELSE 0 END) out_before,"
                + " SUM(CASE WHEN DATE(o.confirm_time) BETWEEN ? AND ? THEN t.qty ELSE 0 END) out_period,"
                + " SUM(CASE WHEN DATE(o.confirm_time) BETWEEN ? AND ? THEN COALESCE(t.amount,0) ELSE 0 END) out_amount"
                + " FROM his_stock_out o JOIN his_stock_out_item t ON t.stock_out_id = o.id"
                + " WHERE o.tenant_id = ? AND o.status = 1 AND o.deleted = 0 AND t.deleted = 0"
                + orgWhFilter(orgId, warehouseId, drugCatalogId, "o", "t")
                + " GROUP BY t.drug_catalog_id";
        List<Object> outArgs = new ArrayList<>();
        outArgs.add(begin);
        outArgs.add(begin);
        outArgs.add(end);
        outArgs.add(begin);
        outArgs.add(end);
        outArgs.add(tenant);
        appendOrgWhDrug(outArgs, orgId, warehouseId, drugCatalogId);
        for (Map<String, Object> r : jdbcTemplate.queryForList(outSql, outArgs.toArray())) {
            Map<String, Object> row = ensureRow(byDrug, r, tenant, orgId, warehouseId, drugCatalogId);
            row.put("outAll", asDecimal(r.get("out_all")));
            row.put("outBefore", asDecimal(r.get("out_before")));
            row.put("outPeriod", asDecimal(r.get("out_period")));
            row.put("outAmount", asDecimal(r.get("out_amount")));
        }

        // 4) 组装: B = 快照 - (入全部 - 出全部); 期初 = B + 入前 - 出前; 期末 = 期初 + 本期入 - 本期出
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal sIn = BigDecimal.ZERO, sOut = BigDecimal.ZERO, sOpen = BigDecimal.ZERO, sClose = BigDecimal.ZERO;
        BigDecimal sInAmt = BigDecimal.ZERO, sOutAmt = BigDecimal.ZERO;
        for (Map<String, Object> row : byDrug.values()) {
            BigDecimal snap = dec(row.get("snapQty"));
            BigDecimal inAll = dec(row.get("inAll"));
            BigDecimal inBefore = dec(row.get("inBefore"));
            BigDecimal inPeriod = dec(row.get("inPeriod"));
            BigDecimal outAll = dec(row.get("outAll"));
            BigDecimal outBefore = dec(row.get("outBefore"));
            BigDecimal outPeriod = dec(row.get("outPeriod"));
            BigDecimal inAmount = dec(row.get("inAmount"));
            BigDecimal outAmount = dec(row.get("outAmount"));
            BigDecimal baseline = snap.subtract(inAll).add(outAll);
            BigDecimal opening = baseline.add(inBefore).subtract(outBefore);
            BigDecimal closing = opening.add(inPeriod).subtract(outPeriod);
            BigDecimal cost = dec(row.get("costPrice"));
            row.put("openingQty", opening);
            row.put("inQty", inPeriod);
            row.put("outQty", outPeriod);
            row.put("closingQty", closing);
            row.put("closingCostAmount", closing.multiply(cost).setScale(2, RoundingMode.HALF_UP));
            // 清理中间聚合键(不返回给前端)
            row.remove("snapQty");
            row.remove("inAll");
            row.remove("inBefore");
            row.remove("inPeriod");
            row.remove("inAmount");
            row.remove("outAll");
            row.remove("outBefore");
            row.remove("outPeriod");
            row.remove("outAmount");
            // 重新放回金额(前端展示用)
            Map<String, Object> withAmt = new LinkedHashMap<>(row);
            withAmt.put("inAmount", inAmount);
            withAmt.put("outAmount", outAmount);
            rows.add(withAmt);
            sIn = sIn.add(inPeriod);
            sOut = sOut.add(outPeriod);
            sOpen = sOpen.add(opening);
            sClose = sClose.add(closing);
            sInAmt = sInAmt.add(inAmount);
            sOutAmt = sOutAmt.add(outAmount);
        }
        rows.sort((a, b) -> {
            String an = str(a.get("drugCode"));
            String bn = str(b.get("drugCode"));
            return an.compareTo(bn);
        });

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rowCount", rows.size());
        summary.put("totalOpeningQty", sOpen);
        summary.put("totalInQty", sIn);
        summary.put("totalOutQty", sOut);
        summary.put("totalClosingQty", sClose);
        summary.put("totalInAmount", sInAmt.setScale(2, RoundingMode.HALF_UP));
        summary.put("totalOutAmount", sOutAmt.setScale(2, RoundingMode.HALF_UP));
        summary.put("beginDate", beginDate);
        summary.put("endDate", endDate);
        summary.put("warehouseId", warehouseId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("summary", summary);
        return out;
    }

    /** 导出用: 与 ledger 同口径, 产出 EasyExcel head/rows (遵循既有导出规范) */
    public Map<String, Object> exportLedger(Long orgId, Long warehouseId, Long drugCatalogId,
                                            LocalDate beginDate, LocalDate endDate) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ledger(orgId, warehouseId, drugCatalogId, beginDate, endDate).get("rows");
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"药品编码", "药品名称", "规格", "期初数量", "入库数量", "入库金额(进价)",
                "出库数量", "出库金额(零售)", "期末数量", "期末金额(进价)"}) {
            List<String> col = new ArrayList<>();
            col.add(h);
            head.add(col);
        }
        List<List<Object>> data = new ArrayList<>();
        int idx = 1;
        for (Map<String, Object> r : rows) {
            data.add(java.util.Arrays.asList(
                    idx++, str(r.get("drugCode")), str(r.get("drugName")), str(r.get("spec")),
                    r.get("openingQty"), r.get("inQty"), r.get("inAmount"),
                    r.get("outQty"), r.get("outAmount"), r.get("closingQty"), r.get("closingCostAmount")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", data);
        return out;
    }

    /* ================= 内部实现 ================= */

    /** 流水 JOIN 主表的机构/库位/药品过滤片段(别名: 主表 m, 明细 d) */
    private String orgWhFilter(Long orgId, Long warehouseId, Long drugCatalogId, String m, String d) {
        StringBuilder sb = new StringBuilder();
        if (orgId != null) {
            sb.append(" AND ").append(m).append(".org_id = ?");
        }
        if (warehouseId != null) {
            sb.append(" AND ").append(m).append(".warehouse_id = ?");
        }
        if (drugCatalogId != null) {
            sb.append(" AND ").append(d).append(".drug_catalog_id = ?");
        }
        return sb.toString();
    }

    private void appendOrgWhDrug(List<Object> args, Long orgId, Long warehouseId, Long drugCatalogId) {
        if (orgId != null) {
            args.add(orgId);
        }
        if (warehouseId != null) {
            args.add(warehouseId);
        }
        if (drugCatalogId != null) {
            args.add(drugCatalogId);
        }
    }

    /** 快照未含该药品(零库存但有流水)时补一行基础信息 */
    private Map<String, Object> ensureRow(Map<Long, Map<String, Object>> byDrug, Map<String, Object> flowRow,
                                          long tenant, Long orgId, Long warehouseId, Long drugCatalogId) {
        Long did = asLong(flowRow.get("drug_catalog_id"));
        Map<String, Object> row = byDrug.get(did);
        if (row == null) {
            row = newRow(did, flowRow);
            row.put("snapQty", BigDecimal.ZERO);
            byDrug.put(did, row);
        }
        return row;
    }

    private Map<String, Object> newRow(Long did, Map<String, Object> src) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("drugCatalogId", did);
        row.put("drugCode", str(src.get("drug_code")));
        row.put("drugName", str(src.get("drug_name")));
        row.put("spec", str(src.get("spec")));
        row.put("costPrice", dec(src.get("cost_price")));
        row.put("retailPrice", dec(src.get("retail_price")));
        return row;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static Long asLong(Object v) {
        if (v == null) {
            throw new BizException(400, "台账数据异常: 药品目录ID为空");
        }
        return ((Number) v).longValue();
    }

    private static BigDecimal asDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal(v.toString());
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }
}
