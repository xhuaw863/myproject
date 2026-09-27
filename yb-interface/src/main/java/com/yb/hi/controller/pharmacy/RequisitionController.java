package com.yb.hi.controller.pharmacy;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.pharmacy.RequisitionReq;
import com.yb.hi.entity.pharmacy.HisRequisition;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.pharmacy.RequisitionService;
import com.yb.hi.service.warehouse.WarehouseAccessService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 药品请领接口(药房→药库): 发起/提交 → 审核并发货 → 确认收货; 列表/详情/可用库存查询。
 * 请领是本机构自治业务(药房是本院过程): 写操作走 requireSelfOrgWrite(不限牵头), 读隔离走 scopeOrgId。
 */
@RestController
@RequestMapping("/api/his/requisition")
public class RequisitionController {

    private final RequisitionService requisitionService;
    private final OrgAccessGuard guard;
    private final WarehouseAccessService access;

    public RequisitionController(RequisitionService requisitionService, OrgAccessGuard guard,
                                 WarehouseAccessService access) {
        this.requisitionService = requisitionService;
        this.guard = guard;
        this.access = access;
    }

    /** 请领单列表(机构/药房/状态/关键字) */
    @GetMapping("/page")
    public R<IPage<HisRequisition>> page(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Long pharmacyId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        if (pharmacyId != null) {
            access.requirePharmacyAccess(pharmacyId);
        }
        return R.ok(requisitionService.page(guard.scopeOrgId(orgId), pharmacyId, status, keyword, page, size));
    }

    /** 请领单详情 {main, items} */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(requisitionService.detail(id));
    }

    /** 来源药库某药品可用库存(发起页超量提示): warehouseId=发货来源药库 */
    @GetMapping("/available")
    public R<BigDecimal> available(@RequestParam(required = false) Long orgId,
                                   @RequestParam Long warehouseId,
                                   @RequestParam Long drugCatalogId) {
        // 发起请领时查源药库可用量: 药房侧动作, 不以源药库科室授权拦截(否则会阻断合法请领)
        return R.ok(requisitionService.availableQty(resolveOrgId(orgId), warehouseId, drugCatalogId));
    }

    /** 药房发起请领(草稿/提交) */
    @PostMapping
    public R<HisRequisition> create(@RequestBody RequisitionReq req) {
        guard.requireSelfOrgWrite();
        if (req != null && req.getPharmacyId() != null) {
            access.requirePharmacyAccess(req.getPharmacyId());
        }
        req.setOrgId(resolveOrgId(req.getOrgId()));
        return R.ok(requisitionService.create(req));
    }

    /** 药库审核并发货(approved=true 发货 / false 驳回) */
    @PostMapping("/{id}/approve")
    public R<HisRequisition> approve(@PathVariable Long id, @RequestParam boolean approved) {
        guard.requireSelfOrgWrite();
        return R.ok(requisitionService.approve(id, approved));
    }

    /** 药房确认收货 */
    @PostMapping("/{id}/receive")
    public R<HisRequisition> receive(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        return R.ok(requisitionService.receive(id));
    }

    /** 作废草稿/待审核请领单 */
    @PostMapping("/{id}/void")
    public R<HisRequisition> voidOne(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        return R.ok(requisitionService.voidRequisition(id));
    }

    /** 机构作用域: 牵头可取入参, 非牵头强制本机构; 入参为空回退当前登录机构 */
    private Long resolveOrgId(Long requested) {
        Long oid = guard.scopeOrgId(requested);
        if (oid != null) {
            return oid;
        }
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getOrgId();
    }
}
