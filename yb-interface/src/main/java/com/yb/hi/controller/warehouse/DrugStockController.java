package com.yb.hi.controller.warehouse;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.warehouse.StockInReq;
import com.yb.hi.dto.warehouse.StockOutReq;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.warehouse.HisDrugStock;
import com.yb.hi.entity.warehouse.HisStockCheck;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockOut;
import com.yb.hi.entity.warehouse.HisWarehouseDef;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.DrugStockService;
import com.yb.hi.service.warehouse.WarehouseDefService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 药库接口: 库存查询/预警/导出 + 入库单/出库单全流程 + 出入库流水
 * + 药库定义维护 + 盘点全流程(创建→录入→确认/作废) + 机构开展药品目录(入库选药)。
 * 读: 非牵头机构强制本院(scopeOrgId); 写: 仅牵头机构管理员(requireLeadWrite), orgId 空则默认当前机构。
 */
@RestController
@RequestMapping("/api/his/stock")
public class DrugStockController {

    private final DrugStockService service;
    private final WarehouseDefService warehouseDefService;
    private final OrgAccessGuard guard;

    public DrugStockController(DrugStockService service, WarehouseDefService warehouseDefService, OrgAccessGuard guard) {
        this.service = service;
        this.warehouseDefService = warehouseDefService;
        this.guard = guard;
    }

    /* ================= 药库定义 ================= */

    /** 药库列表(下拉默认只返回启用; includeDisabled=true 返回全部含停用, 维护页用) */
    @GetMapping("/warehouse-def")
    public R<List<HisWarehouseDef>> warehouseDefList(@RequestParam(required = false) Long orgId,
                                                     @RequestParam(required = false) Boolean includeDisabled) {
        Long scoped = guard.scopeOrgId(orgId);
        return R.ok(Boolean.TRUE.equals(includeDisabled)
                ? warehouseDefService.listAll(scoped)
                : warehouseDefService.list(scoped));
    }

    /** 保存药库定义(id==null 新增, 否则编辑; code 同机构唯一) */
    @PostMapping("/warehouse-def")
    public R<HisWarehouseDef> saveWarehouseDef(@RequestBody HisWarehouseDef def) {
        guard.requireLeadWrite();
        if (def != null && def.getOrgId() == null) {
            LoginUser lu = UserContext.get();
            if (lu != null) {
                def.setOrgId(lu.getOrgId());
            }
        }
        return R.ok(warehouseDefService.save(def));
    }

    /** 启停药库(停用前校验该库无有量库存) */
    @PostMapping("/warehouse-def/{id}/toggle")
    public R<Void> toggleWarehouseDef(@PathVariable Long id, @RequestParam boolean enabled) {
        guard.requireLeadWrite();
        warehouseDefService.toggle(id, enabled);
        return R.ok();
    }

    /* ================= 盘点 ================= */

    /** 创建盘点单(快照指定药库所有 qty>0 库存批次为明细) */
    @PostMapping("/check")
    public R<HisStockCheck> createCheck(@RequestParam(required = false) Long orgId,
                                        @RequestParam Long warehouseId) {
        guard.requireLeadWrite();
        Long theOrg = orgId;
        if (theOrg == null) {
            LoginUser lu = UserContext.get();
            if (lu != null) {
                theOrg = lu.getOrgId();
            }
        }
        return R.ok(service.createStockCheck(theOrg, warehouseId));
    }

    /** 录入实盘数量(自动算差异; 仅进行中的盘点单可录入) */
    @PutMapping("/check/{id}/item/{itemId}")
    public R<Void> updateCheckItem(@PathVariable Long id,
                                   @PathVariable Long itemId,
                                   @RequestParam BigDecimal actualQty) {
        guard.requireLeadWrite();
        service.updateCheckItem(id, itemId, actualQty);
        return R.ok();
    }

    /** 确认盘点(差异生成盘盈入库/盘亏出库并自动确认, 汇总金额, 置已完成) */
    @PostMapping("/check/{id}/confirm")
    public R<Void> confirmCheck(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.confirmStockCheck(id);
        return R.ok();
    }

    /** 作废盘点单(仅进行中可作废) */
    @DeleteMapping("/check/{id}")
    public R<Void> voidCheck(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.voidStockCheck(id);
        return R.ok();
    }

    /** 盘点记录分页(warehouseId 可选; 盘点日期范围 yyyy-MM-dd) */
    @GetMapping("/check/page")
    public R<IPage<HisStockCheck>> checkPage(@RequestParam(required = false) Long orgId,
                                             @RequestParam(required = false) Long warehouseId,
                                             @RequestParam(required = false) String startDate,
                                             @RequestParam(required = false) String endDate,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.stockCheckPage(guard.scopeOrgId(orgId), warehouseId, startDate, endDate, page, size));
    }

    /** 盘点单详情(主表+明细) */
    @GetMapping("/check/{id}")
    public R<Map<String, Object>> checkDetail(@PathVariable Long id) {
        return R.ok(service.stockCheckDetail(id));
    }

    /* ================= 药品目录(入库选药, 只读) ================= */

    /** 机构开展药品目录分页(warehouseType: WESTERN/TCM/MIXED 过滤中药属性) */
    @GetMapping("/drug-catalog")
    public R<IPage<HisDrugCatalog>> drugCatalog(@RequestParam(required = false) Long orgId,
                                                @RequestParam(required = false) String warehouseType,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.drugCatalogPage(guard.scopeOrgId(orgId), warehouseType, keyword, page, size));
    }

    /* ================= 库存 ================= */

    /** 库存分页(keyword: 名称/编码/批号/厂家; lowStock=true 低库存; warehouseId 可选按库过滤) */
    @GetMapping("/page")
    public R<IPage<HisDrugStock>> page(@RequestParam(required = false) Long orgId,
                                       @RequestParam(required = false) Long warehouseId,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Boolean lowStock,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.stockPage(guard.scopeOrgId(orgId), warehouseId, keyword, lowStock, page, size));
    }

    /** 低库存预警列表(warehouseId 可选按库过滤) */
    @GetMapping("/alert")
    public R<List<HisDrugStock>> alert(@RequestParam(required = false) Long orgId,
                                       @RequestParam(required = false) Long warehouseId) {
        return R.ok(service.lowStockAlert(guard.scopeOrgId(orgId), warehouseId));
    }

    /** 出入库流水(已确认单据明细, 确认时间倒序; warehouseId 可选按库过滤) */
    @GetMapping("/flow")
    public R<IPage<Map<String, Object>>> flow(@RequestParam(required = false) Long orgId,
                                              @RequestParam(required = false) Long warehouseId,
                                              @RequestParam(required = false) Long drugCatalogId,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.stockFlow(guard.scopeOrgId(orgId), warehouseId, drugCatalogId, startDate, endDate, page, size));
    }

    /** 库存导出(xlsx): 与列表同口径(warehouseId 可选按库过滤) */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(required = false) Long orgId,
                       @RequestParam(required = false) Long warehouseId,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = service.exportStock(guard.scopeOrgId(orgId), warehouseId);
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

    /** 创建入库单(草稿, warehouseId 可选指定入库药库) */
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

    /** 入库单分页(warehouseId 可选按库过滤) */
    @GetMapping("/in/page")
    public R<IPage<HisStockIn>> inPage(@RequestParam(required = false) Long orgId,
                                       @RequestParam(required = false) Long warehouseId,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) String startDate,
                                       @RequestParam(required = false) String endDate,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.stockInPage(guard.scopeOrgId(orgId), warehouseId, status, startDate, endDate, page, size));
    }

    /** 入库单详情(主表+明细) */
    @GetMapping("/in/{id}")
    public R<Map<String, Object>> inDetail(@PathVariable Long id) {
        return R.ok(service.stockInDetail(id));
    }

    /* ================= 出库单 ================= */

    /** 创建出库单(草稿): 明细可指定批次(drugStockId)或仅药品(确认时FIFO扣减); warehouseId 可选指定出库药库 */
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

    /** 出库单分页(warehouseId 可选按库过滤) */
    @GetMapping("/out/page")
    public R<IPage<HisStockOut>> outPage(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Long warehouseId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String startDate,
                                         @RequestParam(required = false) String endDate,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.stockOutPage(guard.scopeOrgId(orgId), warehouseId, status, startDate, endDate, page, size));
    }

    /** 出库单详情(主表+明细) */
    @GetMapping("/out/{id}")
    public R<Map<String, Object>> outDetail(@PathVariable Long id) {
        return R.ok(service.stockOutDetail(id));
    }
}
