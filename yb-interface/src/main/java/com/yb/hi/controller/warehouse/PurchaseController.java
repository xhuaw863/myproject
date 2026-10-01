package com.yb.hi.controller.warehouse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.warehouse.HisPurchaseOrder;
import com.yb.hi.entity.warehouse.HisPurchasePlan;
import com.yb.hi.entity.warehouse.HisPurchasePlanItem;
import com.yb.hi.entity.warehouse.HisPurchaseRule;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisSupplier;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.PurchaseService;
import com.yb.hi.service.warehouse.WarehouseAccessService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 药品采购接口(批次A): 供应商/采购规则 CRUD + 计划智能生成/审批/转订单 + 订单引入入库/集采上传(Mock)。
 * 读: scopeOrgId 机构隔离(按ID直查详情校验归属); 写: requireLeadWrite(仅牵头管理员, 对齐药库出入库口径); 涉及具体药库的读沿用 requireWhIfPresent。
 */
@RestController
@RequestMapping("/api/warehouse/purchase")
public class PurchaseController {

    private final PurchaseService service;
    private final OrgAccessGuard guard;
    private final WarehouseAccessService access;

    public PurchaseController(PurchaseService service, OrgAccessGuard guard, WarehouseAccessService access) {
        this.service = service;
        this.guard = guard;
        this.access = access;
    }

    /* ================= 供应商 ================= */

    @GetMapping("/supplier/page")
    public R<IPage<HisSupplier>> supplierPage(@RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.supplierPage(null, keyword, page, size));
    }

    @GetMapping("/supplier/list")
    public R<List<HisSupplier>> supplierList(@RequestParam(required = false) Boolean jtOnly) {
        return R.ok(service.supplierList(jtOnly));
    }

    @GetMapping("/supplier/{id}")
    public R<HisSupplier> supplierGet(@PathVariable Long id) {
        return R.ok(service.supplierGet(id));
    }

    @PostMapping("/supplier")
    public R<HisSupplier> saveSupplier(@RequestBody HisSupplier supplier) {
        guard.requireLeadWrite();
        return R.ok(service.saveSupplier(supplier));
    }

    @PostMapping("/supplier/{id}/toggle")
    public R<Void> toggleSupplier(@PathVariable Long id, @RequestParam boolean enabled) {
        guard.requireLeadWrite();
        service.toggleSupplier(id, enabled);
        return R.ok();
    }

    /* ================= 采购规则 ================= */

    @GetMapping("/rule/list")
    public R<List<HisPurchaseRule>> ruleList(@RequestParam(required = false) Long orgId,
                                             @RequestParam(required = false) Long warehouseId) {
        requireWhIfPresent(warehouseId);
        return R.ok(service.ruleList(guard.scopeOrgId(orgId), warehouseId));
    }

    @PostMapping("/rule")
    public R<HisPurchaseRule> saveRule(@RequestBody HisPurchaseRule rule) {
        guard.requireLeadWrite();
        if (rule != null && rule.getOrgId() == null) {
            rule.setOrgId(currentOrgId());
        }
        return R.ok(service.saveRule(rule));
    }

    @DeleteMapping("/rule/{id}")
    public R<Void> deleteRule(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.deleteRule(id);
        return R.ok();
    }

    /* ================= 采购计划 ================= */

    @GetMapping("/plan/page")
    public R<IPage<HisPurchasePlan>> planPage(@RequestParam(required = false) Long orgId,
                                              @RequestParam(required = false) Long warehouseId,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        requireWhIfPresent(warehouseId);
        return R.ok(service.planPage(guard.scopeOrgId(orgId), warehouseId, status, startDate, endDate, page, size));
    }

    @GetMapping("/plan/{id}")
    public R<Map<String, Object>> planDetail(@PathVariable Long id) {
        Map<String, Object> detail = service.planDetail(id);
        checkOrgVisible(((HisPurchasePlan) detail.get("main")).getOrgId());
        return R.ok(detail);
    }

    /** 智能生成采购计划(草稿): 读在仓库存+上月出入库+发药量按规则算建议量 */
    @PostMapping("/plan/auto-generate")
    public R<HisPurchasePlan> autoGenerate(@RequestParam(required = false) Long orgId,
                                           @RequestParam(required = false) Long warehouseId,
                                           @RequestParam(required = false) String majorClass,
                                           @RequestParam(required = false) String remark) {
        guard.requireLeadWrite();
        requireWhIfPresent(warehouseId);
        Long theOrg = orgId != null ? orgId : currentOrgId();
        return R.ok(service.autoGeneratePlan(theOrg, warehouseId, majorClass, remark));
    }

    @PostMapping("/plan/manual")
    public R<HisPurchasePlan> createManual(@RequestParam(required = false) Long orgId,
                                           @RequestParam(required = false) Long warehouseId,
                                           @RequestParam(required = false) Long supplierId,
                                           @RequestParam(required = false) String remark) {
        guard.requireLeadWrite();
        requireWhIfPresent(warehouseId);
        Long theOrg = orgId != null ? orgId : currentOrgId();
        return R.ok(service.createManualPlan(theOrg, warehouseId, supplierId, remark));
    }

    @PostMapping("/plan/{id}/items")
    public R<HisPurchasePlan> savePlanItems(@PathVariable Long id, @RequestBody List<HisPurchasePlanItem> items) {
        guard.requireLeadWrite();
        planWriteGuard(id);
        return R.ok(service.savePlanItems(id, items));
    }

    @PostMapping("/plan/{id}/submit")
    public R<Void> submitPlan(@PathVariable Long id) {
        guard.requireLeadWrite();
        planWriteGuard(id);
        service.submitPlan(id);
        return R.ok();
    }

    @PostMapping("/plan/{id}/approve")
    public R<Void> approvePlan(@PathVariable Long id) {
        guard.requireLeadWrite();
        planWriteGuard(id);
        service.approvePlan(id);
        return R.ok();
    }

    @PostMapping("/plan/{id}/reject")
    public R<Void> rejectPlan(@PathVariable Long id, @RequestParam(required = false) String reason) {
        guard.requireLeadWrite();
        planWriteGuard(id);
        service.rejectPlan(id, reason);
        return R.ok();
    }

    @DeleteMapping("/plan/{id}")
    public R<Void> voidPlan(@PathVariable Long id) {
        guard.requireLeadWrite();
        planWriteGuard(id);
        service.voidPlan(id);
        return R.ok();
    }

    @PostMapping("/plan/{id}/convert")
    public R<HisPurchaseOrder> convertPlan(@PathVariable Long id, @RequestParam(required = false) Long supplierId) {
        guard.requireLeadWrite();
        planWriteGuard(id);
        return R.ok(service.convertPlanToOrder(id, supplierId));
    }

    /* ================= 采购订单 ================= */

    @GetMapping("/order/page")
    public R<IPage<HisPurchaseOrder>> orderPage(@RequestParam(required = false) Long orgId,
                                                @RequestParam(required = false) Long warehouseId,
                                                @RequestParam(required = false) Long supplierId,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(required = false) String startDate,
                                                @RequestParam(required = false) String endDate,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size) {
        requireWhIfPresent(warehouseId);
        return R.ok(service.orderPage(guard.scopeOrgId(orgId), warehouseId, supplierId, status, startDate, endDate, page, size));
    }

    @GetMapping("/order/{id}")
    public R<Map<String, Object>> orderDetail(@PathVariable Long id) {
        Map<String, Object> detail = service.orderDetail(id);
        checkOrgVisible(((HisPurchaseOrder) detail.get("main")).getOrgId());
        return R.ok(detail);
    }

    @PostMapping("/order/{id}/place")
    public R<Void> placeOrder(@PathVariable Long id) {
        guard.requireLeadWrite();
        orderWriteGuard(id);
        service.placeOrder(id);
        return R.ok();
    }

    @DeleteMapping("/order/{id}")
    public R<Void> voidOrder(@PathVariable Long id) {
        guard.requireLeadWrite();
        orderWriteGuard(id);
        service.voidOrder(id);
        return R.ok();
    }

    /** 集采/统采上传(Mock 占位) */
    @PostMapping("/order/{id}/upload")
    public R<HisPurchaseOrder> uploadOrder(@PathVariable Long id) {
        guard.requireLeadWrite();
        orderWriteGuard(id);
        return R.ok(service.uploadOrder(id));
    }

    /** 订单引入入库单(草稿, in_type=1): 后续在采购入库页确认入库 */
    @PostMapping("/order/{id}/import-in")
    public R<HisStockIn> importIn(@PathVariable Long id) {
        guard.requireLeadWrite();
        HisPurchaseOrder order = orderForGuard(id);
        requireWhIfPresent(order.getWarehouseId());
        return R.ok(service.importToStockIn(id));
    }

    /* ================= 守卫辅助 ================= */

    /** 按ID直查的详情/写接口机构隔离: 非牵头仅可操作本机构单据 */
    private void checkOrgVisible(Long orgId) {
        Long scoped = guard.scopeOrgId(null);
        if (scoped != null && !scoped.equals(orgId)) {
            throw new BizException(403, "仅可操作本机构单据");
        }
    }

    private void planWriteGuard(Long planId) {
        Map<String, Object> detail = service.planDetail(planId);
        checkOrgVisible(((HisPurchasePlan) detail.get("main")).getOrgId());
    }

    private void orderWriteGuard(Long orderId) {
        HisPurchaseOrder order = orderForGuard(orderId);
        checkOrgVisible(order.getOrgId());
    }

    private HisPurchaseOrder orderForGuard(Long orderId) {
        return (HisPurchaseOrder) service.orderDetail(orderId).get("main");
    }

    private void requireWhIfPresent(Long warehouseId) {
        if (warehouseId != null) {
            access.requireWarehouseAccess(warehouseId);
        }
    }

    private Long currentOrgId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getOrgId();
    }
}
