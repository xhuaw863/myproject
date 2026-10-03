package com.yb.hi.controller.inpatient;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.yb.hi.dto.inpatient.ReportQueryDTO;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.ExportGuard;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.InpReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院报表接口: 床位统计/床位周转/费用分析/科室经营/住院日统计/DRG-DIP分析六类统计报表
 * + 五类报表 Excel 导出 + 报表钻取(费用明细/在院患者)。
 * 机构隔离: 查询走 scopeOrgId(牵头机构可查全院, 非牵头锁定本机构)。
 */
@RestController
@RequestMapping("/api/his/inp/report")
public class InpReportController {

    private final InpReportService inpReportService;
    private final OrgAccessGuard guard;

    public InpReportController(InpReportService inpReportService, OrgAccessGuard guard) {
        this.inpReportService = inpReportService;
        this.guard = guard;
    }

    /** 床位统计(总床位数/占用数/使用率, 按科室与病区分组) */
    @GetMapping("/bed-stats")
    public R<Map<String, Object>> bedStats(ReportQueryDTO query,
                                           @RequestParam(required = false) Long orgId) {
        return inpReportService.getBedStats(query, guard.scopeOrgId(orgId));
    }

    /** 床位周转(出院人次/平均住院日/周转率/周转次数, 按科室分组) */
    @GetMapping("/bed-turnover")
    public R<Map<String, Object>> bedTurnover(ReportQueryDTO query,
                                              @RequestParam(required = false) Long orgId) {
        return inpReportService.getBedTurnover(query, guard.scopeOrgId(orgId));
    }

    /** 费用分析(费用构成/人均费用/每日趋势/科室TOP10) */
    @GetMapping("/fee-analysis")
    public R<Map<String, Object>> feeAnalysis(ReportQueryDTO query,
                                              @RequestParam(required = false) Long orgId) {
        return inpReportService.getFeeAnalysis(query, guard.scopeOrgId(orgId));
    }

    /** 科室经营(收入/出入院/手术数/药占比/材料占比/平均住院日, 按科室汇总) */
    @GetMapping("/dept-business")
    public R<Map<String, Object>> deptBusiness(ReportQueryDTO query,
                                               @RequestParam(required = false) Long orgId) {
        return inpReportService.getDeptBusiness(query, guard.scopeOrgId(orgId));
    }

    /** 住院日统计(指定日期出入院/在院/转科/死亡当日汇总 + 30天趋势, date 缺省当天) */
    @GetMapping("/daily-summary")
    public R<Map<String, Object>> dailySummary(ReportQueryDTO query,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                               @RequestParam(required = false) Long orgId) {
        if (date != null && query.getStartDate() == null) {
            query.setStartDate(date);
        }
        return inpReportService.getDailySummary(query, guard.scopeOrgId(orgId));
    }

    /** DRG/DIP分析(期内结算按分组编码的分布与组均费用) */
    @GetMapping("/drg-analysis")
    public R<Map<String, Object>> drgAnalysis(ReportQueryDTO query,
                                              @RequestParam(required = false) Long orgId) {
        return inpReportService.getDrgAnalysis(query, guard.scopeOrgId(orgId));
    }

    // ==================== Excel 导出 ====================

    /** 导出床位统计(xlsx, 双Sheet: 科室床位统计+病区明细), 当前快照 */
    @GetMapping("/bed-stats/export")
    public void exportBedStats(ReportQueryDTO query, @RequestParam(required = false) Long orgId,
                               HttpServletResponse resp) throws IOException {
        Map<String, Object> data = inpReportService.getBedStats(query, guard.scopeOrgId(orgId)).getData();
        List<List<Object>> deptRows = new ArrayList<>();
        for (Map<String, Object> d : asList(data.get("byDept"))) {
            deptRows.add(row(d.get("deptName"), d.get("total"), d.get("occupied"),
                    freeBeds(d.get("total"), d.get("occupied")), pct(d.get("rate"))));
        }
        List<List<Object>> wardRows = new ArrayList<>();
        for (Map<String, Object> w : asList(data.get("byWard"))) {
            wardRows.add(row(w.get("wardName"), w.get("deptName"), w.get("total"), w.get("occupied"),
                    freeBeds(w.get("total"), w.get("occupied")), pct(w.get("rate"))));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheet("科室床位统计", head("科室", "总床位", "占用床位", "空闲床位", "使用率(%)"), deptRows));
        sheets.add(sheet("病区明细", head("病区", "科室", "总床位", "占用床位", "空闲床位", "使用率(%)"), wardRows));
        writeSheets(sheets, "住院报表-床位统计_" + LocalDate.now() + ".xlsx", resp);
    }

    /** 导出费用分析(xlsx, 三Sheet: 费用构成+每日趋势+科室TOP10), 区间缺省最近30天 */
    @GetMapping("/fee-analysis/export")
    public void exportFeeAnalysis(ReportQueryDTO query, @RequestParam(required = false) Long orgId,
                                  HttpServletResponse resp) throws IOException {
        Map<String, Object> data = inpReportService.getFeeAnalysis(query, guard.scopeOrgId(orgId)).getData();
        List<List<Object>> typeRows = new ArrayList<>();
        for (Map<String, Object> t : asList(data.get("byType"))) {
            typeRows.add(row(t.get("feeTypeName"), money(t.get("totalAmount")), money(t.get("percentage"))));
        }
        typeRows.add(row("合计", money(data.get("totalAmount")), 100));
        typeRows.add(row("患者数(人)", data.get("patientCount"), ""));
        typeRows.add(row("人均费用(元)", money(data.get("avgCostPerPatient")), ""));
        List<List<Object>> trendRows = new ArrayList<>();
        for (Map<String, Object> t : asList(data.get("dailyTrend"))) {
            trendRows.add(row(t.get("date"), money(t.get("amount"))));
        }
        List<List<Object>> deptRows = new ArrayList<>();
        for (Map<String, Object> d : asList(data.get("topDepts"))) {
            deptRows.add(row(d.get("deptName"), money(d.get("totalAmount"))));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheet("费用构成", head("费用类别", "费用金额(元)", "占比(%)"), typeRows));
        sheets.add(sheet("每日趋势", head("日期", "费用金额(元)"), trendRows));
        sheets.add(sheet("科室TOP10", head("科室", "费用金额(元)"), deptRows));
        writeSheets(sheets, "住院报表-费用分析_" + LocalDate.now() + ".xlsx", resp);
    }

    /** 导出科室经营(xlsx, 单Sheet: 收入/出入院/手术/药占比/材占比/平均住院日), 区间缺省最近30天 */
    @GetMapping("/dept-business/export")
    public void exportDeptBusiness(ReportQueryDTO query, @RequestParam(required = false) Long orgId,
                                   HttpServletResponse resp) throws IOException {
        Map<String, Object> data = inpReportService.getDeptBusiness(query, guard.scopeOrgId(orgId)).getData();
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> d : asList(data.get("items"))) {
            rows.add(row(d.get("deptName"), money(d.get("totalIncome")), d.get("admitCount"),
                    d.get("dischargeCount"), d.get("surgeryCount"), pct(d.get("drugRatio")),
                    pct(d.get("materialRatio")), d.get("avgLos")));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheet("科室经营", head("科室", "总收入(元)", "入院人次", "出院人次", "手术人次",
                "药占比(%)", "材占比(%)", "平均住院日(天)"), rows));
        writeSheets(sheets, "住院报表-科室经营_" + LocalDate.now() + ".xlsx", resp);
    }

    /** 导出住院日统计(xlsx, 双Sheet: 当日汇总+近30天趋势), date 缺省当天 */
    @GetMapping("/daily-summary/export")
    public void exportDailySummary(ReportQueryDTO query, @RequestParam(required = false) Long orgId,
                                   HttpServletResponse resp) throws IOException {
        Map<String, Object> data = inpReportService.getDailySummary(query, guard.scopeOrgId(orgId)).getData();
        Map<String, Object> today = asMap(data.get("today"));
        List<List<Object>> todayRows = new ArrayList<>();
        todayRows.add(row(data.get("date"), today.get("newAdmit"), today.get("discharge"),
                today.get("inHospital"), today.get("transferIn"), today.get("transferOut"), today.get("death")));
        List<List<Object>> trendRows = new ArrayList<>();
        for (Map<String, Object> t : asList(data.get("trend"))) {
            trendRows.add(row(t.get("date"), t.get("newAdmit"), t.get("discharge"), t.get("inHospital")));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheet("当日汇总", head("日期", "新入院", "出院", "在院", "转入", "转出", "死亡"), todayRows));
        sheets.add(sheet("近30天趋势", head("日期", "新入院", "出院", "在院"), trendRows));
        writeSheets(sheets, "住院报表-住院日统计_" + LocalDate.now() + ".xlsx", resp);
    }

    /** 导出DRG/DIP分析(xlsx, 双Sheet: DRG分组+DIP分组), 区间缺省最近30天 */
    @GetMapping("/drg-analysis/export")
    public void exportDrgAnalysis(ReportQueryDTO query, @RequestParam(required = false) Long orgId,
                                  HttpServletResponse resp) throws IOException {
        Map<String, Object> data = inpReportService.getDrgAnalysis(query, guard.scopeOrgId(orgId)).getData();
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheet("DRG分组", head("DRG分组编码", "结算笔数", "组均费用(元)"),
                groupRows(data.get("groupDistribution"))));
        sheets.add(sheet("DIP分组", head("DIP编码", "结算笔数", "组均费用(元)"),
                groupRows(data.get("dipDistribution"))));
        writeSheets(sheets, "住院报表-DRG-DIP分析_" + LocalDate.now() + ".xlsx", resp);
    }

    // ==================== 报表钻取 ====================

    /** 报表钻取分页明细: type=fee/dept费用明细 | bed当前在院患者列表 */
    @GetMapping("/drill-down")
    public R<Map<String, Object>> drillDown(@RequestParam String type,
                                            @RequestParam(required = false) Long deptId,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                            @RequestParam(required = false) Long orgId,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return inpReportService.drillDown(type, deptId, dateFrom, dateTo, guard.scopeOrgId(orgId), page, size);
    }

    // ==================== 导出组装辅助 ====================

    /** 多Sheet Excel 导出公用写法: sheets=[{sheetName, head, rows}] 逐Sheet写入。 */
    @SuppressWarnings("unchecked")
    private void writeSheets(List<Map<String, Object>> sheets, String fname, HttpServletResponse resp)
            throws IOException {
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        int totalRows = 0;
        for (Map<String, Object> sh : sheets) {
            Object rs = sh.get("rows");
            totalRows += rs instanceof java.util.Collection ? ((java.util.Collection<?>) rs).size() : 0;
        }
        ExportGuard.checkRows(totalRows, "住院报表");
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

    /** DRG/DIP分组分布转导出行(空数据补一行提示)。 */
    private static List<List<Object>> groupRows(Object distribution) {
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> g : asList(distribution)) {
            rows.add(row(g.get("groupCode"), g.get("count"), money(g.get("avgCost"))));
        }
        if (rows.isEmpty()) {
            rows.add(row("暂无分组数据(需结算单回写分组编码)", "", ""));
        }
        return rows;
    }

    /** 表头数组转 EasyExcel 动态表头(List<List<String>>)。 */
    private static List<List<String>> head(String... cols) {
        List<List<String>> h = new ArrayList<>();
        for (String c : cols) {
            List<String> col = new ArrayList<>();
            col.add(c);
            h.add(col);
        }
        return h;
    }

    /** Sheet 描述 {sheetName, head, rows}。 */
    private static Map<String, Object> sheet(String name, List<List<String>> head, List<List<Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sheetName", name);
        m.put("head", head);
        m.put("rows", rows);
        return m;
    }

    /** 行组装(空值转空串)。 */
    private static List<Object> row(Object... cells) {
        List<Object> r = new ArrayList<>();
        for (Object c : cells) {
            r.add(c == null ? "" : c);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object v) {
        return v == null ? new ArrayList<>() : (List<Map<String, Object>>) v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v == null ? new LinkedHashMap<>() : (Map<String, Object>) v;
    }

    /** 金额: 保留2位小数(空值转0)。 */
    private static Object money(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return (v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal(v.toString()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 比率: 0-1小数 ×100 保留2位(空值转0)。 */
    private static Object pct(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal b = v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal(v.toString());
        return b.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    /** 空闲床位 = 总床位 - 占用床位。 */
    private static long freeBeds(Object total, Object occupied) {
        long t = total == null ? 0L : ((Number) total).longValue();
        long o = occupied == null ? 0L : ((Number) occupied).longValue();
        return t - o;
    }
}
