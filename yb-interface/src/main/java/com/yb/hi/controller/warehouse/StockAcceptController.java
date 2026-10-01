package com.yb.hi.controller.warehouse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.warehouse.HisStockAccept;
import com.yb.hi.entity.warehouse.HisStockBalance;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.DrugStockService;
import com.yb.hi.service.warehouse.StockAcceptService;
import com.yb.hi.service.warehouse.WarehouseAccessService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 财务验收接口(批次B): 待验收入库查询 + 单张/按供应商集中验收 + 平账记录查询 + 已验收入库单冲红。
 * 读: scopeOrgId 机构隔离(详情按ID直查校验归属); 写: requireLeadWrite(仅牵头管理员, 对齐药库出入库口径); 涉及具体药库的读沿用 requireWhIfPresent。
 */
@RestController
@RequestMapping("/api/warehouse/accept")
public class StockAcceptController {

    private final StockAcceptService service;
    private final DrugStockService drugStockService;
    private final OrgAccessGuard guard;
    private final WarehouseAccessService access;

    public StockAcceptController(StockAcceptService service, DrugStockService drugStockService,
                                 OrgAccessGuard guard, WarehouseAccessService access) {
        this.service = service;
        this.drugStockService = drugStockService;
        this.guard = guard;
        this.access = access;
    }

    /* ================= 查询 ================= */

    @GetMapping("/pending")
    public R<IPage<HisStockIn>> pending(@RequestParam(required = false) Long orgId,
                                        @RequestParam(required = false) Long warehouseId,
                                        @RequestParam(required = false) Long supplierId,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size) {
        requireWhIfPresent(warehouseId);
        return R.ok(service.pendingPage(guard.scopeOrgId(orgId), warehouseId, supplierId, page, size));
    }

    @GetMapping("/page")
    public R<IPage<HisStockAccept>> page(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.page(guard.scopeOrgId(orgId), status, page, size));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> detail = service.detail(id);
        checkOrgVisible(((HisStockAccept) detail.get("main")).getOrgId());
        return R.ok(detail);
    }

    @GetMapping("/balance")
    public R<IPage<HisStockBalance>> balance(@RequestParam(required = false) Long orgId,
                                             @RequestParam(required = false) Long warehouseId,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        requireWhIfPresent(warehouseId);
        return R.ok(service.balancePage(guard.scopeOrgId(orgId), warehouseId, page, size));
    }

    /* ================= 验收处理 ================= */

    @PostMapping("/single")
    public R<HisStockAccept> acceptSingle(@RequestParam Long stockInId,
                                          @RequestParam(required = false) Integer conclusion,
                                          @RequestParam(required = false) String remark) {
        guard.requireLeadWrite();
        return R.ok(service.acceptSingle(stockInId, conclusion, remark));
    }

    @PostMapping("/supplier")
    public R<HisStockAccept> acceptBySupplier(@RequestParam(required = false) Long orgId,
                                              @RequestParam Long supplierId,
                                              @RequestParam(required = false) Integer conclusion,
                                              @RequestParam(required = false) String remark) {
        guard.requireLeadWrite();
        return R.ok(service.acceptBySupplier(orgId, supplierId, conclusion, remark));
    }

    /** 已验收入库单冲红: 生成红字反向单并回退库存 */
    @PostMapping("/stock-in/{id}/red-reverse")
    public R<HisStockIn> redReverse(@PathVariable Long id) {
        guard.requireLeadWrite();
        return R.ok(drugStockService.redReverseStockIn(id));
    }

    /* ================= 守卫辅助 ================= */

    private void checkOrgVisible(Long orgId) {
        Long scoped = guard.scopeOrgId(null);
        if (scoped != null && !scoped.equals(orgId)) {
            throw new BizException(403, "仅可操作本机构单据");
        }
    }

    private void requireWhIfPresent(Long warehouseId) {
        if (warehouseId != null) {
            access.requireWarehouseAccess(warehouseId);
        }
    }
}
