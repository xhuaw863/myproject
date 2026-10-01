package com.yb.hi.controller.warehouse;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.warehouse.SupplierPaymentReq;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisSupplierPayment;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.warehouse.SupplierPaymentService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 供应商付款/应付账款接口(批次C): 未结入库单查询 + 三种付款方式建单/确认 + 付款单查询 + 应付账龄分析。
 * 读: scopeOrgId 机构隔离(详情按ID直查校验归属); 写: requireLeadWrite(仅牵头管理员, 对齐药库出入库口径)。
 */
@RestController
@RequestMapping("/api/warehouse/payment")
public class SupplierPaymentController {

    private final SupplierPaymentService service;
    private final OrgAccessGuard guard;

    public SupplierPaymentController(SupplierPaymentService service, OrgAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /* ================= 查询 ================= */

    /** 供应商未结入库单分页(付款建单选择) */
    @GetMapping("/unpaid")
    public R<IPage<HisStockIn>> unpaid(@RequestParam(required = false) Long orgId,
                                       @RequestParam Long supplierId,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.unpaidPage(guard.scopeOrgId(orgId), supplierId, page, size));
    }

    /** 付款单分页 */
    @GetMapping("/page")
    public R<IPage<HisSupplierPayment>> page(@RequestParam(required = false) Long orgId,
                                             @RequestParam(required = false) Long supplierId,
                                             @RequestParam(required = false) Integer status,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.page(guard.scopeOrgId(orgId), supplierId, status, page, size));
    }

    /** 付款单详情(主+明细) */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> detail = service.detail(id);
        checkOrgVisible(((HisSupplierPayment) detail.get("main")).getOrgId());
        return R.ok(detail);
    }

    /** 应付账款 + 账龄分析 */
    @GetMapping("/payable")
    public R<Map<String, Object>> payable(@RequestParam(required = false) Long orgId,
                                          @RequestParam(required = false) Long supplierId) {
        return R.ok(service.payable(guard.scopeOrgId(orgId), supplierId));
    }

    /* ================= 付款处理 ================= */

    /** 创建付款单(草稿): 按付款方式分摊并校验不超应付 */
    @PostMapping("/create")
    public R<HisSupplierPayment> create(@RequestBody SupplierPaymentReq req) {
        guard.requireLeadWrite();
        req.setOrgId(guard.scopeOrgId(req.getOrgId()));
        return R.ok(service.createPayment(req));
    }

    /** 确认付款: 回写来源入库单已付标记 */
    @PostMapping("/{id}/confirm")
    public R<HisSupplierPayment> confirm(@PathVariable Long id) {
        guard.requireLeadWrite();
        checkOrgVisible(orgIdOf(id));
        return R.ok(service.confirmPayment(id));
    }

    /** 作废草稿付款单 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        guard.requireLeadWrite();
        checkOrgVisible(orgIdOf(id));
        service.deleteDraft(id);
        return R.ok();
    }

    /* ================= 守卫辅助 ================= */

    private Long orgIdOf(Long paymentId) {
        Map<String, Object> detail = service.detail(paymentId);
        return ((HisSupplierPayment) detail.get("main")).getOrgId();
    }

    private void checkOrgVisible(Long orgId) {
        Long scoped = guard.scopeOrgId(null);
        if (scoped != null && !scoped.equals(orgId)) {
            throw new BizException(403, "仅可操作本机构单据");
        }
    }
}
