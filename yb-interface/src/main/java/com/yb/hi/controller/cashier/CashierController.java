package com.yb.hi.controller.cashier;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.cashier.ChargeReq;
import com.yb.hi.dto.cashier.PartialRefundReq;
import com.yb.hi.dto.cashier.RefundReq;
import com.yb.hi.entity.cashier.HisChargeBill;
import com.yb.hi.entity.cashier.HisDailySettle;
import com.yb.hi.entity.cashier.HisInvoice;
import com.yb.hi.entity.cashier.HisInvoicePool;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.cashier.CashierService;
import com.yb.hi.service.cashier.InvoiceService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 收费结算接口(收费员工作台)
 * 读: 非牵头机构强制本院(scopeOrgId); 收费/退费/日结为日常业务不限牵头; 退费机构隔离在服务层守卫。
 * 发票号段/开票冲销为财务票据管理, 仅牵头机构管理员可维护(requireLeadWrite)。
 */
@RestController
@RequestMapping("/api/his/cashier")
public class CashierController {

    private final CashierService cashierService;
    private final InvoiceService invoiceService;
    private final OrgAccessGuard guard;

    public CashierController(CashierService cashierService, InvoiceService invoiceService, OrgAccessGuard guard) {
        this.cashierService = cashierService;
        this.invoiceService = invoiceService;
        this.guard = guard;
    }

    /** 待收费列表(已完成接诊且未收费) */
    @GetMapping("/todo")
    public R<IPage<Map<String, Object>>> todo(@RequestParam(required = false) Long orgId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return R.ok(cashierService.todoPage(guard.scopeOrgId(orgId), keyword, page, size));
    }

    /** 费用明细(处方+检查单汇总, 供收费前核对) */
    @GetMapping("/bill/{visitId}")
    public R<Map<String, Object>> billDetail(@PathVariable Long visitId) {
        return R.ok(cashierService.billDetail(visitId));
    }

    /** 医保收费(2206预结算+2207结算) */
    @PostMapping("/charge")
    public R<Map<String, Object>> charge(@RequestBody ChargeReq req) {
        req.setOrgId(guard.scopeOrgId(req.getOrgId()));
        return R.ok(cashierService.charge(req));
    }

    /** 自费收费(不走医保) */
    @PostMapping("/self-pay")
    public R<Map<String, Object>> selfPay(@RequestBody ChargeReq req) {
        req.setOrgId(guard.scopeOrgId(req.getOrgId()));
        return R.ok(cashierService.selfPayCharge(req));
    }

    /** 退费(已日结不可退, 医保单同步撤销结算) */
    @PostMapping("/refund")
    public R<HisChargeBill> refund(@RequestBody RefundReq req) {
        return R.ok(cashierService.refund(req));
    }

    /** 部分退费(按明细行退指定数量, 支持多次部分退; 全部退完原单自动转已退费) */
    @PostMapping("/partial-refund")
    public R<HisChargeBill> partialRefund(@RequestBody PartialRefundReq req) {
        return R.ok(cashierService.partialRefund(req));
    }

    /** 发票号段分页(按分配时间降序) */
    @GetMapping("/invoice-pool")
    public R<IPage<HisInvoicePool>> invoicePools(@RequestParam(required = false) Long orgId,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size) {
        return R.ok(invoiceService.poolPage(guard.scopeOrgId(orgId), page, size));
    }

    /** 保存发票号段(新增/编辑; 编码同机构唯一, 区间不交叉) */
    @PostMapping("/invoice-pool")
    public R<HisInvoicePool> saveInvoicePool(@RequestBody HisInvoicePool pool) {
        guard.requireLeadWrite();
        pool.setOrgId(guard.scopeOrgId(pool.getOrgId()));
        return R.ok(invoiceService.savePool(pool));
    }

    /** 启用号段(同机构同发票类型唯一使用中, 旧使用中号段自动让位) */
    @PostMapping("/invoice-pool/{id}/activate")
    public R<HisInvoicePool> activateInvoicePool(@PathVariable Long id) {
        guard.requireLeadWrite();
        return R.ok(invoiceService.activatePool(id));
    }

    /** 发票作废(原号+"V"冲销记录, 金额取负) */
    @PostMapping("/invoice/{id}/void")
    public R<HisInvoice> voidInvoice(@PathVariable Long id, @RequestParam String reason) {
        guard.requireLeadWrite();
        return R.ok(invoiceService.voidInvoice(id, reason));
    }

    /** 发票红冲(原号+"R"冲销记录, 金额取负) */
    @PostMapping("/invoice/{id}/red")
    public R<HisInvoice> redInvoice(@PathVariable Long id, @RequestParam String reason) {
        guard.requireLeadWrite();
        return R.ok(invoiceService.redInvoice(id, reason));
    }

    /** 发票记录分页(状态/创建日期区间过滤) */
    @GetMapping("/invoices")
    public R<IPage<HisInvoice>> invoices(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String startDate,
                                         @RequestParam(required = false) String endDate,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return R.ok(invoiceService.invoicePage(guard.scopeOrgId(orgId), status, startDate, endDate, page, size));
    }

    /** 收费记录分页(billType: 1收费/2退费, 可选) */
    @GetMapping("/bills")
    public R<IPage<HisChargeBill>> bills(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) Integer billType,
                                         @RequestParam(required = false) String startDate,
                                         @RequestParam(required = false) String endDate,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return R.ok(cashierService.billPage(guard.scopeOrgId(orgId), status, billType, startDate, endDate, keyword, page, size));
    }

    /** 收据数据(收费单+明细+患者, 供打印/展示; 明细含报销类别/自费自理/票据归并类) */
    @GetMapping("/receipt/{billId}")
    public R<Map<String, Object>> receipt(@PathVariable Long billId) {
        return R.ok(cashierService.billReceipt(billId));
    }

    /** 正式门诊收费票据打印数据(财综〔2012〕3号式样: 表头/患者栏/机打明细/11类归并/大写合计/支付四分) */
    @GetMapping("/invoice-print/{billId}")
    public R<Map<String, Object>> invoicePrint(@PathVariable Long billId) {
        return R.ok(cashierService.invoicePrint(billId));
    }

    /** 今日收费概览(工作站顶部统计卡: 待收费/今日收费退费/现金净额/已开票/日结状态) */
    @GetMapping("/daily-summary")
    public R<Map<String, Object>> dailySummary(@RequestParam(required = false) Long orgId) {
        return R.ok(cashierService.dailySummary(guard.scopeOrgId(orgId)));
    }

    /** 执行日结(幂等: 已日结直接返回) */
    @PostMapping("/daily-settle")
    public R<HisDailySettle> dailySettle(@RequestParam(required = false) Long orgId,
                                         @RequestParam String date) {
        return R.ok(cashierService.dailySettle(guard.scopeOrgId(orgId), date));
    }

    /** 日结记录分页 */
    @GetMapping("/daily-settles")
    public R<IPage<HisDailySettle>> dailySettles(@RequestParam(required = false) Long orgId,
                                                 @RequestParam(required = false) String startDate,
                                                 @RequestParam(required = false) String endDate,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size) {
        return R.ok(cashierService.dailySettlePage(guard.scopeOrgId(orgId), startDate, endDate, page, size));
    }

    /** 收费记录导出(xlsx): 与列表同一机构/日期口径 */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(required = false) Long orgId,
                       @RequestParam(required = false) String startDate,
                       @RequestParam(required = false) String endDate,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = cashierService.exportBills(guard.scopeOrgId(orgId), startDate, endDate);
        String fname = "收费记录_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("收费记录")
                .doWrite((List<List<Object>>) data.get("rows"));
    }

    /** 发票记录导出(xlsx): 与发票列表同一机构/日期口径 */
    @GetMapping("/export/invoices")
    @SuppressWarnings("unchecked")
    public void exportInvoices(@RequestParam(required = false) Long orgId,
                               @RequestParam(required = false) String startDate,
                               @RequestParam(required = false) String endDate,
                               HttpServletResponse resp) throws IOException {
        Map<String, Object> data = invoiceService.exportInvoices(guard.scopeOrgId(orgId), startDate, endDate);
        String fname = "发票记录_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("发票记录")
                .doWrite((List<List<Object>>) data.get("rows"));
    }
}
