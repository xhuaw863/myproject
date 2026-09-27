package com.yb.hi.controller.warehouse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.warehouse.TransferReq;
import com.yb.hi.entity.warehouse.HisTransfer;
import com.yb.hi.entity.warehouse.HisWarehouseDef;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.TransferService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 库存调拨接口(药库/药房库存位之间): 建单 → 调出 → 调入; 列表/详情/库位/可用量。
 * 调拨限同机构: 读走 scopeOrgId, 写走 requireSelfOrgWrite。
 */
@RestController
@RequestMapping("/api/his/transfer")
public class TransferController {

    private final TransferService transferService;
    private final OrgAccessGuard guard;

    public TransferController(TransferService transferService, OrgAccessGuard guard) {
        this.transferService = transferService;
        this.guard = guard;
    }

    @GetMapping("/page")
    public R<IPage<HisTransfer>> page(@RequestParam(required = false) Long orgId,
                                      @RequestParam(required = false) Integer status,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size) {
        return R.ok(transferService.page(guard.scopeOrgId(orgId), status, keyword, page, size));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(transferService.detail(id));
    }

    /** 可选库位(药库+药房库存位) */
    @GetMapping("/locations")
    public R<List<HisWarehouseDef>> locations(@RequestParam(required = false) Long orgId) {
        return R.ok(transferService.locations(resolveOrgId(orgId)));
    }

    /** 调出库位某批次可用量 */
    @GetMapping("/available")
    public R<BigDecimal> available(@RequestParam(required = false) Long orgId,
                                   @RequestParam Long locationId,
                                   @RequestParam Long drugCatalogId,
                                   @RequestParam String batchNo) {
        return R.ok(transferService.availableQty(resolveOrgId(orgId), locationId, drugCatalogId, batchNo));
    }

    @PostMapping
    public R<HisTransfer> create(@RequestBody TransferReq req) {
        guard.requireSelfOrgWrite();
        req.setOrgId(resolveOrgId(req.getOrgId()));
        return R.ok(transferService.create(req));
    }

    @PostMapping("/{id}/ship")
    public R<HisTransfer> ship(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        return R.ok(transferService.ship(id));
    }

    @PostMapping("/{id}/receive")
    public R<HisTransfer> receive(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        return R.ok(transferService.receive(id));
    }

    @PostMapping("/{id}/void")
    public R<HisTransfer> voidOne(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        return R.ok(transferService.voidTransfer(id));
    }

    private Long resolveOrgId(Long requested) {
        Long oid = guard.scopeOrgId(requested);
        if (oid != null) {
            return oid;
        }
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getOrgId();
    }
}
