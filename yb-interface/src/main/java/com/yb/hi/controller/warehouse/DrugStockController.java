package com.yb.hi.controller.warehouse;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.warehouse.StockInReq;
import com.yb.hi.dto.warehouse.StockOutReq;
import com.yb.hi.entity.warehouse.HisDrugStock;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockOut;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.DrugStockService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 药库接口: 库存查询/预警/导出 + 入库单/出库单全流程(草稿→确认/作废) + 出入库流水。
 * 读: 非牵头机构强制本院(scopeOrgId); 写: 仅牵头机构管理员(requireLeadWrite), orgId 空则默认当前机构。
 */
@RestController
@RequestMapping("/api/his/stock")
public class DrugStockController {

    private final DrugStockService service;
    private final OrgAccessGuard guard;

    public DrugStockController(DrugStockService service, OrgAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /* ================= 库存 ================= */

    /** 库存分页(keyword: 名称/编码/批号/厂家; lowStock=true 低库存) */
    @GetMapping("/page")
    public R<IPage<HisDrugStock>> page(@RequestParam(required = false) Long orgId,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Boolean lowStock,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.stockPage(guard.scopeOrgId(orgId), keyword, lowStock, page, size));
    }

    /** 低库存预警列表 */
    @GetMapping("/alert")
    public R<List<HisDrugStock>> alert(@RequestParam(required = false) Long orgId) {
        return R.ok(service.lowStockAlert(guard.scopeOrgId(orgId)));
    }

    /** 出入库流水(已确认单据明细, 确认时间倒序) */
    @GetMapping("/flow")
    public R<IPage<Map<String, Object>>> flow(@RequestParam(required = false) Long orgId,
                                              @RequestParam(required = false) Long drugCatalogId,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.stockFlow(guard.scopeOrgId(orgId), drugCatalogId, startDate, endDate, page, size));
    }

    /** 库存导出(xlsx): 与列表同口径 */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(required = false) Long orgId,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = service.exportStock(guard.scopeOrgId(orgId));
        String fname = "药品库存_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("药品库存")
                .doWrite((List<List<Object>>) data.get("rows"));
    }

    /* ================= 入库单 ================= */

    /** 创建入库单(草稿) */
    @PostMapping("/in")
    public R<HisStockIn> createIn(@RequestBody StockInReq req) {
        guard.requireLeadWrite();
        if (req.getOrgId() == null) {
            LoginUser lu = UserContext.get();
            if (lu != null) {
                req.setOrgId(lu.getOrgId());
            }
        }
        return R.ok(service.createStockIn(req));
    }

    /** 确认入库(幂等, 库存upsert) */
    @PostMapping("/in/{id}/confirm")
    public R<Void> confirmIn(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.confirmStockIn(id);
        return R.ok();
    }

    /** 作废入库单(仅草稿态) */
    @DeleteMapping("/in/{id}")
    public R<Void> voidIn(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.voidStockIn(id);
        return R.ok();
    }

    /** 入库单分页 */
    @GetMapping("/in/page")
    public R<IPage<HisStockIn>> inPage(@RequestParam(required = false) Long orgId,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) String startDate,
                                       @RequestParam(required = false) String endDate,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.stockInPage(guard.scopeOrgId(orgId), status, startDate, endDate, page, size));
    }

    /** 入库单详情(主表+明细) */
    @GetMapping("/in/{id}")
    public R<Map<String, Object>> inDetail(@PathVariable Long id) {
        return R.ok(service.stockInDetail(id));
    }

    /* ================= 出库单 ================= */

    /** 创建出库单(草稿): 明细可指定批次(drugStockId)或仅药品(确认时FIFO扣减) */
    @PostMapping("/out")
    public R<HisStockOut> createOut(@RequestBody StockOutReq req) {
        guard.requireLeadWrite();
        if (req.getOrgId() == null) {
            LoginUser lu = UserContext.get();
            if (lu != null) {
                req.setOrgId(lu.getOrgId());
            }
        }
        return R.ok(service.createStockOut(req));
    }

    /** 确认出库(幂等, 乐观锁扣减库存) */
    @PostMapping("/out/{id}/confirm")
    public R<Void> confirmOut(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.confirmStockOut(id);
        return R.ok();
    }

    /** 作废出库单(仅草稿态) */
    @DeleteMapping("/out/{id}")
    public R<Void> voidOut(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.voidStockOut(id);
        return R.ok();
    }

    /** 出库单分页 */
    @GetMapping("/out/page")
    public R<IPage<HisStockOut>> outPage(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String startDate,
                                         @RequestParam(required = false) String endDate,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.stockOutPage(guard.scopeOrgId(orgId), status, startDate, endDate, page, size));
    }

    /** 出库单详情(主表+明细) */
    @GetMapping("/out/{id}")
    public R<Map<String, Object>> outDetail(@PathVariable Long id) {
        return R.ok(service.stockOutDetail(id));
    }
}
