package com.yb.hi.controller.report;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.ExportGuard;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.report.ReportService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 报表接口(纯读): Dashboard概览/收入趋势/科室收入/药品使用/就诊量/结算记录/日结记录/结算导出/医生工作日志/
 * 药库统计/药房统计/收费统计。
 * 读隔离: 非牵头机构强制本院(scopeOrgId); 牵头机构 orgId=null 时看全医共体。
 */
@RestController
@RequestMapping("/api/his/report")
public class ReportController {

    private final ReportService reportService;
    private final OrgAccessGuard guard;

    public ReportController(ReportService reportService, OrgAccessGuard guard) {
        this.reportService = reportService;
        this.guard = guard;
    }

    /** Dashboard概览: 今日/昨日/本月运营指标, 前端以 yesterday 计算环比 */
    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard(@RequestParam(required = false) Long orgId) {
        return R.ok(reportService.dashboardOverview(guard.scopeOrgId(orgId)));
    }

    /** 收入趋势: granularity=day/week/month, 区间缺省最近30天 */
    @GetMapping("/revenue")
    public R<List<Map<String, Object>>> revenue(@RequestParam(required = false) Long orgId,
                                                @RequestParam(required = false) String startDate,
                                                @RequestParam(required = false) String endDate,
                                                @RequestParam(defaultValue = "day") String granularity) {
        return R.ok(reportService.revenueStats(guard.scopeOrgId(orgId), startDate, endDate, granularity));
    }

    /** 科室收入排名(按金额降序, 含占比) */
    @GetMapping("/dept-revenue")
    public R<List<Map<String, Object>>> deptRevenue(@RequestParam(required = false) Long orgId,
                                                   @RequestParam(required = false) String startDate,
                                                   @RequestParam(required = false) String endDate) {
        return R.ok(reportService.deptRevenueStats(guard.scopeOrgId(orgId), startDate, endDate));
    }

    /** 药品使用排名TOP N(默认10) */
    @GetMapping("/drug-usage")
    public R<List<Map<String, Object>>> drugUsage(@RequestParam(required = false) Long orgId,
                                                  @RequestParam(required = false) String startDate,
                                                  @RequestParam(required = false) String endDate,
                                                  @RequestParam(defaultValue = "10") int topN) {
        return R.ok(reportService.drugUsageStats(guard.scopeOrgId(orgId), startDate, endDate, topN));
    }

    /** 就诊量统计: granularity=day/week/month */
    @GetMapping("/visits")
    public R<List<Map<String, Object>>> visits(@RequestParam(required = false) Long orgId,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "day") String granularity) {
        return R.ok(reportService.visitStats(guard.scopeOrgId(orgId), startDate, endDate, granularity));
    }

    /** 结算记录分页: type=inpatient住院医保结算 / outpatient|null本地收费单; keyword匹配患者姓名或单号 */
    @GetMapping("/settle-records")
    public R<IPage<Map<String, Object>>> settleRecords(@RequestParam(required = false) Long orgId,
                                                       @RequestParam(required = false) String type,
                                                       @RequestParam(required = false) String startDate,
                                                       @RequestParam(required = false) String endDate,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(reportService.settleRecordPage(guard.scopeOrgId(orgId), type, startDate, endDate, keyword, page, size));
    }

    /** 日结记录分页 */
    @GetMapping("/daily-settles")
    public R<IPage<Map<String, Object>>> dailySettles(@RequestParam(required = false) Long orgId,
                                                     @RequestParam(required = false) String startDate,
                                                     @RequestParam(required = false) String endDate,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long size) {
        return R.ok(reportService.dailySettlePage(guard.scopeOrgId(orgId), startDate, endDate, page, size));
    }

    /** 日结挂号费预览(P1-15): 当日挂号/退号净额与全渠道分项 */
    @GetMapping("/reg-preview")
    public R<Map<String, Object>> regPreview(@RequestParam(required = false) Long orgId,
                                             @RequestParam String date) {
        return R.ok(reportService.regPaymentPreview(guard.scopeOrgId(orgId), date));
    }

    /** 医生工作量汇总: 按医师聚合接诊/完成/处方/检查单量与金额(含完成率), 接诊数降序 */
    @GetMapping("/doctor-worklog")
    public R<List<Map<String, Object>>> doctorWorklog(@RequestParam(required = false) Long orgId,
                                                      @RequestParam(required = false) String startDate,
                                                      @RequestParam(required = false) String endDate,
                                                      @RequestParam(required = false) Long staffId,
                                                      @RequestParam(required = false) Long deptId) {
        return R.ok(reportService.doctorWorklogSummary(guard.scopeOrgId(orgId), startDate, endDate, staffId, deptId));
    }

    /** 医生工作日志明细分页: keyword匹配患者姓名, 每行带处方/检查单数量与金额 */
    @GetMapping("/doctor-worklog-detail")
    public R<IPage<Map<String, Object>>> doctorWorklogDetail(@RequestParam(required = false) Long orgId,
                                                             @RequestParam(required = false) String startDate,
                                                             @RequestParam(required = false) String endDate,
                                                             @RequestParam(required = false) Long staffId,
                                                             @RequestParam(required = false) Long deptId,
                                                             @RequestParam(required = false) String keyword,
                                                             @RequestParam(defaultValue = "1") long page,
                                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(reportService.doctorWorklogDetail(guard.scopeOrgId(orgId), startDate, endDate,
                staffId, deptId, keyword, page, size));
    }

    /** 导出结算记录(xlsx): 门诊收费单+住院医保结算, 区间缺省最近30天 */
    @GetMapping("/export/settle")
    @SuppressWarnings("unchecked")
    public void exportSettle(@RequestParam(required = false) Long orgId,
                             @RequestParam(required = false) String startDate,
                             @RequestParam(required = false) String endDate,
                             HttpServletResponse resp) throws IOException {
        Map<String, Object> data = reportService.exportSettleRecords(guard.scopeOrgId(orgId), startDate, endDate);
        String fname = "结算记录_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        ExportGuard.checkRows((java.util.Collection<?>) data.get("rows"), "结算记录");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("结算记录")
                .doWrite((List<List<Object>>) data.get("rows"));
    }

    /** 导出医生工作日志(xlsx, 双Sheet: 工作量汇总+接诊明细), 区间缺省最近30天 */
    @GetMapping("/export/doctor-worklog")
    @SuppressWarnings("unchecked")
    public void exportDoctorWorklog(@RequestParam(required = false) Long orgId,
                                    @RequestParam(required = false) String startDate,
                                    @RequestParam(required = false) String endDate,
                                    @RequestParam(required = false) Long staffId,
                                    @RequestParam(required = false) Long deptId,
                                    HttpServletResponse resp) throws IOException {
        Map<String, Object> data = reportService.exportDoctorWorklog(guard.scopeOrgId(orgId), startDate, endDate, staffId, deptId);
        String fname = "医生工作日志_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        // 双Sheet须用 ExcelWriter 模式(链式 doWrite 仅支持单Sheet)
        ExportGuard.checkRows((java.util.Collection<?>) data.get("summaryRows"), "医生工作日志");
        ExportGuard.checkRows((java.util.Collection<?>) data.get("detailRows"), "医生工作日志-接诊明细");
        ExcelWriter writer = EasyExcel.write(resp.getOutputStream()).build();
        try {
            WriteSheet sheet1 = EasyExcel.writerSheet(0, "工作量汇总")
                    .head((List<List<String>>) data.get("summaryHead")).build();
            writer.write((List<List<Object>>) data.get("summaryRows"), sheet1);
            WriteSheet sheet2 = EasyExcel.writerSheet(1, "接诊明细")
                    .head((List<List<String>>) data.get("detailHead")).build();
            writer.write((List<List<Object>>) data.get("detailRows"), sheet2);
        } finally {
            writer.finish();
        }
    }

    // ============================== 药库/药房/收费统计 ==============================

    /** 药库库存概况: 品种数/库存总金额/低库存批次数/近效期批次数(30天), warehouseId缺省为全药库 */
    @GetMapping("/warehouse-stats")
    public R<Map<String, Object>> warehouseStats(@RequestParam(required = false) Long orgId,
                                                 @RequestParam(required = false) Long warehouseId) {
        return R.ok(reportService.warehouseStockSummary(guard.scopeOrgId(orgId), warehouseId));
    }

    /** 药库入出库统计: 已确认入/出库单按类型汇总(单据数+金额), 区间缺省最近30天 */
    @GetMapping("/warehouse-flow")
    public R<Map<String, Object>> warehouseFlow(@RequestParam(required = false) Long orgId,
                                                @RequestParam(required = false) Long warehouseId,
                                                @RequestParam(required = false) String startDate,
                                                @RequestParam(required = false) String endDate) {
        return R.ok(reportService.warehouseFlowStats(guard.scopeOrgId(orgId), warehouseId, startDate, endDate));
    }

    /** 药房发药按日统计(已发药单), 区间缺省最近30天 */
    @GetMapping("/pharmacy-stats")
    public R<List<Map<String, Object>>> pharmacyStats(@RequestParam(required = false) Long orgId,
                                                      @RequestParam(required = false) Long pharmacyId,
                                                      @RequestParam(required = false) String startDate,
                                                      @RequestParam(required = false) String endDate) {
        return R.ok(reportService.pharmacyDispenseStats(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate));
    }

    /** 药房退药统计: 退药数/退药金额/发药数/退药率, 区间缺省最近30天 */
    @GetMapping("/pharmacy-return")
    public R<Map<String, Object>> pharmacyReturn(@RequestParam(required = false) Long orgId,
                                                 @RequestParam(required = false) Long pharmacyId,
                                                 @RequestParam(required = false) String startDate,
                                                 @RequestParam(required = false) String endDate) {
        return R.ok(reportService.pharmacyReturnStats(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate));
    }

    /** 支付方式构成: 支付明细优先, 老数据回退收费单pay_method, 区间缺省最近30天 */
    @GetMapping("/charge-paymethod")
    public R<List<Map<String, Object>>> chargePayMethod(@RequestParam(required = false) Long orgId,
                                                        @RequestParam(required = false) String startDate,
                                                        @RequestParam(required = false) String endDate) {
        return R.ok(reportService.chargePayMethodStats(guard.scopeOrgId(orgId), startDate, endDate));
    }

    /** 退费统计: 退费笔数/金额/部分与全额退费数, 区间缺省最近30天 */
    @GetMapping("/charge-refund")
    public R<Map<String, Object>> chargeRefund(@RequestParam(required = false) Long orgId,
                                               @RequestParam(required = false) String startDate,
                                               @RequestParam(required = false) String endDate) {
        return R.ok(reportService.chargeRefundStats(guard.scopeOrgId(orgId), startDate, endDate));
    }

    /** 发票统计: 普通发票/作废/红冲/总数, 区间缺省最近30天 */
    @GetMapping("/invoice-stats")
    public R<Map<String, Object>> invoiceStats(@RequestParam(required = false) Long orgId,
                                               @RequestParam(required = false) String startDate,
                                               @RequestParam(required = false) String endDate) {
        return R.ok(reportService.invoiceStats(guard.scopeOrgId(orgId), startDate, endDate));
    }

    /** 导出药库统计(xlsx, 双Sheet: 库存概况+入出库统计), 区间缺省最近30天 */
    @GetMapping("/export/warehouse")
    public void exportWarehouse(@RequestParam(required = false) Long orgId,
                                @RequestParam(required = false) Long warehouseId,
                                @RequestParam(required = false) String startDate,
                                @RequestParam(required = false) String endDate,
                                HttpServletResponse resp) throws IOException {
        Map<String, Object> data = reportService.exportWarehouseStats(guard.scopeOrgId(orgId), warehouseId,
                startDate, endDate);
        writeSheets(data, "药库统计_" + LocalDate.now() + ".xlsx", resp);
    }

    /** 导出药房统计(xlsx, 双Sheet: 发药统计+退药统计), 区间缺省最近30天 */
    @GetMapping("/export/pharmacy")
    public void exportPharmacy(@RequestParam(required = false) Long orgId,
                               @RequestParam(required = false) Long pharmacyId,
                               @RequestParam(required = false) String startDate,
                               @RequestParam(required = false) String endDate,
                               HttpServletResponse resp) throws IOException {
        Map<String, Object> data = reportService.exportPharmacyStats(guard.scopeOrgId(orgId), pharmacyId,
                startDate, endDate);
        writeSheets(data, "药房统计_" + LocalDate.now() + ".xlsx", resp);
    }

    /** 导出收费统计(xlsx, 三Sheet: 支付方式构成+退费统计+发票统计), 区间缺省最近30天 */
    @GetMapping("/export/charge-stats")
    public void exportChargeStats(@RequestParam(required = false) Long orgId,
                                  @RequestParam(required = false) String startDate,
                                  @RequestParam(required = false) String endDate,
                                  HttpServletResponse resp) throws IOException {
        Map<String, Object> data = reportService.exportChargeStats(guard.scopeOrgId(orgId), startDate, endDate);
        writeSheets(data, "收费统计_" + LocalDate.now() + ".xlsx", resp);
    }

    /** 多Sheet Excel 导出公用写法: data 为 {sheets:[{sheetName, head, rows}]}, 逐Sheet写入 */
    @SuppressWarnings("unchecked")
    private void writeSheets(Map<String, Object> data, String fname, HttpServletResponse resp) throws IOException {
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        List<Map<String, Object>> sheets = (List<Map<String, Object>>) data.get("sheets");
        int totalRows = 0;
        for (Map<String, Object> sh : sheets) {
            Object rs = sh.get("rows");
            totalRows += rs instanceof java.util.Collection ? ((java.util.Collection<?>) rs).size() : 0;
        }
        ExportGuard.checkRows(totalRows, "统计报表");
        ExcelWriter writer = EasyExcel.write(resp.getOutputStream()).build();
        try {
            for (int i = 0; i < sheets.size(); i++) {
                Map<String, Object> sh = sheets.get(i);
                WriteSheet sheet = EasyExcel.writerSheet(i, String.valueOf(sh.get("sheetName")))
                        .head((List<List<String>>) sh.get("head")).build();
                writer.write((List<List<Object>>) sh.get("rows"), sheet);
            }
        } finally {
            writer.finish();
        }
    }
}
