package com.yb.hi.service.pharmacy;

import com.yb.hi.framework.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药房统计查询服务(纯只读, 2026-11 药房整合增强): 四维聚合——用量与消耗 / 医保合规 / 处方与退药质量,
 * 发药工作量复用 ReportService.pharmacyDispenseStats(见 /api/his/report)。全部走 JdbcTemplate 手写 SQL,
 * 显式携带 tenant_id(取 {@link TenantContext}) 与 deleted=0(租户插件不作用于 JdbcTemplate), 机构隔离由
 * 控制器 {@code guard.scopeOrgId} 传入 orgId(非牵头锁本院, 牵头可查医共体: orgId=null 为全部)。
 *
 * <p>数据源口径(不新增落库字段, 均现有列):</p>
 * <ul>
 *   <li>门诊药品级消耗: his_dispense d(status=2 已发药, dispense_time 左闭右开, 与处方 1:1) JOIN
 *       his_prescription_item pi(pi.prescription_id=d.prescription_id, SUM(pi.amount)=d.total_amount 已实测守恒)
 *       LEFT JOIN his_drug_catalog c(pi.drug_id=c.id) 取通用名/大类/厂家/医保属性;</li>
 *   <li>住院药品级消耗: his_inp_dispense i(行级发药, status=1 发放/2 部分退/3 全退) LEFT JOIN his_drug_catalog c
 *       (i.drug_catalog_id=c.id); 净量=actual_qty-return_qty, 金额=净量*c.retail_price(目录零售价, 无行级金额列);</li>
 *   <li>医保属性(基药 essential_flag / 抗菌分级 abx_grade / 甲乙丙 chrgitm_lv / 自付比例 selfpay_prop /
 *       医保对账 yb_drug_code / 零差率 zero_margin)取自 his_drug_catalog, 按消耗金额加权;</li>
 *   <li>处方审核质量: his_prescription(audit_status 0无需/1待审/2通过/3驳回, reject_reason) LEFT JOIN his_dept
 *       取 org_id 做机构隔离; 退药质量: his_drug_return(status=1 已审批, reason/return_amount);</li>
 *   <li>药占比/次均费用分母: his_charge_bill(bill_type=1 status=1 门诊收费) + his_inp_charge_detail(status=1 住院费用)。</li>
 * </ul>
 * 集采占比因缺乏稳定的"出库批次→供应商 jt_flag"链路, 本期按用户裁定降级不纳入(见交付说明)。
 */
@Service
public class PharmacyStatService {

    private final JdbcTemplate jdbc;

    public PharmacyStatService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ============================ 公共: 药品级消耗底表(门诊 UNION 住院) ============================ */

    /** 药品级消耗底表派生列: gname 通用名 / mclass 大类 / mfr 厂家 / dept 科室 / qty 净数量 / amount 净金额
     *  / ef 基药 / yb 医保目录已对账 / zm 零差率 / sp 自付比例 / abx 抗菌分级码 / an 抗菌分级名
     *  / lv 医保甲乙丙码 / ln 甲乙丙名。所有维度/合规聚合均 SELECT ... FROM (base) b GROUP BY ... 复用。 */
    private static final String OUTP_HEAD =
            "SELECT IFNULL(c.generic_name, pi.item_name) AS gname,"
            + " IFNULL(c.major_class,'未分类') AS mclass,"
            + " IFNULL(c.manufacturer,'未知厂家') AS mfr,"
            + " IFNULL(d.dept_name,'未知科室') AS dept,"
            + " IFNULL(pi.quantity,0) AS qty, IFNULL(pi.amount,0) AS amount,"
            + " c.essential_flag AS ef,"
            + " CASE WHEN c.yb_drug_code IS NOT NULL AND c.yb_drug_code<>'' THEN 1 ELSE 0 END AS yb,"
            + " c.zero_margin AS zm, c.selfpay_prop AS sp,"
            + " c.abx_grade AS abx, IFNULL(c.abx_grade_name,'') AS an,"
            + " c.chrgitm_lv AS lv, IFNULL(c.chrgitm_lv_name,'') AS ln"
            + " FROM his_dispense d"
            + " JOIN his_prescription_item pi ON pi.prescription_id=d.prescription_id AND pi.tenant_id=d.tenant_id AND pi.deleted=0"
            + " LEFT JOIN his_drug_catalog c ON c.id=pi.drug_id AND c.tenant_id=pi.tenant_id AND c.deleted=0";

    private static final String INP_HEAD =
            "SELECT IFNULL(c.generic_name, i.drug_name) AS gname,"
            + " IFNULL(c.major_class,'未分类') AS mclass,"
            + " IFNULL(c.manufacturer,'未知厂家') AS mfr,"
            + " IFNULL(i.dept_name,'未知科室') AS dept,"
            + " IFNULL(i.actual_qty-i.return_qty,0) AS qty,"
            + " IFNULL((i.actual_qty-i.return_qty)*c.retail_price,0) AS amount,"
            + " c.essential_flag AS ef,"
            + " CASE WHEN c.yb_drug_code IS NOT NULL AND c.yb_drug_code<>'' THEN 1 ELSE 0 END AS yb,"
            + " c.zero_margin AS zm, c.selfpay_prop AS sp,"
            + " c.abx_grade AS abx, IFNULL(c.abx_grade_name,'') AS an,"
            + " c.chrgitm_lv AS lv, IFNULL(c.chrgitm_lv_name,'') AS ln"
            + " FROM his_inp_dispense i"
            + " LEFT JOIN his_drug_catalog c ON c.id=i.drug_catalog_id AND c.tenant_id=i.tenant_id AND c.deleted=0";

    /** 消耗底表(带过滤): 返回可嵌入 FROM(...)b 的 UNION SQL 与按序参数。orgId/pharmacyId 为空则不加该过滤。 */
    private Object[] drugBase(Long tid, String[] range, Long orgId, Long pharmacyId) {
        StringBuilder outp = new StringBuilder(OUTP_HEAD)
                .append(" WHERE d.tenant_id=? AND d.deleted=0 AND d.status=2 AND d.dispense_time>=? AND d.dispense_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) { outp.append(" AND d.org_id=?"); args.add(orgId); }
        if (pharmacyId != null) { outp.append(" AND d.pharmacy_id=?"); args.add(pharmacyId); }
        StringBuilder inp = new StringBuilder(INP_HEAD)
                .append(" WHERE i.tenant_id=? AND i.deleted=0 AND i.dispense_time>=? AND i.dispense_time<?");
        args.add(tid);
        args.add(range[0]);
        args.add(nextDay(range[1]));
        if (orgId != null) { inp.append(" AND i.org_id=?"); args.add(orgId); }
        if (pharmacyId != null) { inp.append(" AND i.pharmacy_id=?"); args.add(pharmacyId); }
        return new Object[] {outp + " UNION ALL " + inp, args.toArray()};
    }

    /** 消耗维度白名单: 请求 dimType -> 底表列名(防注入) */
    private String dimColumn(String dimType) {
        String d = dimType == null ? "" : dimType.trim().toLowerCase();
        switch (d) {
            case "major_class": return "mclass";
            case "manufacturer": return "mfr";
            case "dept": return "dept";
            case "generic":
            default: return "gname";
        }
    }

    private String dimLabel(String dimType) {
        String d = dimType == null ? "" : dimType.trim().toLowerCase();
        switch (d) {
            case "major_class": return "大类";
            case "manufacturer": return "厂家";
            case "dept": return "科室";
            case "generic":
            default: return "通用名";
        }
    }

    /* ============================ 维度一: 用量与消耗 ============================ */

    /** 消耗概况卡片: 本期消耗总额/净量/品种数 + 门诊/住院拆分 + 环比(上一等长周期)增长 */
    public Map<String, Object> usageSummary(Long orgId, Long pharmacyId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        Map<String, Object> cur = usageAgg(tid, range, orgId, pharmacyId);
        // 上一等长周期(环比): [start-len, start)
        LocalDate s = LocalDate.parse(range[0]);
        LocalDate e = LocalDate.parse(range[1]);
        long days = e.isBefore(s) ? 1 : (e.toEpochDay() - s.toEpochDay() + 1);
        String[] prev = {s.minusDays(days).toString(), s.minusDays(1).toString()};
        Map<String, Object> pv = usageAgg(tid, prev, orgId, pharmacyId);
        BigDecimal curAmt = toBd(cur.get("totalAmount"));
        BigDecimal prevAmt = toBd(pv.get("totalAmount"));
        Map<String, Object> out = new LinkedHashMap<>(cur);
        out.put("prevAmount", prevAmt);
        // 环比: 上期为 0 时无基数可比, 本期有量记"新增", 两期皆 0 记"0.0%"(避免百分比爆炸)
        String growth;
        if (prevAmt.signum() == 0) {
            growth = curAmt.signum() == 0 ? "0.0%" : "新增";
        } else {
            growth = curAmt.subtract(prevAmt).multiply(HUNDRED)
                    .divide(prevAmt, 1, RoundingMode.HALF_UP).toPlainString() + "%";
        }
        out.put("growthRate", growth);
        out.put("periodStart", range[0]);
        out.put("periodEnd", range[1]);
        return out;
    }

    private Map<String, Object> usageAgg(Long tid, String[] range, Long orgId, Long pharmacyId) {
        Object[] base = drugBase(tid, range, orgId, pharmacyId);
        String sql = "SELECT IFNULL(SUM(b.amount),0) AS totalAmount, IFNULL(SUM(b.qty),0) AS totalQty,"
                + " COUNT(DISTINCT b.gname) AS drugCount FROM (" + base[0] + ") b";
        Map<String, Object> row = jdbc.queryForMap(sql, (Object[]) base[1]);
        // 门诊/住院金额拆分
        String outpSql = "SELECT IFNULL(SUM(pi.amount),0) FROM his_dispense d"
                + " JOIN his_prescription_item pi ON pi.prescription_id=d.prescription_id AND pi.tenant_id=d.tenant_id AND pi.deleted=0"
                + " WHERE d.tenant_id=? AND d.deleted=0 AND d.status=2 AND d.dispense_time>=? AND d.dispense_time<?";
        List<Object> a1 = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) { outpSql += " AND d.org_id=?"; a1.add(orgId); }
        if (pharmacyId != null) { outpSql += " AND d.pharmacy_id=?"; a1.add(pharmacyId); }
        BigDecimal outpAmt = jdbc.queryForObject(outpSql, BigDecimal.class, a1.toArray());
        String inpSql = "SELECT IFNULL(SUM((i.actual_qty-i.return_qty)*IFNULL(c.retail_price,0)),0) FROM his_inp_dispense i"
                + " LEFT JOIN his_drug_catalog c ON c.id=i.drug_catalog_id AND c.tenant_id=i.tenant_id AND c.deleted=0"
                + " WHERE i.tenant_id=? AND i.deleted=0 AND i.dispense_time>=? AND i.dispense_time<?";
        List<Object> a2 = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) { inpSql += " AND i.org_id=?"; a2.add(orgId); }
        if (pharmacyId != null) { inpSql += " AND i.pharmacy_id=?"; a2.add(pharmacyId); }
        BigDecimal inpAmt = jdbc.queryForObject(inpSql, BigDecimal.class, a2.toArray());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalAmount", row.get("totalAmount"));
        m.put("totalQty", row.get("totalQty"));
        m.put("drugCount", row.get("drugCount"));
        m.put("outpAmount", outpAmt);
        m.put("inpAmount", inpAmt);
        return m;
    }

    /**
     * 消耗维度排行(带 ABC 分类): 按 dimType(通用名/大类/厂家/科室) 聚合净金额降序, 计算金额占比、
     * 累计占比与 ABC(A≤80% / B≤95% / C 其余)。limit<=0 时返回全部维度项。
     */
    public List<Map<String, Object>> usageDim(Long orgId, Long pharmacyId, String startDate, String endDate,
                                              String dimType, int limit) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        String col = dimColumn(dimType);
        Object[] base = drugBase(tid, range, orgId, pharmacyId);
        String sql = "SELECT b." + col + " AS name, IFNULL(SUM(b.qty),0) AS qty, IFNULL(SUM(b.amount),0) AS amount"
                + " FROM (" + base[0] + ") b GROUP BY b." + col + " ORDER BY amount DESC";
        if (limit > 0) {
            sql += " LIMIT " + Math.min(limit, 500);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql, (Object[]) base[1]);
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            total = total.add(toBd(r.get("amount")));
        }
        BigDecimal cum = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal amt = toBd(r.get("amount"));
            cum = cum.add(amt);
            r.put("pct", ratioPct(amt, total));
            BigDecimal cumPct = ratio(amtSumPct(cum, total));
            r.put("cumPct", cumPct.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%");
            r.put("abcClass", abcClass(cumPct));
        }
        return rows;
    }

    private BigDecimal amtSumPct(BigDecimal cum, BigDecimal total) {
        return total.signum() == 0 ? BigDecimal.ZERO
                : cum.multiply(HUNDRED).divide(total, 4, RoundingMode.HALF_UP);
    }

    private String abcClass(BigDecimal cumPct) {
        if (cumPct.compareTo(BigDecimal.valueOf(80)) <= 0) {
            return "A";
        }
        if (cumPct.compareTo(BigDecimal.valueOf(95)) <= 0) {
            return "B";
        }
        return "C";
    }

    /** 消耗 Top 明细(通用名 + 大类 + 厂家 三维透视, 按金额降序), 供下钻与导出 */
    public List<Map<String, Object>> usageDetail(Long orgId, Long pharmacyId, String startDate, String endDate, int topN) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        int limit = topN <= 0 ? 50 : Math.min(topN, 500);
        Object[] base = drugBase(tid, range, orgId, pharmacyId);
        String sql = "SELECT b.gname AS genericName, b.mclass AS majorClass, b.mfr AS manufacturer,"
                + " IFNULL(SUM(b.qty),0) AS qty, IFNULL(SUM(b.amount),0) AS amount"
                + " FROM (" + base[0] + ") b GROUP BY b.gname, b.mclass, b.mfr ORDER BY amount DESC LIMIT " + limit;
        return jdbc.queryForList(sql, (Object[]) base[1]);
    }

    /** 用量报表导出(双Sheet): Sheet1 ABC 维度排行(默认按大类), Sheet2 Top 消耗明细 */
    public Map<String, Object> exportUsage(Long orgId, Long pharmacyId, String startDate, String endDate, String dimType) {
        List<List<Object>> dimRows = new ArrayList<>();
        dimRows.add(Collections.singletonList("导出说明: 集采占比因缺稳定批次-供应商链路本期未纳入; ABC 按累计金额 80/95 划分"));
        List<List<Object>> rank = new ArrayList<>();
        List<Map<String, Object>> dims = usageDim(orgId, pharmacyId, startDate, endDate, dimType, 0);
        for (Map<String, Object> r : dims) {
            rank.add(Arrays.asList(text(r.get("name")), r.get("qty"), r.get("amount"), r.get("pct"), r.get("cumPct"), r.get("abcClass")));
        }
        List<List<Object>> detail = new ArrayList<>();
        for (Map<String, Object> r : usageDetail(orgId, pharmacyId, startDate, endDate, 100)) {
            detail.add(Arrays.asList(text(r.get("genericName")), text(r.get("majorClass")), text(r.get("manufacturer")),
                    r.get("qty"), r.get("amount")));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheetOf("消耗排行" + dimLabel(dimType), headOf(dimLabel(dimType), "净数量", "消耗金额", "金额占比", "累计占比", "ABC"), rank));
        sheets.add(sheetOf("Top消耗明细", headOf("通用名", "大类", "厂家", "净数量", "消耗金额"), detail));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheets", sheets);
        out.put("note", dimRows);
        return out;
    }

    /* ============================ 维度二: 医保合规分析 ============================ */

    /**
     * 医保合规指标卡(按消耗金额加权, 门诊+住院合并口径): 基药占比 / 医保目录对账率 / 零差率药品占比 /
     * 平均自付比例 + 抗菌供应分级分布(abxDist) + 甲乙丙结构(lvDist) + 药占比(drugCostRatio) + 门诊次均费用(avgPerVisit)。
     */
    public Map<String, Object> ybSummary(Long orgId, Long pharmacyId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        Object[] base = drugBase(tid, range, orgId, pharmacyId);
        String sql = "SELECT IFNULL(SUM(b.amount),0) AS totalAmount,"
                + " IFNULL(SUM(CASE WHEN b.ef=1 THEN b.amount ELSE 0 END),0) AS essentialAmount,"
                + " IFNULL(SUM(CASE WHEN b.yb=1 THEN b.amount ELSE 0 END),0) AS ybAmount,"
                + " IFNULL(SUM(CASE WHEN b.zm=1 THEN b.amount ELSE 0 END),0) AS zeroAmount,"
                + " IFNULL(SUM(CASE WHEN b.sp IS NOT NULL THEN b.amount*b.sp ELSE 0 END),0) AS spWeighted,"
                + " IFNULL(SUM(CASE WHEN b.sp IS NOT NULL THEN b.amount ELSE 0 END),0) AS spBase"
                + " FROM (" + base[0] + ") b";
        Map<String, Object> agg = jdbc.queryForMap(sql, (Object[]) base[1]);
        BigDecimal total = toBd(agg.get("totalAmount"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drugAmount", total);
        out.put("essentialRatio", ratioPct(toBd(agg.get("essentialAmount")), total));
        out.put("ybMatchRatio", ratioPct(toBd(agg.get("ybAmount")), total));
        out.put("zeroMarginRatio", ratioPct(toBd(agg.get("zeroAmount")), total));
        out.put("avgSelfpayProp", ratioPct(toBd(agg.get("spWeighted")), toBd(agg.get("spBase"))));
        // 抗菌供应分级分布(仅 abx 非空)
        String abxSql = "SELECT IFNULL(NULLIF(b.an,''),b.abx) AS grade, IFNULL(SUM(b.amount),0) AS amount, IFNULL(SUM(b.qty),0) AS qty"
                + " FROM (" + base[0] + ") b WHERE b.abx IS NOT NULL AND b.abx<>'' GROUP BY grade ORDER BY amount DESC";
        List<Map<String, Object>> abx = jdbc.queryForList(abxSql, (Object[]) base[1]);
        for (Map<String, Object> r : abx) {
            r.put("pct", ratioPct(toBd(r.get("amount")), total));
        }
        out.put("abxDist", abx);
        // 甲乙丙结构分布
        String lvSql = "SELECT IFNULL(NULLIF(b.ln,''),IFNULL(b.lv,'未分类')) AS level, IFNULL(SUM(b.amount),0) AS amount, IFNULL(SUM(b.qty),0) AS qty"
                + " FROM (" + base[0] + ") b GROUP BY level ORDER BY amount DESC";
        List<Map<String, Object>> lv = jdbc.queryForList(lvSql, (Object[]) base[1]);
        for (Map<String, Object> r : lv) {
            r.put("pct", ratioPct(toBd(r.get("amount")), total));
        }
        out.put("chrgitmLvDist", lv);
        // 药占比 = 药品消耗金额 / (门诊收费总额 + 住院费用明细总额); 门诊次均费用 = 门诊收费总额 / 门诊收费单数
        String billSql = "SELECT IFNULL(SUM(total_amount),0) AS amt, COUNT(*) AS cnt FROM his_charge_bill"
                + " WHERE tenant_id=? AND deleted=0 AND bill_type=1 AND status=1 AND charge_time>=? AND charge_time<?";
        List<Object> ba = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) { billSql += " AND org_id=?"; ba.add(orgId); }
        Map<String, Object> bill = jdbc.queryForMap(billSql, ba.toArray());
        BigDecimal billAmt = toBd(bill.get("amt"));
        long billCnt = ((Number) bill.get("cnt")).longValue();
        String inpFeeSql = "SELECT IFNULL(SUM(amount),0) FROM his_inp_charge_detail"
                + " WHERE tenant_id=? AND deleted=0 AND status=1 AND charge_date>=? AND charge_date<?";
        List<Object> fa = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) { inpFeeSql += " AND org_id=?"; fa.add(orgId); }
        BigDecimal inpFee = jdbc.queryForObject(inpFeeSql, BigDecimal.class, fa.toArray());
        BigDecimal totalCost = billAmt.add(inpFee);
        out.put("totalCost", totalCost);
        out.put("drugCostRatio", ratioPct(total, totalCost));
        out.put("avgPerVisit", billCnt == 0 ? "0.00" : billAmt.divide(BigDecimal.valueOf(billCnt), 2, RoundingMode.HALF_UP).toPlainString());
        out.put("periodStart", range[0]);
        out.put("periodEnd", range[1]);
        return out;
    }

    /** 医保合规导出(三Sheet): 指标卡 + 抗菌分级分布 + 甲乙丙结构 */
    public Map<String, Object> exportYb(Long orgId, Long pharmacyId, String startDate, String endDate) {
        Map<String, Object> s = ybSummary(orgId, pharmacyId, startDate, endDate);
        List<List<Object>> cardRows = new ArrayList<>();
        cardRows.add(Arrays.asList("药品消耗金额", s.get("drugAmount")));
        cardRows.add(Arrays.asList("基本药物占比", s.get("essentialRatio")));
        cardRows.add(Arrays.asList("医保目录对账率", s.get("ybMatchRatio")));
        cardRows.add(Arrays.asList("零差率药品占比", s.get("zeroMarginRatio")));
        cardRows.add(Arrays.asList("平均自付比例", s.get("avgSelfpayProp")));
        cardRows.add(Arrays.asList("药占比(药品/医药总费)", s.get("drugCostRatio")));
        cardRows.add(Arrays.asList("门诊次均费用(元)", s.get("avgPerVisit")));
        cardRows.add(Arrays.asList("集采占比", "本期未纳入(缺稳定批次-供应商链路)"));
        List<List<Object>> abx = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("abxDist")) {
            abx.add(Arrays.asList(text(r.get("grade")), r.get("qty"), r.get("amount"), r.get("pct")));
        }
        List<List<Object>> lv = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("chrgitmLvDist")) {
            lv.add(Arrays.asList(text(r.get("level")), r.get("qty"), r.get("amount"), r.get("pct")));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheetOf("合规指标", headOf("指标", "数值"), cardRows));
        sheets.add(sheetOf("抗菌供应分级", headOf("分级", "净数量", "金额", "占比"), abx));
        sheets.add(sheetOf("甲乙丙结构", headOf("类别", "净数量", "金额", "占比"), lv));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheets", sheets);
        return out;
    }

    /* ============================ 维度三: 处方与退药质量 ============================ */

    /**
     * 处方与退药质量卡: 审方通过率(passRate)/待审/驳回计数 + 驳回原因分布(rejectReasons)
     * + 退药率(returnRate, 审批退药单数/已发药单数) + 退药原因分布(returnReasons) + 处方点评抽样Top(rxReview)。
     * 审方口径: audit_status IN (2,3) 为已审; passRate=通过/(通过+驳回)。按处方开立时间(create_time)统计。
     */
    public Map<String, Object> qualitySummary(Long orgId, Long pharmacyId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        String start = range[0];
        String end = nextDay(range[1]);
        // 审方聚合(处方 -> his_dept 取 org_id 做机构隔离)
        StringBuilder aw = new StringBuilder(" FROM his_prescription p LEFT JOIN his_dept hd ON hd.id=p.dept_id AND hd.tenant_id=p.tenant_id AND hd.deleted=0")
                .append(" WHERE p.tenant_id=? AND p.deleted=0 AND p.create_time>=? AND p.create_time<?");
        List<Object> aargs = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) { aw.append(" AND hd.org_id=?"); aargs.add(orgId); }
        if (pharmacyId != null) { aw.append(" AND p.pharmacy_id=?"); aargs.add(pharmacyId); }
        Map<String, Object> audit = jdbc.queryForMap("SELECT"
                + " SUM(CASE WHEN p.audit_status=1 THEN 1 ELSE 0 END) AS pending,"
                + " SUM(CASE WHEN p.audit_status=2 THEN 1 ELSE 0 END) AS pass,"
                + " SUM(CASE WHEN p.audit_status=3 THEN 1 ELSE 0 END) AS reject,"
                + " COUNT(*) AS total" + aw, aargs.toArray());
        long pass = num(audit.get("pass"));
        long reject = num(audit.get("reject"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rxTotal", num(audit.get("total")));
        out.put("auditPending", num(audit.get("pending")));
        out.put("auditPass", pass);
        out.put("auditReject", reject);
        out.put("passRate", ratioPct(BigDecimal.valueOf(pass), BigDecimal.valueOf(pass + reject)));
        // 驳回原因分布
        StringBuilder rr = new StringBuilder(" SELECT IFNULL(NULLIF(p.reject_reason,''),'未填写原因') AS reason, COUNT(*) AS cnt")
                .append(aw).append(" AND p.audit_status=3 GROUP BY reason ORDER BY cnt DESC LIMIT 20");
        out.put("rejectReasons", jdbc.queryForList(rr.toString(), aargs.toArray()));
        // 退药(已审批 status=1, 审批时间口径): 单数/金额 + 发药单数(退药率分母)
        StringBuilder dr = new StringBuilder(" SELECT IFNULL(SUM(r.return_amount),0) AS returnAmount, COUNT(*) AS returnCount FROM his_drug_return r")
                .append(" WHERE r.tenant_id=? AND r.deleted=0 AND r.status=1 AND r.approve_time>=? AND r.approve_time<?");
        List<Object> dargs = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) { dr.append(" AND r.org_id=?"); dargs.add(orgId); }
        Map<String, Object> ret = jdbc.queryForMap(dr.toString(), dargs.toArray());
        long returnCount = num(ret.get("returnCount"));
        StringBuilder dsql = new StringBuilder("SELECT COUNT(*) FROM his_dispense WHERE tenant_id=? AND deleted=0 AND status=2 AND dispense_time>=? AND dispense_time<?");
        List<Object> dsa = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) { dsql.append(" AND org_id=?"); dsa.add(orgId); }
        if (pharmacyId != null) { dsql.append(" AND pharmacy_id=?"); dsa.add(pharmacyId); }
        long dispenseCount = jdbc.queryForObject(dsql.toString(), Long.class, dsa.toArray());
        out.put("dispenseCount", dispenseCount);
        out.put("returnCount", returnCount);
        out.put("returnAmount", ret.get("returnAmount"));
        out.put("returnRate", ratioPct(BigDecimal.valueOf(returnCount), BigDecimal.valueOf(dispenseCount)));
        // 退药原因分布
        StringBuilder rrs = new StringBuilder(" SELECT IFNULL(NULLIF(r.reason,''),'未填写原因') AS reason, COUNT(*) AS cnt, IFNULL(SUM(r.return_amount),0) AS amount")
                .append(" FROM his_drug_return r WHERE r.tenant_id=? AND r.deleted=0 AND r.status=1 AND r.approve_time>=? AND r.approve_time<?");
        List<Object> rsa = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) { rrs.append(" AND r.org_id=?"); rsa.add(orgId); }
        rrs.append(" GROUP BY reason ORDER BY cnt DESC LIMIT 20");
        out.put("returnReasons", jdbc.queryForList(rrs.toString(), rsa.toArray()));
        // 处方点评抽样 Top(大金额处方前 20, 附明细项数)
        StringBuilder rx = new StringBuilder(" SELECT p.rx_no AS rxNo, p.patient_name AS patientName, IFNULL(p.dept_name,'未知科室') AS deptName,")
                .append(" IFNULL(p.dr_name,'') AS drName, p.total_amount AS totalAmount,")
                .append(" (SELECT COUNT(*) FROM his_prescription_item pi WHERE pi.prescription_id=p.id AND pi.deleted=0) AS itemCount")
                .append(" FROM his_prescription p LEFT JOIN his_dept hd ON hd.id=p.dept_id AND hd.tenant_id=p.tenant_id AND hd.deleted=0")
                .append(" WHERE p.tenant_id=? AND p.deleted=0 AND p.create_time>=? AND p.create_time<?");
        List<Object> rxa = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) { rx.append(" AND hd.org_id=?"); rxa.add(orgId); }
        if (pharmacyId != null) { rx.append(" AND p.pharmacy_id=?"); rxa.add(pharmacyId); }
        rx.append(" ORDER BY p.total_amount DESC, p.id DESC LIMIT 20");
        out.put("rxReview", jdbc.queryForList(rx.toString(), rxa.toArray()));
        out.put("periodStart", range[0]);
        out.put("periodEnd", range[1]);
        return out;
    }

    /** 处方与退药质量导出(三Sheet): 质量指标卡 + 退药原因分布 + 处方点评Top */
    public Map<String, Object> exportQuality(Long orgId, Long pharmacyId, String startDate, String endDate) {
        Map<String, Object> s = qualitySummary(orgId, pharmacyId, startDate, endDate);
        List<List<Object>> card = new ArrayList<>();
        card.add(Arrays.asList("处方总数", s.get("rxTotal")));
        card.add(Arrays.asList("审方待审", s.get("auditPending")));
        card.add(Arrays.asList("审方通过", s.get("auditPass")));
        card.add(Arrays.asList("审方驳回", s.get("auditReject")));
        card.add(Arrays.asList("审方通过率", s.get("passRate")));
        card.add(Arrays.asList("发药单数", s.get("dispenseCount")));
        card.add(Arrays.asList("退药单数", s.get("returnCount")));
        card.add(Arrays.asList("退药金额", s.get("returnAmount")));
        card.add(Arrays.asList("退药率", s.get("returnRate")));
        List<List<Object>> retReasons = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("returnReasons")) {
            retReasons.add(Arrays.asList(text(r.get("reason")), r.get("cnt"), r.get("amount")));
        }
        List<List<Object>> rejectReasons = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("rejectReasons")) {
            rejectReasons.add(Arrays.asList(text(r.get("reason")), r.get("cnt")));
        }
        List<List<Object>> rxReview = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) s.get("rxReview")) {
            rxReview.add(Arrays.asList(text(r.get("rxNo")), text(r.get("patientName")), text(r.get("deptName")),
                    text(r.get("drName")), r.get("itemCount"), r.get("totalAmount")));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheetOf("质量指标", headOf("指标", "数值"), card));
        sheets.add(sheetOf("审方驳回原因", headOf("驳回原因", "次数"), rejectReasons));
        sheets.add(sheetOf("退药原因分布", headOf("退药原因", "次数", "退药金额"), retReasons));
        sheets.add(sheetOf("处方点评Top", headOf("处方号", "患者", "科室", "医生", "明细项数", "处方金额"), rxReview));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheets", sheets);
        return out;
    }

    /* ============================ 内部工具 ============================ */

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 比率(0-100, 保留1位) -> "XX.X%"; 分母为0返回 0.0% */
    private static String ratioPct(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() == 0) {
            return "0.0%";
        }
        return num.multiply(HUNDRED).divide(den, 1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static BigDecimal ratio(BigDecimal pct) {
        return pct;
    }

    /** 区间规整: 缺省最近30天, 起>止时以止为起点。返回 [start,end] 含首尾(yyyy-MM-dd), 闭区间。 */
    private static String[] normRange(String startDate, String endDate) {
        LocalDate end = parseDate(endDate);
        if (end == null) {
            end = LocalDate.now();
        }
        LocalDate start = parseDate(startDate);
        if (start == null) {
            start = end.minusDays(29);
        }
        if (start.isAfter(end)) {
            start = end;
        }
        return new String[] {start.toString(), end.toString()};
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 左闭右开上界: end 的次日(yyyy-MM-dd), 用于 DATETIME >=start AND <nextDay(end) */
    private static String nextDay(String date) {
        return LocalDate.parse(date).plusDays(1).toString();
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static List<List<String>> headOf(String... cols) {
        List<List<String>> head = new ArrayList<>();
        for (String c : cols) {
            head.add(Collections.singletonList(c));
        }
        return head;
    }

    private static Map<String, Object> sheetOf(String sheetName, List<List<String>> head, List<List<Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sheetName", sheetName);
        m.put("head", head);
        m.put("rows", rows);
        return m;
    }
}
