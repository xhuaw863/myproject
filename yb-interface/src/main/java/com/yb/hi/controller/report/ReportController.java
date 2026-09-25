package com.yb.hi.controller.report;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.framework.common.R;
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
 * 报表接口(纯读): Dashboard概览/收入趋势/科室收入/药品使用/就诊量/结算记录/日结记录/结算导出。
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
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("结算记录")
                .doWrite((List<List<Object>>) data.get("rows"));
    }
}
