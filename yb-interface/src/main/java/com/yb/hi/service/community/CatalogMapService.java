package com.yb.hi.service.community;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.community.CatalogMapApplyReq;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.entity.community.HisConsCatalog;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.community.HisYbMapLog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.community.HisYbMapLogMapper;
import com.yb.hi.service.StdDictMaintainService;
import com.yb.hi.service.basedata.HisChargeItemService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 三目录医保对照服务(医疗机构目录 -> 标准字典医保目录)。
 *
 * 方向: 从院内已有条目(药品 his_drug_catalog / 耗材 his_cons_catalog / 医疗服务项目 his_charge_item)
 * 出发, 为其补/改医保标准编码(yb_drug_code / yb_cons_code / med_list_codg)。
 * 对照源固定为 L1 标准字典: drug->std_drug, cons->std_consumable, charge->std_med_service。
 * 读标准字典复用 {@link StdDictMaintainService}(page 取候选 / row 取全列回填), 不新写 SQL;
 * 写库走各目录 Service 以触发字典回填与租户隔离。
 */
@Service
public class CatalogMapService {

    public static final String CAT_DRUG = "drug";
    public static final String CAT_CONS = "cons";
    public static final String CAT_CHARGE = "charge";

    /** 对照失效原因: 医保码在标准字典中已查不到(目录行被删除) */
    private static final String REASON_DELETED = "已删除";

    private final HisDrugCatalogService drugService;
    private final HisConsCatalogService consService;
    private final HisChargeItemService chargeService;
    private final StdDictMaintainService stdMaintain;
    private final HisYbMapLogMapper ybMapLogMapper;

    public CatalogMapService(HisDrugCatalogService drugService,
                             HisConsCatalogService consService,
                             HisChargeItemService chargeService,
                             StdDictMaintainService stdMaintain,
                             HisYbMapLogMapper ybMapLogMapper) {
        this.drugService = drugService;
        this.consService = consService;
        this.chargeService = chargeService;
        this.stdMaintain = stdMaintain;
        this.ybMapLogMapper = ybMapLogMapper;
    }

    /** 目录 -> 标准字典 key */
    private String stdKeyOf(String catalog) {
        if (CAT_DRUG.equals(catalog)) {
            return "drug";
        }
        if (CAT_CONS.equals(catalog)) {
            return "consumable";
        }
        if (CAT_CHARGE.equals(catalog)) {
            return "med_service";
        }
        throw new BizException(400, "不支持的目录: " + catalog);
    }

    /** 目录 -> 院内表的医保码列(带表名限定), 供标准字典有效性子查询引用外层列 */
    private String ybColRef(String catalog) {
        if (CAT_DRUG.equals(catalog)) {
            return "his_drug_catalog.yb_drug_code";
        }
        if (CAT_CONS.equals(catalog)) {
            return "his_cons_catalog.yb_cons_code";
        }
        if (CAT_CHARGE.equals(catalog)) {
            return "his_charge_item.med_list_codg";
        }
        throw new BizException(400, "不支持的目录: " + catalog);
    }

    /* ================= 覆盖率看板 ================= */

    /** 每目录 {total, mapped, unmapped, invalid}: invalid = 已对照但医保码在标准字典中已作废/过期/删除, 需重新对照 */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(CAT_DRUG, countOf(drugService.count(), drugService.count(new LambdaQueryWrapper<HisDrugCatalog>()
                .isNotNull(HisDrugCatalog::getYbDrugCode).ne(HisDrugCatalog::getYbDrugCode, "")), invalidCount(CAT_DRUG)));
        out.put(CAT_CONS, countOf(consService.count(), consService.count(new LambdaQueryWrapper<HisConsCatalog>()
                .isNotNull(HisConsCatalog::getYbConsCode).ne(HisConsCatalog::getYbConsCode, "")), invalidCount(CAT_CONS)));
        out.put(CAT_CHARGE, countOf(chargeService.count(), chargeService.count(new LambdaQueryWrapper<HisChargeItem>()
                .isNotNull(HisChargeItem::getMedListCodg).ne(HisChargeItem::getMedListCodg, "")), invalidCount(CAT_CHARGE)));
        return out;
    }

    /** 对照失效条数: 已对照且医保码在标准字典中已无有效行(作废/过期/删除) */
    private long invalidCount(String catalog) {
        String validSub = stdMaintain.validExistsSql(stdKeyOf(catalog), ybColRef(catalog));
        if (CAT_DRUG.equals(catalog)) {
            return drugService.count(new LambdaQueryWrapper<HisDrugCatalog>()
                    .isNotNull(HisDrugCatalog::getYbDrugCode).ne(HisDrugCatalog::getYbDrugCode, "")
                    .notExists(validSub));
        }
        if (CAT_CONS.equals(catalog)) {
            return consService.count(new LambdaQueryWrapper<HisConsCatalog>()
                    .isNotNull(HisConsCatalog::getYbConsCode).ne(HisConsCatalog::getYbConsCode, "")
                    .notExists(validSub));
        }
        return chargeService.count(new LambdaQueryWrapper<HisChargeItem>()
                .isNotNull(HisChargeItem::getMedListCodg).ne(HisChargeItem::getMedListCodg, "")
                .notExists(validSub));
    }

    private Map<String, Object> countOf(long total, long mapped, long invalid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("mapped", mapped);
        m.put("unmapped", total - mapped);
        m.put("invalid", invalid);
        return m;
    }

    /* ================= 院内工作队列 ================= */

    /** 院内条目归一视图分页: {id,code,name,spec,manufacturer,unit,ybCode,ybName,ybValid,mapped} */
    public IPage<Map<String, Object>> items(String catalog, long page, long size,
                                            Integer mapped, String keyword, String itemType) {
        boolean hasKw = StringUtils.hasText(keyword);
        if (CAT_DRUG.equals(catalog)) {
            LambdaQueryChainWrapper<HisDrugCatalog> q = drugService.lambdaQuery();
            applyMapped(q, mapped, HisDrugCatalog::getYbDrugCode, catalog);
            if (hasKw) {
                q.and(w -> w.like(HisDrugCatalog::getGenericName, keyword)
                        .or().like(HisDrugCatalog::getTradeName, keyword)
                        .or().like(HisDrugCatalog::getDrugCode, keyword)
                        .or().like(HisDrugCatalog::getYbDrugCode, keyword));
            }
            IPage<HisDrugCatalog> p = q.orderByDesc(HisDrugCatalog::getId).page(new Page<>(page, size));
            return enrichYbInfo(catalog, mapView(p, e -> hosp(e.getId(), e.getDrugCode(), e.getGenericName(), e.getSpec(),
                    e.getManufacturer(), "", e.getYbDrugCode(), e.getRetailPrice(), e.getPrevYbCode(), e.getYbMapEffTime())));
        }
        if (CAT_CONS.equals(catalog)) {
            LambdaQueryChainWrapper<HisConsCatalog> q = consService.lambdaQuery();
            applyMapped(q, mapped, HisConsCatalog::getYbConsCode, catalog);
            if (hasKw) {
                q.and(w -> w.like(HisConsCatalog::getName, keyword)
                        .or().like(HisConsCatalog::getConsCode, keyword)
                        .or().like(HisConsCatalog::getYbConsCode, keyword)
                        .or().like(HisConsCatalog::getRegCertNo, keyword));
            }
            IPage<HisConsCatalog> p = q.orderByDesc(HisConsCatalog::getId).page(new Page<>(page, size));
            return enrichYbInfo(catalog, mapView(p, e -> hosp(e.getId(), e.getConsCode(), e.getName(), e.getSpecModel(),
                    e.getManufacturer(), "", e.getYbConsCode(), e.getChargePrice(), e.getPrevYbCode(), e.getYbMapEffTime())));
        }
        // charge
        LambdaQueryChainWrapper<HisChargeItem> q = chargeService.lambdaQuery()
                .eq(StringUtils.hasText(itemType), HisChargeItem::getItemType, itemType);
        applyMapped(q, mapped, HisChargeItem::getMedListCodg, catalog);
        if (hasKw) {
            q.and(w -> w.like(HisChargeItem::getItemName, keyword)
                    .or().like(HisChargeItem::getItemCode, keyword)
                    .or().like(HisChargeItem::getMedListCodg, keyword));
        }
        IPage<HisChargeItem> p = q.orderByDesc(HisChargeItem::getId).page(new Page<>(page, size));
        return enrichYbInfo(catalog, mapView(p, e -> hosp(e.getId(), e.getItemCode(), e.getItemName(), e.getSpec(),
                "", e.getUnit(), e.getMedListCodg(), e.getPrice(), e.getPrevYbCode(), e.getYbMapEffTime())));
    }

    /** 左栏回显医保名称与有效性: 按已对照医保码批量查标准字典(一页一次),
     *  便于直接肉眼核对对照是否正确; 医保码已作废/过期/删除的行标记为对照失效, 需重新对照 */
    private IPage<Map<String, Object>> enrichYbInfo(String catalog, IPage<Map<String, Object>> pg) {
        String stdKey = stdKeyOf(catalog);
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> m : pg.getRecords()) {
            String c = str(m.get("ybCode"));
            if (!c.isEmpty()) {
                codes.add(c);
            }
        }
        Map<String, Map<String, Object>> info = codes.isEmpty()
                ? new LinkedHashMap<>() : stdMaintain.infoByCode(stdKey, codes);
        for (Map<String, Object> m : pg.getRecords()) {
            String c = str(m.get("ybCode"));
            if (c.isEmpty()) {
                m.put("ybName", null);
                m.put("ybValid", null);
                m.put("ybInvalidReason", null);
                continue;
            }
            Map<String, Object> si = info.get(c);
            if (si == null) {
                /* 标准字典中已无此医保码: 目录行被删除, 必须重新对照 */
                m.put("ybName", null);
                m.put("ybValid", false);
                m.put("ybInvalidReason", REASON_DELETED);
            } else {
                m.put("ybName", si.get("name"));
                m.put("ybValid", si.get("valid"));
                m.put("ybInvalidReason", si.get("invalidReason"));
            }
        }
        return pg;
    }

    /** 导出对照结果行数上限(按当前筛选一次性导出, 不分页) */
    private static final long EXPORT_MAX = 200000L;
    private static final DateTimeFormatter EFF_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] EXPORT_HEAD = {"院内码", "名称", "规格/型号", "单位", "厂家", "单价",
            "医保码", "医保名称", "对照有效性", "变更前医保码", "对照生效时间", "对照状态"};

    /** 导出对照结果: 复用列表查询与医保名称回显, 返回 {head, rows, total} */
    public Map<String, Object> exportRows(String catalog, Integer mapped, String keyword, String itemType) {
        stdKeyOf(catalog);
        IPage<Map<String, Object>> pg = items(catalog, 1, EXPORT_MAX, mapped, keyword, itemType);
        List<List<String>> head = new ArrayList<>();
        for (String h : EXPORT_HEAD) {
            head.add(Collections.singletonList(h));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : pg.getRecords()) {
            List<Object> r = new ArrayList<>();
            r.add(str(m.get("code")));
            r.add(str(m.get("name")));
            r.add(str(m.get("spec")));
            r.add(str(m.get("unit")));
            r.add(str(m.get("manufacturer")));
            r.add(m.get("price"));
            r.add(str(m.get("ybCode")));
            r.add(str(m.get("ybName")));
            r.add(validityText(m));
            r.add(str(m.get("prevYbCode")));
            Object eff = m.get("mapEffTime");
            r.add(eff instanceof LocalDateTime ? ((LocalDateTime) eff).format(EFF_FMT) : "");
            r.add(Boolean.TRUE.equals(m.get("mapped")) ? "已对照" : "未对照");
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", pg.getTotal());
        return out;
    }

    /** 导出用的对照有效性文本: 未对照为 —, 失效则写明原因(医保目录已作废/已过期/已删除) */
    private static String validityText(Map<String, Object> m) {
        if (!Boolean.TRUE.equals(m.get("mapped"))) {
            return "—";
        }
        Object reason = m.get("ybInvalidReason");
        return reason == null ? "有效" : "医保目录" + reason;
    }

    /** mapped=1 仅已对照, mapped=0 仅未对照, mapped=2 仅对照失效(医保码已作废/过期/删除), null 全部 */
    private <T> void applyMapped(LambdaQueryChainWrapper<T> q, Integer mapped,
                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, String> col,
                                 String catalog) {
        if (mapped == null) {
            return;
        }
        if (mapped == 1) {
            q.isNotNull(col).ne(col, "");
        } else if (mapped == 2) {
            /* 已对照但标准字典中已无有效行: NOT EXISTS 覆盖"作废/过期"与"被删除"两种情况 */
            q.isNotNull(col).ne(col, "")
                    .notExists(stdMaintain.validExistsSql(stdKeyOf(catalog), ybColRef(catalog)));
        } else {
            q.and(w -> w.isNull(col).or().eq(col, ""));
        }
    }

    private <T> IPage<Map<String, Object>> mapView(IPage<T> src, java.util.function.Function<T, HospItem> fn) {
        Page<Map<String, Object>> out = new Page<>(src.getCurrent(), src.getSize(), src.getTotal());
        List<Map<String, Object>> recs = new ArrayList<>();
        for (T e : src.getRecords()) {
            recs.add(toView(fn.apply(e)));
        }
        out.setRecords(recs);
        return out;
    }

    private Map<String, Object> toView(HospItem h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.id);
        m.put("code", h.code);
        m.put("name", h.name);
        m.put("spec", h.spec);
        m.put("manufacturer", h.manufacturer);
        m.put("unit", h.unit);
        m.put("ybCode", h.ybCode);
        m.put("price", h.price);
        m.put("prevYbCode", h.prevYbCode);
        m.put("mapEffTime", h.mapEffTime);
        m.put("mapped", StringUtils.hasText(h.ybCode));
        return m;
    }

    /** 院内条目归一载体 */
    private static class HospItem {
        Long id;
        String code;
        String name;
        String spec;
        String manufacturer;
        String unit;
        String ybCode;
        BigDecimal price;
        String prevYbCode;
        LocalDateTime mapEffTime;
    }

    private HospItem hosp(Long id, String code, String name, String spec,
                          String manufacturer, String unit, String ybCode, BigDecimal price,
                          String prevYbCode, LocalDateTime mapEffTime) {
        HospItem h = new HospItem();
        h.id = id;
        h.code = code;
        h.name = name;
        h.spec = spec;
        h.manufacturer = manufacturer;
        h.unit = unit;
        h.ybCode = ybCode;
        h.price = price;
        h.prevYbCode = prevYbCode;
        h.mapEffTime = mapEffTime;
        return h;
    }

    /** 按 id 加载院内条目归一载体(供候选/自动对照使用) */
    private HospItem loadHosp(String catalog, Long id) {
        if (CAT_DRUG.equals(catalog)) {
            HisDrugCatalog e = drugService.getById(id);
            return e == null ? null : hosp(e.getId(), e.getDrugCode(), e.getGenericName(), e.getSpec(),
                    e.getManufacturer(), "", e.getYbDrugCode(), e.getRetailPrice(), e.getPrevYbCode(), e.getYbMapEffTime());
        }
        if (CAT_CONS.equals(catalog)) {
            HisConsCatalog e = consService.getById(id);
            return e == null ? null : hosp(e.getId(), e.getConsCode(), e.getName(), e.getSpecModel(),
                    e.getManufacturer(), "", e.getYbConsCode(), e.getChargePrice(), e.getPrevYbCode(), e.getYbMapEffTime());
        }
        HisChargeItem e = chargeService.getById(id);
        return e == null ? null : hosp(e.getId(), e.getItemCode(), e.getItemName(), e.getSpec(),
                "", e.getUnit(), e.getMedListCodg(), e.getPrice(), e.getPrevYbCode(), e.getYbMapEffTime());
    }

    /* ================= 候选打分 ================= */

    /** 打分候选: [{stdId,code,name,spec,extra,score,reasons}]。
     *  未传 keyword 时按院内条目名称自动推荐, 仅保留 score>=0.5;
     *  传 keyword 时为人工检索医保目录, 返回检索到的全部行(按置信度降序), 便于人工挑选。 */
    public List<Map<String, Object>> candidates(String catalog, Long itemId, String keyword, int limit) {
        String stdKey = stdKeyOf(catalog);
        HospItem h = loadHosp(catalog, itemId);
        if (h == null) {
            throw new BizException(404, "院内条目不存在: " + itemId);
        }
        boolean manual = StringUtils.hasText(keyword);
        String base = manual ? keyword : h.name;
        List<Map<String, Object>> stdRows = fetchStdCandidates(stdKey, base, limit * 2);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : stdRows) {
            CatalogMapMatcher.Result r = scoreStdRow(catalog, h, row);
            if (!manual && r.getScore() < 0.5) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stdId", row.get("id"));
            m.put("code", row.get("code"));
            m.put("name", row.get("name"));
            m.put("spec", row.get("spec"));
            m.put("extra", row.get("extra"));
            m.put("score", round2(r.getScore()));
            m.put("reasons", r.getReasons());
            out.add(m);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> -((Number) m.get("score")).doubleValue())
                .thenComparing(m -> String.valueOf(m.get("code"))));
        List<Map<String, Object>> fin = out.size() > limit ? out.subList(0, limit) : out;
        /* 附带标准字典整行, 供右栏展示更多列(地方码/内涵/除外/支付标准等) */
        for (Map<String, Object> m : fin) {
            Object sid = m.get("stdId");
            if (sid instanceof Number) {
                m.put("row", stdMaintain.row(stdKey, ((Number) sid).longValue()));
            }
        }
        /* 标注候选医保码在标准字典中的有效性: 已作废/已过期的编码不应再用于新对照 */
        annotateStdValid(stdKey, fin);
        return fin;
    }

    /** 批量标注标准字典行的有效性: 写入 valid / invalidReason(已作废·已过期·已删除) */
    private void annotateStdValid(String stdKey, List<Map<String, Object>> rows) {
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            String c = str(m.get("code"));
            if (!c.isEmpty()) {
                codes.add(c);
            }
        }
        if (codes.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> info = stdMaintain.infoByCode(stdKey, codes);
        for (Map<String, Object> m : rows) {
            Map<String, Object> si = info.get(str(m.get("code")));
            boolean valid = si != null && Boolean.TRUE.equals(si.get("valid"));
            m.put("valid", valid);
            m.put("invalidReason", valid ? null : (si == null ? REASON_DELETED : si.get("invalidReason")));
        }
    }

    /** 剔除标准字典中已作废/已过期的行(自动对照不得写入失效医保码) */
    private List<Map<String, Object>> dropInvalidStd(String stdKey, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        annotateStdValid(stdKey, rows);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : rows) {
            if (!Boolean.FALSE.equals(m.get("valid"))) {
                out.add(m);
            }
        }
        return out;
    }

    /** 按目录适配"规格/厂家"参与打分的字段: 耗材规格不可比(用分类), 医疗服务用单位比 prc_unit */
    private CatalogMapMatcher.Result scoreStdRow(String catalog, HospItem h, Map<String, Object> row) {
        String stdName = str(row.get("name"));
        String stdSpec;
        String stdMfr;
        String hospSpec;
        String hospMfr;
        if (CAT_CONS.equals(catalog)) {
            stdSpec = "";
            stdMfr = str(row.get("extra"));
            hospSpec = "";
            hospMfr = h.manufacturer;
        } else if (CAT_CHARGE.equals(catalog)) {
            stdSpec = str(row.get("spec"));
            stdMfr = "";
            hospSpec = h.unit;
            hospMfr = "";
        } else {
            stdSpec = str(row.get("spec"));
            stdMfr = str(row.get("extra"));
            hospSpec = h.spec;
            hospMfr = h.manufacturer;
        }
        return CatalogMapMatcher.score(h.name, hospSpec, hospMfr, stdName, stdSpec, stdMfr);
    }

    /** 拉取标准字典候选行(全名 + 半名前缀两次检索取并集, 覆盖"院内名含标准名"与反向两种情况) */
    private List<Map<String, Object>> fetchStdCandidates(String stdKey, String base, int limit) {
        Map<Object, Map<String, Object>> byId = new LinkedHashMap<>();
        List<String> attempts = new ArrayList<>();
        if (StringUtils.hasText(base)) {
            attempts.add(base);
            if (base.length() > 4) {
                attempts.add(base.substring(0, base.length() / 2));
            }
        }
        for (String kw : attempts) {
            Map<String, Object> p = stdMaintain.page(stdKey, kw, 1, limit);
            Object recs = p.get("records");
            if (recs instanceof List) {
                for (Object o : (List<?>) recs) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) o;
                        byId.putIfAbsent(row.get("id"), row);
                    }
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /* ================= 写入对照 ================= */

    /** 人工/预览确认写入, 返回写入条数(幂等: 重复对照即更新) */
    public int apply(String catalog, List<CatalogMapApplyReq.Item> items) {
        return apply(catalog, items, HisYbMapLog.SRC_MANUAL, false);
    }

    /** 写入对照并留痕: src=manual人工/auto自动; 自动对照仅作用于未对照条目, 不受 force 限制 */
    public int apply(String catalog, List<CatalogMapApplyReq.Item> items, String src) {
        return apply(catalog, items, src, true);
    }

    /** 写入对照并留痕: force=false 时, 已对照且目标医保码不同的条目视为"变更对照", 直接拒绝(需前端二次确认后带 force=true) */
    public int apply(String catalog, List<CatalogMapApplyReq.Item> items, String src, boolean force) {
        String stdKey = stdKeyOf(catalog);
        int applied = 0;
        for (CatalogMapApplyReq.Item it : items) {
            if (it == null || it.getItemId() == null || it.getStdId() == null) {
                continue;
            }
            Map<String, Object> std = stdMaintain.row(stdKey, it.getStdId());
            /* 人工对照不得写入已作废/已删除的医保码(自动对照已在候选阶段剔除) */
            if (HisYbMapLog.SRC_MANUAL.equals(src)) {
                guardStdValid(catalog, std);
            }
            if (applyOne(catalog, it.getItemId(), std, src, force)) {
                applied++;
            }
        }
        return applied;
    }

    /** 目录 -> 标准字典主码列名 */
    private String stdCodeCol(String catalog) {
        if (CAT_DRUG.equals(catalog)) {
            return "drug_code";
        }
        return CAT_CONS.equals(catalog) ? "cons_code" : "nat_item_code";
    }

    /** 目标医保码在标准字典中已作废/过期/删除时禁止写入对照, 避免把院内条目对到失效编码上 */
    private void guardStdValid(String catalog, Map<String, Object> std) {
        String code = str(std.get(stdCodeCol(catalog)));
        if (code.isEmpty()) {
            return;
        }
        Map<String, Object> si = stdMaintain.infoByCode(stdKeyOf(catalog), Collections.singletonList(code)).get(code);
        if (si == null) {
            throw new BizException(409, "医保码 " + code + " 已从标准字典删除, 不能用于对照, 请另选有效的医保项目");
        }
        if (!Boolean.TRUE.equals(si.get("valid"))) {
            throw new BizException(409, "医保码 " + code + " 在标准字典中" + si.get("invalidReason")
                    + ", 不能用于对照, 请另选有效的医保项目");
        }
    }

    /** 已对照条目换成不同医保码 => 变更对照, 必须显式确认, 避免误操作覆盖既有对照 */
    private void guardChange(String catalog, boolean force, String oldCode, String newCode, String itemName) {
        if (force || oldCode == null || oldCode.isEmpty() || oldCode.equals(newCode)) {
            return;
        }
        String oldName = stdMaintain.namesByCode(stdKeyOf(catalog), Collections.singletonList(oldCode)).get(oldCode);
        throw new BizException(409, "院内条目「" + itemName + "」已对照医保码 " + oldCode
                + (oldName == null || oldName.isEmpty() ? "" : "(" + oldName + ")") + ", 变更对照需二次确认");
    }

    /** 单条写入: 主对照码必写, 附带字段仅空才回填, 并写溯源三件套;
     *  对照码发生变化时记录 prev_yb_code + yb_map_eff_time 并写变更留痕;
     *  已对照条目改码需 force=true(前端二次确认), 否则拒绝写入。 */
    private boolean applyOne(String catalog, Long itemId, Map<String, Object> std, String src, boolean force) {
        LocalDateTime now = LocalDateTime.now();
        if (CAT_DRUG.equals(catalog)) {
            HisDrugCatalog e = drugService.getById(itemId);
            if (e == null) {
                return false;
            }
            String old = str(e.getYbDrugCode());
            String neu = str(std.get("drug_code"));
            guardChange(catalog, force, old, neu, e.getGenericName());
            e.setYbDrugCode(neu);
            fillIfEmpty(e::setDrugStdCode, e.getDrugStdCode(), str(std.get("drug_std_code")));
            fillIfEmpty(e::setApprovalNo, e.getApprovalNo(), str(std.get("approval_no")));
            fillIfEmpty(e::setManufacturer, e.getManufacturer(), str(std.get("drug_entp")));
            fillIfEmpty(e::setSpec, e.getSpec(), str(std.get("act_spec")));
            fillIfEmpty(e::setChrgitmLv, e.getChrgitmLv(), str(std.get("chrgitm_lv")));
            fillIfEmpty(e::setPayStdPrep, e.getPayStdPrep(), str(std.get("pay_std_prep")));
            fillIfEmpty(e::setTradeName, e.getTradeName(), str(std.get("trade_name")));
            fillIfEmpty(e::setMktHolder, e.getMktHolder(), str(std.get("mkt_holder")));
            e.setSrcType("drug");
            e.setSrcCode(str(std.get("drug_code")));
            e.setSrcDoc(firstNonEmpty(str(std.get("src_doc")), "湖北省医保药品(西药、中成药)编码数据库"));
            boolean changed = !neu.equals(old);
            if (changed) {
                e.setPrevYbCode(old.isEmpty() ? null : old);
                e.setYbMapEffTime(now);
            }
            boolean ok = drugService.updateById(e);
            if (ok && changed) {
                logChange(catalog, e.getId(), e.getDrugCode(), e.getGenericName(), old, neu,
                        old.isEmpty() ? HisYbMapLog.TYPE_MAP : HisYbMapLog.TYPE_CHANGE, null, src);
            }
            return ok;
        }
        if (CAT_CONS.equals(catalog)) {
            HisConsCatalog e = consService.getById(itemId);
            if (e == null) {
                return false;
            }
            String old = str(e.getYbConsCode());
            String neu = str(std.get("cons_code"));
            guardChange(catalog, force, old, neu, e.getName());
            e.setYbConsCode(neu);
            fillIfEmpty(e::setCat1, e.getCat1(), str(std.get("cat1")));
            fillIfEmpty(e::setCat2, e.getCat2(), str(std.get("cat2")));
            fillIfEmpty(e::setCat3, e.getCat3(), str(std.get("cat3")));
            fillIfEmpty(e::setMaterial, e.getMaterial(), str(std.get("material")));
            fillIfEmpty(e::setFeature, e.getFeature(), str(std.get("feature")));
            fillIfEmpty(e::setManufacturer, e.getManufacturer(), str(std.get("cons_entp")));
            fillIfEmpty(e::setRegCertNo, e.getRegCertNo(), str(std.get("reg_cert_no")));
            fillIfEmpty(e::setPayStd, e.getPayStd(), str(std.get("pay_std")));
            e.setSrcType("consumable");
            e.setSrcCode(str(std.get("cons_code")));
            e.setSrcDoc(firstNonEmpty(str(std.get("src_doc")), "湖北省医用耗材(20位)编码数据库"));
            boolean changed = !neu.equals(old);
            if (changed) {
                e.setPrevYbCode(old.isEmpty() ? null : old);
                e.setYbMapEffTime(now);
            }
            boolean ok = consService.updateById(e);
            if (ok && changed) {
                logChange(catalog, e.getId(), e.getConsCode(), e.getName(), old, neu,
                        old.isEmpty() ? HisYbMapLog.TYPE_MAP : HisYbMapLog.TYPE_CHANGE, null, src);
            }
            return ok;
        }
        HisChargeItem e = chargeService.getById(itemId);
        if (e == null) {
            return false;
        }
        String old = str(e.getMedListCodg());
        String neu = str(std.get("nat_item_code"));
        guardChange(catalog, force, old, neu, e.getItemName());
        e.setMedListCodg(neu);
        e.setMedChrgitmType("02");
        fillIfEmpty(e::setNatItemCode, e.getNatItemCode(), str(std.get("nat_item_code")));
        fillIfEmpty(e::setLocItemCode, e.getLocItemCode(), str(std.get("loc_item_code")));
        fillIfEmpty(e::setUnit, e.getUnit(), str(std.get("prc_unit")));
        fillIfEmpty(e::setItemContent, e.getItemContent(), str(std.get("item_connotation")));
        fillIfEmpty(e::setItemExcluded, e.getItemExcluded(), str(std.get("item_excluded")));
        e.setSrcType("med_service");
        e.setSrcCode(str(std.get("nat_item_code")));
        e.setSrcDoc(firstNonEmpty(str(std.get("src_doc")), "湖北省医疗服务项目编码数据库"));
        boolean changed = !neu.equals(old);
        if (changed) {
            e.setPrevYbCode(old.isEmpty() ? null : old);
            e.setYbMapEffTime(now);
        }
        boolean ok = chargeService.updateById(e);
        if (ok && changed) {
            logChange(catalog, e.getId(), e.getItemCode(), e.getItemName(), old, neu,
                    old.isEmpty() ? HisYbMapLog.TYPE_MAP : HisYbMapLog.TYPE_CHANGE, null, src);
        }
        return ok;
    }

    /** 写对照变更留痕(新增/变更/清除均记一行) */
    private void logChange(String catalog, Long itemId, String itemCode, String itemName,
                           String oldCode, String newCode, String type, Double score, String src) {
        logChange(catalog, itemId, itemCode, itemName, oldCode, newCode, type, score, src, null);
    }

    /** 写对照变更留痕(带备注) */
    private void logChange(String catalog, Long itemId, String itemCode, String itemName,
                           String oldCode, String newCode, String type, Double score, String src, String memo) {
        HisYbMapLog rec = new HisYbMapLog();
        rec.setCatalogType(catalog);
        rec.setCatalogId(itemId);
        rec.setItemCode(itemCode);
        rec.setItemName(itemName);
        rec.setOldCode(StringUtils.hasText(oldCode) ? oldCode : null);
        rec.setNewCode(StringUtils.hasText(newCode) ? newCode : null);
        rec.setChangeType(type);
        rec.setScore(score);
        rec.setSrc(src);
        LoginUser lu = UserContext.get();
        if (lu != null) {
            rec.setOperator(lu.getUsername());
            rec.setOperatorName(lu.getRealName());
            rec.setOrgId(lu.getOrgId());
        }
        rec.setChangeTime(LocalDateTime.now());
        rec.setMemo(memo);
        ybMapLogMapper.insert(rec);
    }

    /* ================= 批量自动对照 ================= */

    /** 批量自动对照: dryRun 仅预览; 否则写入达阈值项。返回 {dryRun,threshold,matched,reviewed,skipped,preview} */
    public Map<String, Object> auto(String catalog, List<Long> itemIds, Double threshold, boolean dryRun) {
        String stdKey = stdKeyOf(catalog);
        double thr = threshold != null ? threshold : CatalogMapMatcher.DEFAULT_AUTO_THRESHOLD;
        List<HospItem> scope = loadUnmapped(catalog, itemIds);

        List<Map<String, Object>> preview = new ArrayList<>();
        int reviewed = 0;
        for (HospItem h : scope) {
            Map<String, Object> best = bestMatch(catalog, stdKey, h, thr);
            if (best == null) {
                reviewed++;
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", h.id);
            m.put("itemCode", h.code);
            m.put("itemName", h.name);
            m.put("stdId", best.get("stdId"));
            m.put("stdCode", best.get("code"));
            m.put("stdName", best.get("name"));
            m.put("score", best.get("score"));
            preview.add(m);
        }
        int skipped = 0;
        if (itemIds != null && !itemIds.isEmpty()) {
            skipped = itemIds.size() - scope.size();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("threshold", thr);
        out.put("matched", preview.size());
        out.put("reviewed", reviewed);
        out.put("skipped", skipped);
        out.put("preview", preview);
        if (dryRun) {
            return out;
        }
        // 落库: 逐条按预览配对写入
        List<CatalogMapApplyReq.Item> pairs = new ArrayList<>();
        for (Map<String, Object> m : preview) {
            CatalogMapApplyReq.Item it = new CatalogMapApplyReq.Item();
            it.setItemId(((Number) m.get("itemId")).longValue());
            it.setStdId(((Number) m.get("stdId")).longValue());
            pairs.add(it);
        }
        int written = apply(catalog, pairs, HisYbMapLog.SRC_AUTO);
        out.put("matched", written);
        return out;
    }

    /** 取未对照条目(可选 id 子集); 已对照/不存在的 id 计入 skipped 由调用方计算 */
    private List<HospItem> loadUnmapped(String catalog, List<Long> itemIds) {
        List<HospItem> out = new ArrayList<>();
        if (CAT_DRUG.equals(catalog)) {
            LambdaQueryChainWrapper<HisDrugCatalog> q = drugService.lambdaQuery()
                    .and(w -> w.isNull(HisDrugCatalog::getYbDrugCode).or().eq(HisDrugCatalog::getYbDrugCode, ""))
                    .in(itemIds != null && !itemIds.isEmpty(), HisDrugCatalog::getId, itemIds);
            for (HisDrugCatalog e : q.list()) {
                out.add(hosp(e.getId(), e.getDrugCode(), e.getGenericName(), e.getSpec(),
                        e.getManufacturer(), "", e.getYbDrugCode(), e.getRetailPrice(), e.getPrevYbCode(), e.getYbMapEffTime()));
            }
            return out;
        }
        if (CAT_CONS.equals(catalog)) {
            LambdaQueryChainWrapper<HisConsCatalog> q = consService.lambdaQuery()
                    .and(w -> w.isNull(HisConsCatalog::getYbConsCode).or().eq(HisConsCatalog::getYbConsCode, ""))
                    .in(itemIds != null && !itemIds.isEmpty(), HisConsCatalog::getId, itemIds);
            for (HisConsCatalog e : q.list()) {
                out.add(hosp(e.getId(), e.getConsCode(), e.getName(), e.getSpecModel(),
                        e.getManufacturer(), "", e.getYbConsCode(), e.getChargePrice(), e.getPrevYbCode(), e.getYbMapEffTime()));
            }
            return out;
        }
        LambdaQueryChainWrapper<HisChargeItem> q = chargeService.lambdaQuery()
                .and(w -> w.isNull(HisChargeItem::getMedListCodg).or().eq(HisChargeItem::getMedListCodg, ""))
                .in(itemIds != null && !itemIds.isEmpty(), HisChargeItem::getId, itemIds);
        for (HisChargeItem e : q.list()) {
            out.add(hosp(e.getId(), e.getItemCode(), e.getItemName(), e.getSpec(),
                    "", e.getUnit(), e.getMedListCodg(), e.getPrice(), e.getPrevYbCode(), e.getYbMapEffTime()));
        }
        return out;
    }

    /** 单条目最优匹配(达阈值才返回), 返回候选行视图 */
    private Map<String, Object> bestMatch(String catalog, String stdKey, HospItem h, double thr) {
        /* 已作废/已过期的医保码不参与自动对照 */
        List<Map<String, Object>> rows = dropInvalidStd(stdKey, fetchStdCandidates(stdKey, h.name, 10));
        Map<String, Object> best = null;
        double bestScore = 0;
        for (Map<String, Object> row : rows) {
            CatalogMapMatcher.Result r = scoreStdRow(catalog, h, row);
            if (r.getScore() > bestScore) {
                bestScore = r.getScore();
                best = row;
            }
        }
        if (best == null || bestScore < thr) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stdId", best.get("id"));
        m.put("code", best.get("code"));
        m.put("name", best.get("name"));
        m.put("score", round2(bestScore));
        return m;
    }

    /* ================= 清除对照 ================= */

    /** 将指定院内条目的医保对照码置空, 返回处理条数; 清除同样留痕(prev 记原码, 生效时间置空) */
    public int clear(String catalog, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Long itemId : itemIds) {
            if (CAT_DRUG.equals(catalog)) {
                HisDrugCatalog e = drugService.getById(itemId);
                if (e == null || !StringUtils.hasText(e.getYbDrugCode())) {
                    continue;
                }
                String old = e.getYbDrugCode();
                boolean ok = drugService.update(null, new LambdaUpdateWrapper<HisDrugCatalog>()
                        .set(HisDrugCatalog::getYbDrugCode, null)
                        .set(HisDrugCatalog::getPrevYbCode, old)
                        .set(HisDrugCatalog::getYbMapEffTime, null)
                        .eq(HisDrugCatalog::getId, itemId));
                if (ok) {
                    logChange(catalog, itemId, e.getDrugCode(), e.getGenericName(), old, null,
                            HisYbMapLog.TYPE_CLEAR, null, HisYbMapLog.SRC_MANUAL);
                    n++;
                }
            } else if (CAT_CONS.equals(catalog)) {
                HisConsCatalog e = consService.getById(itemId);
                if (e == null || !StringUtils.hasText(e.getYbConsCode())) {
                    continue;
                }
                String old = e.getYbConsCode();
                boolean ok = consService.update(null, new LambdaUpdateWrapper<HisConsCatalog>()
                        .set(HisConsCatalog::getYbConsCode, null)
                        .set(HisConsCatalog::getPrevYbCode, old)
                        .set(HisConsCatalog::getYbMapEffTime, null)
                        .eq(HisConsCatalog::getId, itemId));
                if (ok) {
                    logChange(catalog, itemId, e.getConsCode(), e.getName(), old, null,
                            HisYbMapLog.TYPE_CLEAR, null, HisYbMapLog.SRC_MANUAL);
                    n++;
                }
            } else {
                stdKeyOf(catalog);
                HisChargeItem e = chargeService.getById(itemId);
                if (e == null || !StringUtils.hasText(e.getMedListCodg())) {
                    continue;
                }
                String old = e.getMedListCodg();
                boolean ok = chargeService.update(null, new LambdaUpdateWrapper<HisChargeItem>()
                        .set(HisChargeItem::getMedListCodg, null)
                        .set(HisChargeItem::getPrevYbCode, old)
                        .set(HisChargeItem::getYbMapEffTime, null)
                        .eq(HisChargeItem::getId, itemId));
                if (ok) {
                    logChange(catalog, itemId, e.getItemCode(), e.getItemName(), old, null,
                            HisYbMapLog.TYPE_CLEAR, null, HisYbMapLog.SRC_MANUAL);
                    n++;
                }
            }
        }
        return n;
    }

    /* ================= 变更留痕查询 ================= */

    /** 对照变更留痕分页: catalog/itemId 可选过滤; kw 匹配院内码/院内名/医保码/医保名称任一;
     *  start/end 为变更日期(含两端); 按变更时间倒序 */
    public IPage<HisYbMapLog> logs(String catalog, Long itemId, String kw, LocalDate start, LocalDate end, long page, long size) {
        LambdaQueryWrapper<HisYbMapLog> q = new LambdaQueryWrapper<HisYbMapLog>()
                .eq(StringUtils.hasText(catalog), HisYbMapLog::getCatalogType, catalog)
                .eq(itemId != null, HisYbMapLog::getCatalogId, itemId)
                .ge(start != null, HisYbMapLog::getChangeTime, start == null ? null : start.atStartOfDay())
                .lt(end != null, HisYbMapLog::getChangeTime, end == null ? null : end.plusDays(1).atStartOfDay())
                .orderByDesc(HisYbMapLog::getChangeTime)
                .orderByDesc(HisYbMapLog::getId);
        if (StringUtils.hasText(kw)) {
            final String k = kw.trim();
            final List<String> stdCodes = stdCodesByName(catalog, k);
            q.and(w -> {
                w.like(HisYbMapLog::getItemCode, k).or().like(HisYbMapLog::getItemName, k)
                        .or().like(HisYbMapLog::getOldCode, k).or().like(HisYbMapLog::getNewCode, k);
                if (!stdCodes.isEmpty()) {
                    w.or().in(HisYbMapLog::getOldCode, stdCodes).or().in(HisYbMapLog::getNewCode, stdCodes);
                }
            });
        }
        return ybMapLogMapper.selectPage(new Page<>(page, size), q);
    }

    /** 按医保名称反查医保码: catalog 指定时只查对应标准字典, 否则三本都查(上限 200 防 IN 过长) */
    private List<String> stdCodesByName(String catalog, String kw) {
        List<String> codes = new ArrayList<>();
        if (StringUtils.hasText(catalog)) {
            codes.addAll(stdMaintain.codesByNameLike(stdKeyOf(catalog), kw, 200));
        } else {
            codes.addAll(stdMaintain.codesByNameLike(stdKeyOf(CAT_DRUG), kw, 200));
            codes.addAll(stdMaintain.codesByNameLike(stdKeyOf(CAT_CONS), kw, 200));
            codes.addAll(stdMaintain.codesByNameLike(stdKeyOf(CAT_CHARGE), kw, 200));
        }
        return codes;
    }

    /** 某时点生效的医保码: 取该时点(含)前最近一条留痕的 new_code(CLEAR 则为空);
     *  该时点前无留痕时回退当前行: 当前对照生效时间不晚于该时点则视为当时已对照(历史数据), 否则当时未对照。 */
    public Map<String, Object> codeAt(String catalog, Long itemId, LocalDateTime at) {
        stdKeyOf(catalog);
        HisYbMapLog rec = ybMapLogMapper.selectOne(new LambdaQueryWrapper<HisYbMapLog>()
                .eq(HisYbMapLog::getCatalogType, catalog)
                .eq(HisYbMapLog::getCatalogId, itemId)
                .le(at != null, HisYbMapLog::getChangeTime, at)
                .orderByDesc(HisYbMapLog::getChangeTime)
                .orderByDesc(HisYbMapLog::getId)
                .last("LIMIT 1"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalog", catalog);
        out.put("itemId", itemId);
        out.put("at", at);
        if (rec != null) {
            out.put("code", rec.getNewCode());
            out.put("basis", "log");
            out.put("changeType", rec.getChangeType());
            out.put("changeTime", rec.getChangeTime());
            return out;
        }
        HospItem h = loadHosp(catalog, itemId);
        if (h == null) {
            throw new BizException(404, "院内条目不存在: " + itemId);
        }
        boolean mappedThen = StringUtils.hasText(h.ybCode)
                && (h.mapEffTime == null || at == null || !h.mapEffTime.isAfter(at));
        out.put("code", mappedThen ? h.ybCode : null);
        out.put("basis", mappedThen ? "current" : "none");
        return out;
    }

    /** 修改对照生效时间(人工纠偏/补录历史生效时点), 留痕 EFF; 医保码本身不变 */
    public void updateEffTime(String catalog, Long itemId, LocalDateTime eff) {
        stdKeyOf(catalog);
        String memo = "对照生效时间调整为 " + eff;
        if (CAT_DRUG.equals(catalog)) {
            HisDrugCatalog e = drugService.getById(itemId);
            if (e == null) {
                throw new BizException(404, "院内条目不存在: " + itemId);
            }
            e.setYbMapEffTime(eff);
            drugService.updateById(e);
            logChange(catalog, itemId, e.getDrugCode(), e.getGenericName(), e.getYbDrugCode(), e.getYbDrugCode(),
                    HisYbMapLog.TYPE_EFF, null, HisYbMapLog.SRC_MANUAL, memo);
            return;
        }
        if (CAT_CONS.equals(catalog)) {
            HisConsCatalog e = consService.getById(itemId);
            if (e == null) {
                throw new BizException(404, "院内条目不存在: " + itemId);
            }
            e.setYbMapEffTime(eff);
            consService.updateById(e);
            logChange(catalog, itemId, e.getConsCode(), e.getName(), e.getYbConsCode(), e.getYbConsCode(),
                    HisYbMapLog.TYPE_EFF, null, HisYbMapLog.SRC_MANUAL, memo);
            return;
        }
        HisChargeItem e = chargeService.getById(itemId);
        if (e == null) {
            throw new BizException(404, "院内条目不存在: " + itemId);
        }
        e.setYbMapEffTime(eff);
        chargeService.updateById(e);
        logChange(catalog, itemId, e.getItemCode(), e.getItemName(), e.getMedListCodg(), e.getMedListCodg(),
                HisYbMapLog.TYPE_EFF, null, HisYbMapLog.SRC_MANUAL, memo);
    }

    /* ================= 工具 ================= */

    private static void fillIfEmpty(Consumer<String> setter, String current, String val) {
        if (!StringUtils.hasText(current) && StringUtils.hasText(val)) {
            setter.accept(val);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private static double round2(double d) {
        return Math.round(d * 100d) / 100d;
    }
}
