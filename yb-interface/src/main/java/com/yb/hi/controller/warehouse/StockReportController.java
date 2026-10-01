package com.yb.hi.controller.warehouse;

import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.MonthEndService;
import com.yb.hi.service.warehouse.StockLedgerService;
import com.yb.hi.service.warehouse.WarehouseAccessService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 库存账簿查询接口(批次D): 在 StockLedgerService 台账基础上输出 收发存/账簿(按记账标准 进价/零售 分离财务账金额与实物账数量)。
 * 读: scopeOrgId 机构隔离; 涉及具体药库的读沿用 requireWhIfPresent。
 */
@RestController
@RequestMapping("/api/warehouse/report")
public class StockReportController {

    private final StockLedgerService ledgerService;
    private final MonthEndService monthEndService;
    private final OrgAccessGuard guard;
    private final WarehouseAccessService access;

    public StockReportController(StockLedgerService ledgerService, MonthEndService monthEndService,
                                 OrgAccessGuard guard, WarehouseAccessService access) {
        this.ledgerService = ledgerService;
        this.monthEndService = monthEndService;
        this.guard = guard;
        this.access = access;
    }

    /** 进销存台账(原样, 供保管员账簿/明细) */
    @GetMapping("/ledger")
    public R<Map<String, Object>> ledger(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Long warehouseId,
                                         @RequestParam(required = false) Long drugCatalogId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        requireWhIfPresent(warehouseId);
        return R.ok(ledgerService.ledger(guard.scopeOrgId(orgId), warehouseId, drugCatalogId, beginDate, endDate));
    }

    /** 账簿/收发存(按记账标准汇总财务账金额 + 实物账数量) */
    @GetMapping("/stock-book")
    public R<Map<String, Object>> stockBook(@RequestParam(required = false) Long orgId,
                                            @RequestParam(required = false) Long warehouseId,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginDate,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                            @RequestParam(defaultValue = "1") Integer standard) {
        requireWhIfPresent(warehouseId);
        return R.ok(monthEndService.bookReport(guard.scopeOrgId(orgId), warehouseId, beginDate, endDate, standard));
    }

    private void requireWhIfPresent(Long warehouseId) {
        if (warehouseId != null) {
            access.requireWarehouseAccess(warehouseId);
        }
    }
}
