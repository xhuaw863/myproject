package com.yb.hi.controller.mr;

import com.alibaba.excel.EasyExcel;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.ExportGuard;
import com.yb.hi.service.mr.MrReportService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 病案统计报表接口(P2): 报表类型 / 数据查询(只读多维汇总) / Excel 导出。
 * 均为读接口(机构隔离 scopeOrgId); 铁律: 仅聚合编目侧数据, 不回写临床首页。
 */
@RestController
@RequestMapping("/api/his/mr/report")
public class MrReportController {

    private final MrReportService reportService;

    public MrReportController(MrReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/types")
    public R<List<String>> types() {
        return R.ok(reportService.types());
    }

    @GetMapping("/data")
    public R<List<Map<String, Object>>> data(@RequestParam String type,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(defaultValue = "20") int limit) {
        return R.ok(reportService.query(type, from, to, limit));
    }

    /** 导出(xlsx): 与查询同 type/区间口径, 表头中文, 上限由 TOP 类报表控制。 */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam String type,
                       @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       @RequestParam(defaultValue = "20") int limit,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = reportService.exportData(type, from, to, limit);
        String title = String.valueOf(data.get("title"));
        String fname = title + "_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        ExportGuard.checkRows((java.util.Collection<?>) data.get("rows"), title);
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet(title)
                .doWrite((List<List<Object>>) data.get("rows"));
    }
}
