package com.yb.hi.service.community;

import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.entity.community.HisConsCatalog;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.service.StdDictMaintainService;
import com.yb.hi.service.StdDictQueryService;
import com.yb.hi.service.basedata.HisChargeItemService;
import com.yb.hi.stddict.StdDict;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标准字典 -> 医共体目录(L2) 导入服务(牵头机构)。
 * 复用 StdDictMaintainService.row 读取标准字典整行, 按医保编码幂等写入 L2 并记 src 三件套。
 * 标准字典无价格/管理字段, 价格与三级单位换算等由前端补录弹窗填写后随实体一并提交。
 */
@Service
public class CommunityDictImportService {

    private final StdDictMaintainService stdMaintain;
    private final StdDictQueryService stdQuery;
    private final HisDrugCatalogService drugService;
    private final HisConsCatalogService consService;
    private final HisChargeItemService chargeService;
    private final DataSource dataSource;

    public CommunityDictImportService(StdDictMaintainService stdMaintain,
                                      StdDictQueryService stdQuery,
                                      HisDrugCatalogService drugService,
                                      HisConsCatalogService consService,
                                      HisChargeItemService chargeService,
                                      DataSource dataSource) {
        this.stdMaintain = stdMaintain;
        this.stdQuery = stdQuery;
        this.drugService = drugService;
        this.consService = consService;
        this.chargeService = chargeService;
        this.dataSource = dataSource;
    }

    /* ================= 药品 ================= */

    /** 从 std_drug 行映射为药品目录预览(未落库), 供前端补录弹窗 */
    public HisDrugCatalog previewDrug(long stdId) {
        Map<String, Object> r = stdMaintain.row("drug", stdId);
        HisDrugCatalog d = new HisDrugCatalog();
        d.setYbDrugCode(str(r, "drug_code"));
        d.setDrugStdCode(str(r, "drug_std_code"));
        d.setApprovalNo(str(r, "approval_no"));
        d.setGenericName(str(r, "reg_name"));
        d.setTradeName(str(r, "trade_name"));
        d.setMajorClass(str(r, "major_class"));
        d.setDosform(str(r, "hi_dosform"));
        d.setDosformName(str(r, "act_dosform"));
        d.setSpec(CatalogMapService.composeDrugSpec(firstNonBlank(str(r, "act_spec"), str(r, "reg_spec")), str(r, "min_pack_qty")));
        d.setManufacturer(str(r, "drug_entp"));
        d.setMktHolder(str(r, "mkt_holder"));
        d.setChrgitmLv(str(r, "chrgitm_lv"));
        d.setPayStdPrep(str(r, "pay_std_prep"));
        d.setNegoFlag(str(r, "nego_flag"));
        d.setMsdFlag(str(r, "msd_flag"));
        d.setLtdSelfFlag(str(r, "ltd_self_flag"));
        // 三级单位与换算: 标准字典仅提供部分, unit_dose/pack_unit 需人工补录
        d.setDoseUnit(str(r, "min_prep_unit"));
        d.setMinUnit(str(r, "min_pack_unit"));
        d.setPackRatio(intg(str(r, "min_pack_qty")));
        d.setRoundRule(1);
        d.setZeroMargin(1);
        d.setStatus(1);
        d.setSrcType("drug");
        d.setSrcDoc(srcDoc("drug"));
        d.setSrcCode(str(r, "drug_code"));
        return d;
    }

    /** 幂等导入药品目录: 同租户同医保码已存在则更新, 否则新增 */
    public Long importDrug(HisDrugCatalog e) {
        if (e == null || !StringUtils.hasText(e.getYbDrugCode())) {
            throw new BizException(400, "缺少医保药品代码, 无法导入");
        }
        if (!StringUtils.hasText(e.getDrugCode())) {
            e.setDrugCode(e.getYbDrugCode());
        }
        if (!StringUtils.hasText(e.getSrcType())) {
            e.setSrcType("drug");
            e.setSrcDoc(srcDoc("drug"));
            e.setSrcCode(e.getYbDrugCode());
        }
        HisDrugCatalog exist = drugService.lambdaQuery()
                .eq(HisDrugCatalog::getYbDrugCode, e.getYbDrugCode()).one();
        drugService.backfillDict(e);
        if (exist != null) {
            e.setId(exist.getId());
            drugService.updateById(e);
            return exist.getId();
        }
        drugService.save(e);
        return e.getId();
    }

    /* ================= 耗材 ================= */

    public HisConsCatalog previewCons(long stdId) {
        Map<String, Object> r = stdMaintain.row("consumable", stdId);
        HisConsCatalog c = new HisConsCatalog();
        c.setYbConsCode(str(r, "cons_code"));
        c.setName(str(r, "hi_genname"));
        c.setCat1(str(r, "cat1"));
        c.setCat2(str(r, "cat2"));
        c.setCat3(str(r, "cat3"));
        c.setMaterial(str(r, "material"));
        c.setFeature(str(r, "feature"));
        c.setManufacturer(str(r, "cons_entp"));
        c.setPayStd(str(r, "pay_std"));
        c.setRegCertNo(str(r, "reg_cert_no"));
        c.setPackRatio(1);
        c.setChargeFlag(1);
        c.setStatus(1);
        c.setSrcType("consumable");
        c.setSrcDoc(srcDoc("consumable"));
        c.setSrcCode(str(r, "cons_code"));
        return c;
    }

    public Long importCons(HisConsCatalog e) {
        if (e == null || !StringUtils.hasText(e.getYbConsCode())) {
            throw new BizException(400, "缺少医保耗材代码, 无法导入");
        }
        if (!StringUtils.hasText(e.getConsCode())) {
            e.setConsCode(e.getYbConsCode());
        }
        if (!StringUtils.hasText(e.getSrcType())) {
            e.setSrcType("consumable");
            e.setSrcDoc(srcDoc("consumable"));
            e.setSrcCode(e.getYbConsCode());
        }
        HisConsCatalog exist = consService.lambdaQuery()
                .eq(HisConsCatalog::getYbConsCode, e.getYbConsCode()).one();
        consService.backfillDict(e);
        if (exist != null) {
            e.setId(exist.getId());
            consService.updateById(e);
            return exist.getId();
        }
        consService.save(e);
        return e.getId();
    }

    /* ================= 收费项目 ================= */

    /** 从物价/医疗服务标准字典行映射为收费项目预览(未落库) */
    public HisChargeItem previewCharge(String dictKey, long stdId) {
        return mapCharge(dictKey, stdMaintain.row(dictKey, stdId));
    }

    /**
     * 批量导入标准字典全表为收费项目(L2): 按院内编码幂等, 已存在跳过。
     * 返回 total/imported/skipped。价格等管理字段标准字典未提供, 导入后经弹窗补录/调价维护。
     */
    public Map<String, Object> importChargeBatch(String dictKey) {
        StdDict d = stdQuery.get(dictKey);
        if (d == null) {
            throw new BizException(400, "未知标准字典: " + dictKey);
        }
        if (!"msi_nat".equals(dictKey) && !"msi_hb".equals(dictKey) && !"med_service".equals(dictKey)) {
            throw new BizException(400, "该字典不支持批量导入收费项目: " + dictKey);
        }
        // 已存在院内编码(本租户), 幂等跳过
        Set<String> exist = new HashSet<>();
        for (HisChargeItem e : chargeService.lambdaQuery().select(HisChargeItem::getItemCode).list()) {
            exist.add(e.getItemCode());
        }
        int total = 0;
        int skipped = 0;
        List<HisChargeItem> batch = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + d.getTable())) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                total++;
                Map<String, Object> r = new HashMap<>();
                for (int i = 1; i <= cols; i++) {
                    r.put(md.getColumnLabel(i), rs.getObject(i));
                }
                HisChargeItem it = mapCharge(dictKey, r);
                String code = it.getItemCode();
                // 无编码或无名称的行(如源表附录仅编码行)不导入
                if (!StringUtils.hasText(code) || !StringUtils.hasText(it.getItemName()) || !exist.add(code)) {
                    skipped++;
                    continue;
                }
                batch.add(it);
            }
        } catch (SQLException e) {
            throw new BizException(500, "读取标准字典失败: " + e.getMessage());
        }
        if (!batch.isEmpty()) {
            chargeService.saveBatch(batch, 500);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", total);
        res.put("imported", batch.size());
        res.put("skipped", skipped);
        // 导入后置: 依据财务归集口径规范版本对照链回填四分类(仅填空, 保留人工改选), 使 msi_hb/med_service 行不再空壳
        Map<String, Object> bf = backfillChargeClass();
        res.put("classBackfilled", bf.get("classFilled"));
        res.put("catBackfilled", bf.get("catFilled"));
        return res;
    }

    /**
     * 收费项目四分类回填(导入后置/可独立调用): 按院内编码派生
     * 票据分类/会计科目/病案首页归并(item_code 去湖北扩展尾部字母 -> std_msi_fin.code_2001)
     * 与 物价分类(fin.code_2023 -> std_msi_nat.item_code -> cat_code)。
     * 安全口径: 仅填充当前为空的行(不覆盖人工改选); 只写归并三元组/cat_code 唯一的标准码, 歧义码留空不猜。
     * 返回 {classFilled, catFilled} 本次实际回填行数。
     */
    public Map<String, Object> backfillChargeClass() {
        // item_code 尾部字母(如 110200001a / 480000007x 的 a/x)是湖北扩展位, 2001 版基码为纯数字, 去尾后关联 code_2001
        String strip = "REGEXP_REPLACE(c.item_code,'[^0-9]+$','')";
        Map<String, Object> res = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            int cls = st.executeUpdate(
                "UPDATE his_charge_item c JOIN ("
                + "  SELECT code_2001, MIN(invoice_class) inv, MIN(acct_class) acct, MIN(mr_cost_class) mr"
                + "  FROM std_msi_fin WHERE code_2001 IS NOT NULL AND code_2001<>''"
                + "  GROUP BY code_2001 HAVING COUNT(DISTINCT invoice_class,acct_class,mr_cost_class)=1) f"
                + "  ON f.code_2001=" + strip
                + " SET c.invoice_class=f.inv, c.acct_class=f.acct, c.mr_cost_class=f.mr"
                + " WHERE c.deleted=0 AND (c.invoice_class IS NULL OR c.invoice_class='')");
            int cat = st.executeUpdate(
                "UPDATE his_charge_item c JOIN ("
                + "  SELECT f.code_2001 code_2001, MIN(n.cat_code) cat"
                + "  FROM std_msi_fin f JOIN std_msi_nat n ON n.item_code=f.code_2023"
                + "  WHERE f.code_2001 IS NOT NULL AND f.code_2001<>'' AND n.cat_code IS NOT NULL AND n.cat_code<>''"
                + "  GROUP BY f.code_2001 HAVING COUNT(DISTINCT n.cat_code)=1) g"
                + "  ON g.code_2001=" + strip
                + " SET c.cat_code=g.cat"
                + " WHERE c.deleted=0 AND (c.cat_code IS NULL OR c.cat_code='')");
            res.put("classFilled", cls);
            res.put("catFilled", cat);
        } catch (SQLException e) {
            throw new BizException(500, "收费项目四分类回填失败: " + e.getMessage());
        }
        return res;
    }

    /** 标准字典行 -> 收费项目实体映射(单条预览与批量导入共用) */
    private HisChargeItem mapCharge(String dictKey, Map<String, Object> r) {
        HisChargeItem it = new HisChargeItem();
        it.setItemType("诊疗");
        it.setStatus(1);
        it.setSrcType(dictKey);
        it.setSrcDoc(srcDoc(dictKey));
        switch (dictKey == null ? "" : dictKey) {
            case "med_service":
                it.setNatItemCode(str(r, "nat_item_code"));
                it.setLocItemCode(str(r, "loc_item_code"));
                it.setItemCode(firstNonBlank(str(r, "loc_item_code"), str(r, "nat_item_code")));
                it.setItemName(firstNonBlank(str(r, "loc_item_name"), str(r, "nat_item_name")));
                it.setUnit(str(r, "prc_unit"));
                it.setItemContent(str(r, "item_connotation"));
                it.setItemExcluded(str(r, "item_excluded"));
                it.setSrcCode(firstNonBlank(str(r, "loc_item_code"), str(r, "nat_item_code")));
                break;
            case "msi_hb":
                it.setItemCode(str(r, "item_code"));
                it.setLocItemCode(str(r, "item_code"));
                it.setItemName(str(r, "item_name"));
                it.setUnit(str(r, "unit"));
                it.setItemContent(str(r, "item_content"));
                it.setItemExcluded(firstNonBlank(str(r, "excluded"), str(r, "item_excluded")));
                it.setItemCat(str(r, "cat_name"));
                it.setSrcCode(str(r, "item_code"));
                break;
            case "msi_nat":
                it.setItemCode(str(r, "item_code"));
                it.setNatItemCode(str(r, "item_code"));
                it.setItemName(str(r, "item_name"));
                it.setUnit(str(r, "unit"));
                it.setInvoiceClass(str(r, "invoice_class"));
                it.setAcctClass(str(r, "acct_class"));
                it.setMrCostClass(str(r, "mr_cost_class"));
                it.setCatCode(str(r, "cat_code"));
                it.setItemCat(str(r, "cat_name"));
                it.setSrcCode(str(r, "item_code"));
                break;
            default:
                throw new BizException(400, "不支持导入为收费项目的字典类型: " + dictKey);
        }
        return it;
    }

    /* ================= 工具 ================= */

    private String srcDoc(String key) {
        StdDict d = stdQuery.get(key);
        return d == null ? null : d.getSrcDoc();
    }

    private String str(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private Integer intg(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return new BigDecimal(s.trim()).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
