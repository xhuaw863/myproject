package com.yb.hi.controller.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.community.MedDictImportReq;
import com.yb.hi.dto.community.PriceAdjustReq;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.entity.community.HisConsCatalog;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.community.HisMedDict;
import com.yb.hi.entity.community.HisPriceAdjust;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.service.StdDictMaintainService;
import com.yb.hi.service.basedata.HisChargeItemService;
import com.yb.hi.service.community.CommunityDictImportService;
import com.yb.hi.service.community.CommunityPriceAdjustService;
import com.yb.hi.service.community.HisConsCatalogService;
import com.yb.hi.service.community.HisDrugCatalogService;
import com.yb.hi.service.community.HisMedDictService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    private final SysOrgMapper orgMapper;

    public CommunityDictController(HisDrugCatalogService drugService,
                                   HisConsCatalogService consService,
                                   HisChargeItemService chargeService,
                                   HisMedDictService medDictService,
                                   CommunityPriceAdjustService adjustService,
                                   CommunityDictImportService importService,
                                   StdDictMaintainService stdMaintain,
                                   SysOrgMapper orgMapper) {
        this.drugService = drugService;
        this.consService = consService;
        this.chargeService = chargeService;
        this.medDictService = medDictService;
        this.adjustService = adjustService;
        this.importService = importService;
        this.stdMaintain = stdMaintain;
        this.orgMapper = orgMapper;
    }

    /* ================= 药品目录 ================= */

    @GetMapping("/drug/page")
    public R<IPage<HisDrugCatalog>> drugPage(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Integer status) {
        return R.ok(drugService.pageQuery(page, size, keyword, status));
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
        drugService.save(e);
        return R.ok();
    }

    @PutMapping("/drug")
    public R<Void> drugUpdate(@RequestBody HisDrugCatalog e) {
        requireLeadOrg();
        drugService.updateById(e);
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
        return R.ok(consService.pageQuery(page, size, keyword, status));
    }

    @GetMapping("/cons/{id}")
    public R<HisConsCatalog> consGet(@PathVariable Long id) {
        return R.ok(consService.getById(id));
    }

    @PostMapping("/cons")
    public R<Void> consCreate(@RequestBody HisConsCatalog e) {
        requireLeadOrg();
        e.setId(null);
        consService.save(e);
        return R.ok();
    }

    @PutMapping("/cons")
    public R<Void> consUpdate(@RequestBody HisConsCatalog e) {
        requireLeadOrg();
        consService.updateById(e);
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
        return R.ok(chargeService.pageQuery(page, size, keyword, itemType));
    }

    @GetMapping("/charge/{id}")
    public R<HisChargeItem> chargeGet(@PathVariable Long id) {
        return R.ok(chargeService.getById(id));
    }

    @PostMapping("/charge")
    public R<Void> chargeCreate(@RequestBody HisChargeItem e) {
        requireLeadOrg();
        e.setId(null);
        chargeService.save(e);
        return R.ok();
    }

    @PutMapping("/charge")
    public R<Void> chargeUpdate(@RequestBody HisChargeItem e) {
        requireLeadOrg();
        chargeService.updateById(e);
        return R.ok();
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
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (!Roles.ADMIN.equals(lu.getRole())) {
            throw new BizException(403, "仅牵头机构管理员可维护医共体字典");
        }
        Long orgId = lu.getOrgId();
        if (orgId == null) {
            throw new BizException(403, "当前用户未归属机构, 无法维护医共体字典");
        }
        SysOrg org = orgMapper.selectById(orgId);
        if (org == null || org.getOrgLevel() == null || org.getOrgLevel() != 1) {
            throw new BizException(403, "仅牵头机构(县级)可维护医共体字典");
        }
    }
}
