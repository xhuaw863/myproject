package com.yb.hi.controller.warehouse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.warehouse.MonthEndReq;
import com.yb.hi.entity.warehouse.HisStockMonthEnd;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.MonthEndService;
import com.yb.hi.service.warehouse.WarehouseAccessService;
import org.springframework.web.bind.annotation.*;

/**
 * 库房月结接口(批次D): 月结 / 取消月结 / 月结记录查询。
 * 读: scopeOrgId 机构隔离; 写: requireLeadWrite(仅牵头管理员, 对齐药库口径); 涉及具体药库的读沿用 requireWhIfPresent。
 */
@RestController
@RequestMapping("/api/warehouse/month-end")
public class MonthEndController {

    private final MonthEndService service;
    private final OrgAccessGuard guard;
    private final WarehouseAccessService access;

    public MonthEndController(MonthEndService service, OrgAccessGuard guard, WarehouseAccessService access) {
        this.service = service;
        this.guard = guard;
        this.access = access;
    }

    @GetMapping("/page")
    public R<IPage<HisStockMonthEnd>> page(@RequestParam(required = false) Long orgId,
                                           @RequestParam(required = false) Long warehouseId,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "20") long size) {
        requireWhIfPresent(warehouseId);
        return R.ok(service.page(guard.scopeOrgId(orgId), warehouseId, status, page, size));
    }

    @PostMapping("/run")
    public R<HisStockMonthEnd> run(@RequestBody MonthEndReq req) {
        guard.requireLeadWrite();
        Long org = guard.scopeOrgId(req.getOrgId());
        // 牵头管理员未指定机构时回落到登录机构(org_id 非空约束)
        req.setOrgId(org != null ? org : guard.currentOrgId());
        requireWhIfPresent(req.getWarehouseId());
        return R.ok(service.monthEnd(req));
    }

    @PostMapping("/{id}/unmonth-end")
    public R<HisStockMonthEnd> unmonthEnd(@PathVariable Long id) {
        guard.requireLeadWrite();
        return R.ok(service.unmonthEnd(id));
    }

    private void requireWhIfPresent(Long warehouseId) {
        if (warehouseId != null) {
            access.requireWarehouseAccess(warehouseId);
        }
    }
}
