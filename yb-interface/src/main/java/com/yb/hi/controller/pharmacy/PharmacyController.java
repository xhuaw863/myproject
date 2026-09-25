package com.yb.hi.controller.pharmacy;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.pharmacy.DispenseReq;
import com.yb.hi.dto.pharmacy.DrugReturnReq;
import com.yb.hi.entity.pharmacy.HisDispense;
import com.yb.hi.entity.pharmacy.HisDrugReturn;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.pharmacy.PharmacyService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 药房接口(药房工作站): 待发药/发药/发药记录/退药申请与审批/退药记录。
 * 读: 非牵头机构强制本院(scopeOrgId); 发药/退药为日常业务不限牵头(机构隔离由 scopeOrgId 保证)。
 */
@RestController
@RequestMapping("/api/his/pharmacy")
public class PharmacyController {

    private final PharmacyService pharmacyService;
    private final OrgAccessGuard guard;

    public PharmacyController(PharmacyService pharmacyService, OrgAccessGuard guard) {
        this.pharmacyService = pharmacyService;
        this.guard = guard;
    }

    /** 待发药列表(dispense_status=0 的处方, 先开先发) */
    @GetMapping("/todo")
    public R<IPage<Map<String, Object>>> todo(@RequestParam(required = false) Long orgId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return R.ok(pharmacyService.todoPage(guard.scopeOrgId(orgId), keyword, page, size));
    }

    /** 发药详情(处方信息+药品明细+库存匹配, 供发药前核对) */
    @GetMapping("/detail/{prescriptionId}")
    public R<Map<String, Object>> detail(@PathVariable Long prescriptionId) {
        return R.ok(pharmacyService.dispenseDetail(prescriptionId));
    }

    /** 执行发药(乐观锁防重复, 出库单确认 FIFO 扣减库存) */
    @PostMapping("/dispense")
    public R<HisDispense> dispense(@RequestBody DispenseReq req) {
        req.setOrgId(guard.scopeOrgId(req.getOrgId()));
        return R.ok(pharmacyService.doDispense(req));
    }

    /** 发药记录分页(机构/状态/发药日期区间/单号或患者关键字) */
    @GetMapping("/records")
    public R<IPage<HisDispense>> records(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String startDate,
                                         @RequestParam(required = false) String endDate,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return R.ok(pharmacyService.dispensePage(guard.scopeOrgId(orgId), status, startDate, endDate, keyword, page, size));
    }

    /** 发药记录导出(xlsx): 与列表同一机构/日期口径 */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(required = false) Long orgId,
                       @RequestParam(required = false) String startDate,
                       @RequestParam(required = false) String endDate,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = pharmacyService.exportDispense(guard.scopeOrgId(orgId), startDate, endDate);
        String fname = "发药记录_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("发药记录")
                .doWrite((List<List<Object>>) data.get("rows"));
    }

    /** 退药申请(仅已发药记录, 生成TY单待审核) */
    @PostMapping("/return")
    public R<HisDrugReturn> returnApply(@RequestBody DrugReturnReq req) {
        return R.ok(pharmacyService.returnApply(req));
    }

    /** 退药审批(通过: 入库单确认回补库存+处方置已退药; 驳回: 仅更新状态) */
    @PostMapping("/return/{id}/approve")
    public R<HisDrugReturn> returnApprove(@PathVariable Long id, @RequestParam boolean approved) {
        return R.ok(pharmacyService.returnApprove(id, approved));
    }

    /** 退药记录分页(机构/状态/申请日期区间) */
    @GetMapping("/returns")
    public R<IPage<HisDrugReturn>> returns(@RequestParam(required = false) Long orgId,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) String startDate,
                                           @RequestParam(required = false) String endDate,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "20") long size) {
        return R.ok(pharmacyService.returnPage(guard.scopeOrgId(orgId), status, startDate, endDate, page, size));
    }
}
