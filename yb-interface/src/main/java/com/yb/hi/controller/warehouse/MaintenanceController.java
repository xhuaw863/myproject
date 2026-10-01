package com.yb.hi.controller.warehouse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.warehouse.MaintenanceCreateReq;
import com.yb.hi.dto.warehouse.MaintenanceItemReq;
import com.yb.hi.entity.warehouse.HisDrugMaintenance;
import com.yb.hi.entity.warehouse.HisMaintenanceTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.MaintenanceService;
import com.yb.hi.service.warehouse.WarehouseAccessService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 药品养护接口(批次D): 养护模板 CRUD + 三种建单(手动/自动/模板) + 明细编辑 + 完成养护 + 查询。
 * 读: scopeOrgId 机构隔离(详情按ID直查校验归属); 写: requireLeadWrite(仅牵头管理员, 对齐药库口径); 涉及具体药库的读沿用 requireWhIfPresent。
 */
@RestController
@RequestMapping("/api/warehouse/maintenance")
public class MaintenanceController {

    private final MaintenanceService service;
    private final OrgAccessGuard guard;
    private final WarehouseAccessService access;

    public MaintenanceController(MaintenanceService service, OrgAccessGuard guard, WarehouseAccessService access) {
        this.service = service;
        this.guard = guard;
        this.access = access;
    }

    /* ================= 模板 ================= */

    @GetMapping("/template/list")
    public R<List<HisMaintenanceTemplate>> templateList(@RequestParam(required = false) Long orgId) {
        return R.ok(service.templateList(guard.scopeOrgId(orgId)));
    }

    @PostMapping("/template")
    public R<HisMaintenanceTemplate> saveTemplate(@RequestBody HisMaintenanceTemplate tpl) {
        guard.requireLeadWrite();
        if (tpl.getOrgId() == null) {
            Long org = guard.scopeOrgId(null);
            // 牵头管理员未指定机构时回落到登录机构(org_id 非空约束)
            tpl.setOrgId(org != null ? org : guard.currentOrgId());
        }
        return R.ok(service.saveTemplate(tpl));
    }

    @DeleteMapping("/template/{id}")
    public R<Void> deleteTemplate(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.deleteTemplate(id);
        return R.ok();
    }

    /* ================= 养护单查询 ================= */

    @GetMapping("/page")
    public R<IPage<HisDrugMaintenance>> page(@RequestParam(required = false) Long orgId,
                                             @RequestParam(required = false) Long warehouseId,
                                             @RequestParam(required = false) Integer status,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        requireWhIfPresent(warehouseId);
        return R.ok(service.page(guard.scopeOrgId(orgId), warehouseId, status, page, size));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> detail = service.detail(id);
        checkOrgVisible(((HisDrugMaintenance) detail.get("main")).getOrgId());
        return R.ok(detail);
    }

    /* ================= 建单/编辑/完成 ================= */

    @PostMapping("/create")
    public R<HisDrugMaintenance> create(@RequestBody MaintenanceCreateReq req) {
        guard.requireLeadWrite();
        Long org = guard.scopeOrgId(req.getOrgId());
        // 牵头管理员未指定机构时回落到登录机构(org_id 非空约束)
        req.setOrgId(org != null ? org : guard.currentOrgId());
        requireWhIfPresent(req.getWarehouseId());
        return R.ok(service.create(req));
    }

    @PutMapping("/{id}/items")
    public R<HisDrugMaintenance> saveItems(@PathVariable Long id, @RequestBody List<MaintenanceItemReq> items) {
        guard.requireLeadWrite();
        checkOrgVisible(orgIdOf(id));
        return R.ok(service.saveItems(id, items));
    }

    @PostMapping("/{id}/complete")
    public R<HisDrugMaintenance> complete(@PathVariable Long id, @RequestParam(required = false) String conclusion) {
        guard.requireLeadWrite();
        checkOrgVisible(orgIdOf(id));
        return R.ok(service.complete(id, conclusion));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        guard.requireLeadWrite();
        checkOrgVisible(orgIdOf(id));
        service.deleteDraft(id);
        return R.ok();
    }

    /* ================= 守卫辅助 ================= */

    private Long orgIdOf(Long id) {
        return ((HisDrugMaintenance) service.detail(id).get("main")).getOrgId();
    }

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
