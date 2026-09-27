package com.yb.hi.controller.warehouse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.warehouse.DrugPriceAdjustReq;
import com.yb.hi.entity.warehouse.HisDrugPriceAdjust;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.DrugPriceAdjustService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 药品调价单接口(药库/药房统一): 预览影响 → 建草稿单 → 生效 → 作废; 列表/详情。
 * 目录价格是医共体级(牵头统一维护): 写操作走 requireLeadWrite, 读为租户内共享(无需机构隔离)。
 */
@RestController
@RequestMapping("/api/his/price-adjust")
public class DrugPriceAdjustController {

    private final DrugPriceAdjustService priceAdjustService;
    private final OrgAccessGuard guard;

    public DrugPriceAdjustController(DrugPriceAdjustService priceAdjustService, OrgAccessGuard guard) {
        this.priceAdjustService = priceAdjustService;
        this.guard = guard;
    }

    @GetMapping("/page")
    public R<IPage<HisDrugPriceAdjust>> page(@RequestParam(required = false) Integer status,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(priceAdjustService.page(status, keyword, page, size));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(priceAdjustService.detail(id));
    }

    /** 预览调价影响(受影响药品现有进/零售价、在库量、在库金额变动), 不落库 */
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody DrugPriceAdjustReq req) {
        return R.ok(priceAdjustService.preview(req));
    }

    /** 建草稿调价单 */
    @PostMapping
    public R<HisDrugPriceAdjust> create(@RequestBody DrugPriceAdjustReq req) {
        guard.requireLeadWrite();
        return R.ok(priceAdjustService.create(req));
    }

    /** 生效调价单(更新目录当前价 + 在库零售价 + 写留痕) */
    @PostMapping("/{id}/confirm")
    public R<HisDrugPriceAdjust> confirm(@PathVariable Long id) {
        guard.requireLeadWrite();
        return R.ok(priceAdjustService.confirm(id));
    }

    /** 作废草稿调价单 */
    @PostMapping("/{id}/void")
    public R<HisDrugPriceAdjust> voidOne(@PathVariable Long id) {
        guard.requireLeadWrite();
        return R.ok(priceAdjustService.voidOrder(id));
    }
}
