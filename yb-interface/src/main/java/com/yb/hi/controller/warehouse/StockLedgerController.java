package com.yb.hi.controller.warehouse;

import com.alibaba.excel.EasyExcel;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.ExportGuard;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.StockLedgerService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 进销存台账接口(药库/药房库存位通用, 只读): 期初+入-出=期末 对账查询 + xlsx 导出。
 * 读走 scopeOrgId(牵头跨机构, 非牵头锁本机构); warehouseId 传药房库存位 id 即为"药房台账/药房库存"。
 */
@RestController
@RequestMapping("/api/his/stock/ledger")
public class StockLedgerController {

    private final StockLedgerService ledgerService;
    private final OrgAccessGuard guard;

    public StockLedgerController(StockLedgerService ledgerService, OrgAccessGuard guard) {
        this.ledgerService = ledgerService;
        this.guard = guard;
    }

    @GetMapping
    public R<Map<String, Object>> ledger(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Long warehouseId,
                                         @RequestParam(required = false) Long drugCatalogId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return R.ok(ledgerService.ledger(resolveOrgId(orgId), warehouseId, drugCatalogId, startDate, endDate));
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) Long orgId,
                       @RequestParam(required = false) Long warehouseId,
                       @RequestParam(required = false) Long drugCatalogId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = ledgerService.exportLedger(resolveOrgId(orgId), warehouseId, drugCatalogId, startDate, endDate);
        String fname = "进销存台账_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        ExportGuard.checkRows((java.util.Collection<?>) data.get("rows"), "进销存台账");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("进销存台账")
                .doWrite((List<List<Object>>) data.get("rows"));
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
