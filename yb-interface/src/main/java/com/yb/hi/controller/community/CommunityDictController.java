package com.yb.hi.controller.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.community.MedDictImportReq;
import com.yb.hi.dto.community.PriceAdjustReq;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.entity.community.HisConsCatalog;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.community.HisDictEditLog;
import com.yb.hi.entity.community.HisMedDict;
import com.yb.hi.entity.community.HisPriceAdjust;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.StdDictMaintainService;
import com.yb.hi.service.basedata.HisChargeItemService;
import com.yb.hi.service.community.CatalogMapService;
import com.yb.hi.service.community.CommunityDictImportService;
import com.yb.hi.service.community.CommunityDictEditLogService;
import com.yb.hi.service.community.CommunityPriceAdjustService;
import com.yb.hi.service.community.HisConsCatalogService;
import com.yb.hi.service.community.HisDrugCatalogService;
import com.yb.hi.service.community.HisMedDictService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 医共体统一字典(L2)管理接口: 药品/耗材/收费项目三目录 CRUD + 调价留痕 + 标准字典导入。
 * 读接口对已登录用户开放(菜单仅牵头机构可见); 写接口 requireLeadOrg 守卫——仅租户根机构(org_level=1)的 ADMIN 可维护。
 * 平台超管只读(不参与 L2 写)。
 */
@RestController
@RequestMapping("/api/community-dict")
public class CommunityDictController {

    private final HisDrugCatalogService drugService;
    private final HisConsCatalogService consService;
    private final HisChargeItemService chargeService;
    private final HisMedDictService medDictService;
    private final CommunityPriceAdjustService adjustService;
    private final CommunityDictImportService importService;
    private final StdDictMaintainService stdMaintain;
    private final CatalogMapService mapService;
    private final CommunityDictEditLogService editLogService;
    private final OrgAccessGuard guard;

    public CommunityDictController(HisDrugCatalogService drugService,
                                   HisConsCatalogService consService,
                                   HisChargeItemService chargeService,
                                   HisMedDictService medDictService,
                                   CommunityPriceAdjustService adjustService,
                                   CommunityDictImportService importService,
                                   StdDictMaintainService stdMaintain,
                                   CatalogMapService mapService,
                                   CommunityDictEditLogService editLogService,
                                   OrgAccessGuard guard) {
        this.drugService = drugService;
        this.consService = consService;
        this.chargeService = chargeService;
        this.medDictService = medDictService;
        this.adjustService = adjustService;
        this.importService = importService;
        this.stdMaintain = stdMaintain;
        this.mapService = mapService;
        this.editLogService = editLogService;
        this.guard = guard;
    }

    /** 医保名称回显: 收集本页已对照码批量回查标准字典(名称不落库, 字典改名后自动跟随), 与对照工作台 enrich 同机制 */
    private <T> void fillYbName(List<T> rows, String stdKey, Function<T, String> codeGetter, BiConsumer<T, String> nameSetter) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<String> codes = new ArrayList<>();
        for (T r : rows) {
            String c = codeGetter.apply(r);
            if (c != null && !c.isEmpty() && !codes.contains(c)) {
                codes.add(c);
            }
        }
        if (codes.isEmpty()) {
            return;
        }
        Map<String, String> names = stdMaintain.namesByCode(stdKey, codes);
        for (T r : rows) {
            String c = codeGetter.apply(r);
            if (c != null && !c.isEmpty()) {
                nameSetter.accept(r, names.get(c));
            }
        }
    }

    /** 医保码是否变化(null/空串/首尾空格归一) */
    private static boolean ybCodeChanged(String oldCode, String newCode) {
        String o = oldCode == null ? "" : oldCode.trim();
        String n = newCode == null ? "" : newCode.trim();
        return !o.equals(n);
    }

    /* ================= 药品目录 ================= */

    /** 按医保码回查标准字典的医保名称与医保甲乙分类(编码), 供三目录编辑弹窗显示与保存前不一致提示 */
    @GetMapping("/yb-info")
    public R<Map<String, Object>> ybInfo(@RequestParam String catalog, @RequestParam(required = false) String code) {
        return R.ok(mapService.ybClassInfoByCode(catalog, code));
    }

    @GetMapping("/drug/page")
    public R<IPage<HisDrugCatalog>> drugPage(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer status) {
        IPage<HisDrugCatalog> p = drugService.pageQuery(page, size, keyword, status);
        fillYbName(p.getRecords(), "drug", HisDrugCatalog::getYbDrugCode, HisDrugCatalog::setYbName);
        return R.ok(p);
    }

    @GetMapping("/drug/{id}")
    public R<HisDrugCatalog> drugGet(@PathVariable Long id) {
        HisDrugCatalog d = drugService.getById(id);
        drugService.derivePackPrice(d);
        return R.ok(d);
    }

    @PostMapping("/drug")
    public R<Void> drugCreate(@RequestBody HisDrugCatalog e) {
        requireLeadOrg();
        e.setId(null);
        mapService.guardStdCode(CatalogMapService.CAT_DRUG, e.getYbDrugCode());
        // 甲乙丙类以标准字典为准: 录入医保码即按 std_drug.chrgitm_lv 同步(归一为编码)
        if (StringUtils.hasText(e.getYbDrugCode())) {
            e.setChrgitmLv(mapService.drugChrgitmLvByCode(e.getYbDrugCode()));
        }
        drugService.save(e);
        editLogService.logCreate(CatalogMapService.CAT_DRUG, drugService.getById(e.getId()));
        mapService.logDictEditCodeChange(CatalogMapService.CAT_DRUG, e.getId(), e.getDrugCode(), e.getGenericName(), null, e.getYbDrugCode());
        return R.ok();
    }

    @PutMapping("/drug")
    public R<Void> drugUpdate(@RequestBody HisDrugCatalog e) {
        requireLeadOrg();
        HisDrugCatalog old = drugService.getById(e.getId());
        String oldCode = old == null ? null : old.getYbDrugCode();
        mapService.guardStdCode(CatalogMapService.CAT_DRUG, e.getYbDrugCode());
        if (ybCodeChanged(oldCode, e.getYbDrugCode())) {
            e.setPrevYbCode(StringUtils.hasText(oldCode) ? oldCode.trim() : null);
            e.setYbMapEffTime(LocalDateTime.now());
            // 甲乙丙类随医保码变更同步: 以新码对应 std_drug.chrgitm_lv 为准强制覆盖(无值则置空)
            e.setChrgitmLv(mapService.drugChrgitmLvByCode(e.getYbDrugCode()));
        }
        drugService.updateById(e);
        editLogService.logChanges(CatalogMapService.CAT_DRUG, old, drugService.getById(e.getId()), HisDictEditLog.SRC_EDIT);
        mapService.logDictEditCodeChange(CatalogMapService.CAT_DRUG, e.getId(), e.getDrugCode(), e.getGenericName(), oldCode, e.getYbDrugCode());
        return R.ok();
    }

    @DeleteMapping("/drug/{id}")
    public R<Void> drugDelete(@PathVariable Long id) {
        requireLeadOrg();
        drugService.removeById(id);
        return R.ok();
    }

    /* ================= 耗材目录 ================= */

    @GetMapping("/cons/page")
    public R<IPage<HisConsCatalog>> consPage(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer status) {
        IPage<HisConsCatalog> p = consService.pageQuery(page, size, keyword, status);
        fillYbName(p.getRecords(), "consumable", HisConsCatalog::getYbConsCode, HisConsCatalog::setYbName);
        return R.ok(p);
    }

    @GetMapping("/cons/{id}")
    public R<HisConsCatalog> consGet(@PathVariable Long id) {
        return R.ok(consService.getById(id));
    }

    @PostMapping("/cons")
    public R<Void> consCreate(@RequestBody HisConsCatalog e) {
        requireLeadOrg();
        e.setId(null);
        mapService.guardStdCode(CatalogMapService.CAT_CONS, e.getYbConsCode());
        consService.save(e);
        editLogService.logCreate(CatalogMapService.CAT_CONS, consService.getById(e.getId()));
        mapService.logDictEditCodeChange(CatalogMapService.CAT_CONS, e.getId(), e.getConsCode(), e.getName(), null, e.getYbConsCode());
        return R.ok();
    }

    @PutMapping("/cons")
    public R<Void> consUpdate(@RequestBody HisConsCatalog e) {
        requireLeadOrg();
        HisConsCatalog old = consService.getById(e.getId());
        String oldCode = old == null ? null : old.getYbConsCode();
        mapService.guardStdCode(CatalogMapService.CAT_CONS, e.getYbConsCode());
        if (ybCodeChanged(oldCode, e.getYbConsCode())) {
            e.setPrevYbCode(StringUtils.hasText(oldCode) ? oldCode.trim() : null);
            e.setYbMapEffTime(LocalDateTime.now());
        }
        consService.updateById(e);
        editLogService.logChanges(CatalogMapService.CAT_CONS, old, consService.getById(e.getId()), HisDictEditLog.SRC_EDIT);
        mapService.logDictEditCodeChange(CatalogMapService.CAT_CONS, e.getId(), e.getConsCode(), e.getName(), oldCode, e.getYbConsCode());
        return R.ok();
    }

    @DeleteMapping("/cons/{id}")
    public R<Void> consDelete(@PathVariable Long id) {
        requireLeadOrg();
        consService.removeById(id);
        return R.ok();
    }

    /* ================= 收费项目目录(L2 三档价) ================= */

    @GetMapping("/charge/page")
    public R<IPage<HisChargeItem>> chargePage(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String itemType) {
        IPage<HisChargeItem> p = chargeService.pageQuery(page, size, keyword, itemType);
        fillYbName(p.getRecords(), "med_service", HisChargeItem::getMedListCodg, HisChargeItem::setYbName);
        return R.ok(p);
    }

    @GetMapping("/charge/{id}")
    public R<HisChargeItem> chargeGet(@PathVariable Long id) {
        return R.ok(chargeService.getById(id));
    }

    @PostMapping("/charge")
    public R<Void> chargeCreate(@RequestBody HisChargeItem e) {
        requireLeadOrg();
        e.setId(null);
        mapService.guardStdCode(CatalogMapService.CAT_CHARGE, e.getMedListCodg());
        chargeService.save(e);
        editLogService.logCreate(CatalogMapService.CAT_CHARGE, chargeService.getById(e.getId()));
        mapService.logDictEditCodeChange(CatalogMapService.CAT_CHARGE, e.getId(), e.getItemCode(), e.getItemName(), null, e.getMedListCodg());
        return R.ok();
    }

    @PutMapping("/charge")
    public R<Void> chargeUpdate(@RequestBody HisChargeItem e) {
        requireLeadOrg();
        HisChargeItem old = chargeService.getById(e.getId());
        String oldCode = old == null ? null : old.getMedListCodg();
        mapService.guardStdCode(CatalogMapService.CAT_CHARGE, e.getMedListCodg());
        if (ybCodeChanged(oldCode, e.getMedListCodg())) {
            e.setPrevYbCode(StringUtils.hasText(oldCode) ? oldCode.trim() : null);
            e.setYbMapEffTime(LocalDateTime.now());
            // 甲乙丙类随医保码变更同步: 以新码对应 std_med_service.policy_flag 为准强制覆盖为 01/02/03(无值则置空), 与 applyOne 对照路径一致
            e.setChrgitmLv(mapService.chargeChrgitmLvByCode(e.getMedListCodg()));
        }
        chargeService.updateById(e);
        editLogService.logChanges(CatalogMapService.CAT_CHARGE, old, chargeService.getById(e.getId()), HisDictEditLog.SRC_EDIT);
        mapService.logDictEditCodeChange(CatalogMapService.CAT_CHARGE, e.getId(), e.getItemCode(), e.getItemName(), oldCode, e.getMedListCodg());
        return R.ok();
    }

    /** 统一字典字段级修改记录分页(三目录统一): catalog/关键字(编码/名称/字段)/日期段; 医保码变更另见对照工作台留痕 */
    @GetMapping("/edit-log/page")
    public R<IPage<HisDictEditLog>> editLogPage(@RequestParam(required = false) String catalog,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
                                                @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size) {
        return R.ok(editLogService.page(catalog, keyword, start, end, page, size));
    }

    @DeleteMapping("/charge/{id}")
    public R<Void> chargeDelete(@PathVariable Long id) {
        requireLeadOrg();
        chargeService.removeById(id);
        return R.ok();
    }

    /* ================= 用药字典(用法/用药频次, L2 单表 dict_type 区分) ================= */

    @GetMapping("/med-dict/page")
    public R<IPage<HisMedDict>> medDictPage(@RequestParam(required = false) String dictType,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Integer status) {
        return R.ok(medDictService.pageQuery(dictType, keyword, status, page, size));
    }

    @GetMapping("/med-dict/{id}")
    public R<HisMedDict> medDictGet(@PathVariable Long id) {
        return R.ok(medDictService.getById(id));
    }

    @PostMapping("/med-dict")
    public R<Void> medDictCreate(@RequestBody HisMedDict e) {
        requireLeadOrg();
        e.setId(null);
        medDictService.save(e);
        return R.ok();
    }

    @PutMapping("/med-dict")
    public R<Void> medDictUpdate(@RequestBody HisMedDict e) {
        requireLeadOrg();
        medDictService.updateById(e);
        return R.ok();
    }

    @DeleteMapping("/med-dict/{id}")
    public R<Void> medDictDelete(@PathVariable Long id) {
        requireLeadOrg();
        medDictService.removeById(id);
        return R.ok();
    }

    /** 从医保标准值域批量导入用法/频次(幂等: 同 dict_type+code 更新) */
    @PostMapping("/med-dict/import-batch")
    public R<Integer> medDictImport(@RequestBody MedDictImportReq req) {
        requireLeadOrg();
        if (req == null) {
            return R.ok(0);
        }
        return R.ok(medDictService.importBatch(req.getDictType(), req.getItems()));
    }

    /* ================= 调价留痕 ================= */

    @GetMapping("/price-adjust/page")
    public R<IPage<HisPriceAdjust>> adjustPage(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) String catalogType,
                                               @RequestParam(required = false) Long catalogId) {
        return R.ok(adjustService.pageQuery(page, size, catalogType, catalogId));
    }

    @PostMapping("/price-adjust")
    public R<Void> adjust(@RequestBody PriceAdjustReq req) {
        requireLeadOrg();
        adjustService.adjust(req);
        return R.ok();
    }

    /* ================= 标准字典导入(牵头机构) ================= */

    /** 浏览标准字典(供导入选择, 复用维护服务的通用分页) */
    @GetMapping("/std/page")
    public R<Map<String, Object>> stdPage(@RequestParam String dictKey,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return R.ok(stdMaintain.page(dictKey, keyword, page, size));
    }

    /** 标准字典行详情(带中文列名, 供导入前查看物价等完整内容) */
    @GetMapping("/std/detail")
    public R<List<Map<String, Object>>> stdDetail(@RequestParam String dictKey,
                                                  @RequestParam long stdId) {
        return R.ok(stdMaintain.detail(dictKey, stdId));
    }

    /** 药品导入预览: std_drug 行 -> 药品目录映射 */
    @GetMapping("/std-preview/drug")
    public R<HisDrugCatalog> previewDrug(@RequestParam long stdId) {
        requireLeadOrg();
        return R.ok(importService.previewDrug(stdId));
    }

    /** 耗材导入预览: std_consumable 行 -> 耗材目录映射 */
    @GetMapping("/std-preview/cons")
    public R<HisConsCatalog> previewCons(@RequestParam long stdId) {
        requireLeadOrg();
        return R.ok(importService.previewCons(stdId));
    }

    /** 收费项目导入预览: msi_hb/msi_nat/med_service 行 -> 收费项目映射 */
    @GetMapping("/std-preview/charge")
    public R<HisChargeItem> previewCharge(@RequestParam String dictKey, @RequestParam long stdId) {
        requireLeadOrg();
        return R.ok(importService.previewCharge(dictKey, stdId));
    }

    /** 药品导入落库(幂等: 同医保码更新) */
    @PostMapping("/drug/import")
    public R<Long> importDrug(@RequestBody HisDrugCatalog e) {
        requireLeadOrg();
        return R.ok(importService.importDrug(e));
    }

    /** 耗材导入落库(幂等: 同医保码更新) */
    @PostMapping("/cons/import")
    public R<Long> importCons(@RequestBody HisConsCatalog e) {
        requireLeadOrg();
        return R.ok(importService.importCons(e));
    }

    /** 收费项目导入落库 */
    @PostMapping("/charge/import")
    public R<Void> importCharge(@RequestBody HisChargeItem e) {
        requireLeadOrg();
        if (e.getId() == null) {
            chargeService.save(e);
        } else {
            chargeService.updateById(e);
        }
        return R.ok();
    }

    /** 批量导入标准字典全表为收费项目(幂等: 已存在院内编码跳过), 返回 total/imported/skipped */
    @PostMapping("/charge/import-batch")
    public R<Map<String, Object>> importChargeBatch(@RequestParam String dictKey) {
        requireLeadOrg();
        return R.ok(importService.importChargeBatch(dictKey));
    }

    /* ================= 守卫 ================= */

    /** 仅租户牵头机构(org_level=1)的 ADMIN 可维护 L2 目录; 平台超管只读不参与写。 */
    private void requireLeadOrg() {
        guard.requireLeadOrg("仅牵头机构管理员可维护医共体字典");
    }
}
