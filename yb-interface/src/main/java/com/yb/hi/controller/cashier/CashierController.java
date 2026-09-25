package com.yb.hi.controller.cashier;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.cashier.ChargeReq;
import com.yb.hi.dto.cashier.RefundReq;
import com.yb.hi.entity.cashier.HisChargeBill;
import com.yb.hi.entity.cashier.HisDailySettle;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.cashier.CashierService;
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
 */
@RestController
@RequestMapping("/api/his/cashier")
public class CashierController {

    private final CashierService cashierService;
    private final OrgAccessGuard guard;

    public CashierController(CashierService cashierService, OrgAccessGuard guard) {
        this.cashierService = cashierService;
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

    /** 收据数据(收费单+明细+患者, 供打印/展示) */
    @GetMapping("/receipt/{billId}")
    public R<Map<String, Object>> receipt(@PathVariable Long billId) {
        return R.ok(cashierService.billReceipt(billId));
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
}
