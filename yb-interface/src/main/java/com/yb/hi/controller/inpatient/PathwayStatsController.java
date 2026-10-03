package com.yb.hi.controller.inpatient;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.ExportGuard;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.PathwayStatsService;
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
 * 临床路径统计质控接口(/api/his/pathway/stats/*): 驾驶舱 / 四大率 / 月度趋势 /
 * 变异·退出原因柏拉图 / 效率费用三口径对比 / 病种覆盖 / 病例明细 / 多Sheet Excel导出。
 * 全部只读; 机构隔离走 scopeOrgId(牵头机构可查全医共体, 非牵头锁定本机构);
 * 口径定义见 PathwayStatsService 类注释。
 */
@RestController
@RequestMapping("/api/his/pathway/stats")
public class PathwayStatsController {

    private final PathwayStatsService statsService;
    private final OrgAccessGuard guard;

    public PathwayStatsController(PathwayStatsService statsService, OrgAccessGuard guard) {
        this.statsService = statsService;
        this.guard = guard;
    }

    /** 管理驾驶舱(实时): KPI + 本月四大率 + 在径患者一览 + 近30天变异/退出 */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(required = false) Long orgId) {
        return R.ok(statsService.overview(guard.scopeOrgId(orgId)));
    }

    /** 质控四大率: 总体 + 科室/医生/病种分维 + 入径率/完成率排行(from/to=yyyy-MM-dd 出院窗口, 缺省近90天) */
    @GetMapping("/rates")
    public R<Map<String, Object>> rates(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) Long doctorId,
                                        @RequestParam(required = false) Long templateId,
                                        @RequestParam(required = false) Long orgId) {
        return R.ok(statsService.rates(from, to, deptId, doctorId, templateId, guard.scopeOrgId(orgId)));
    }

    /** 月度趋势: 入径例数/完成率/变异率按月(缺省近12个月) */
    @GetMapping("/trend")
    public R<Map<String, Object>> trend(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) Long doctorId,
                                        @RequestParam(required = false) Long templateId,
                                        @RequestParam(required = false) Long orgId) {
        return R.ok(statsService.trend(from, to, deptId, doctorId, templateId, guard.scopeOrgId(orgId)));
    }

    /** 变异原因分类柏拉图 + 高频变异病种 Top10 */
    @GetMapping("/variance-pareto")
    public R<Map<String, Object>> variancePareto(@RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to,
                                                 @RequestParam(required = false) Long deptId,
                                                 @RequestParam(required = false) Long templateId,
                                                 @RequestParam(required = false) Long orgId) {
        return R.ok(statsService.varianceParetoWithTop(from, to, deptId, templateId, guard.scopeOrgId(orgId)));
    }

    /** 退出原因分类柏拉图 */
    @GetMapping("/exit-pareto")
    public R<Map<String, Object>> exitPareto(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) Long deptId,
                                             @RequestParam(required = false) Long templateId,
                                             @RequestParam(required = false) Long orgId) {
        return R.ok(statsService.exitPareto(from, to, deptId, templateId, guard.scopeOrgId(orgId)));
    }

    /** 效率与费用: 入径完成组/非入径同病种组/模板标准 三口径对比 + 达标符合率 */
    @GetMapping("/efficiency")
    public R<Map<String, Object>> efficiency(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) Long deptId,
                                             @RequestParam(required = false) Long templateId,
                                             @RequestParam(required = false) Long orgId) {
        return R.ok(statsService.efficiency(from, to, deptId, templateId, guard.scopeOrgId(orgId)));
    }

    /** 病种覆盖: 启用模板数/覆盖科室数/各病种入径占其出院比 */
    @GetMapping("/coverage")
    public R<Map<String, Object>> coverage(@RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) Long orgId) {
        return R.ok(statsService.coverage(from, to, guard.scopeOrgId(orgId)));
    }

    /** 病例明细钻取(分页): 入径/状态/天数/变异项数/实际住院日/实际费用 */
    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Long deptId,
                                         @RequestParam(required = false) Long doctorId,
                                         @RequestParam(required = false) Long templateId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false, defaultValue = "1") Integer page,
                                         @RequestParam(required = false, defaultValue = "20") Integer size,
                                         @RequestParam(required = false) Long orgId) {
        return R.ok(statsService.detail(from, to, deptId, doctorId, templateId, status, page, size,
                guard.scopeOrgId(orgId)));
    }

    // ==================== 多Sheet Excel 导出 ====================

    /** 导出统计质控工作簿(xlsx 九Sheet: 概览/科室/医生/病种/趋势/变异柏拉图/退出柏拉图/效率对比/明细前1000) */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       @RequestParam(required = false) Long deptId,
                       @RequestParam(required = false) Long doctorId,
                       @RequestParam(required = false) Long templateId,
                       @RequestParam(required = false) Long orgId,
                       HttpServletResponse resp) throws IOException {
        Long scope = guard.scopeOrgId(orgId);
        Map<String, Object> rates = statsService.rates(from, to, deptId, doctorId, templateId, scope);
        Map<String, Object> trend = statsService.trend(from, to, deptId, doctorId, templateId, scope);
        Map<String, Object> varP = statsService.varianceParetoWithTop(from, to, deptId, templateId, scope);
        Map<String, Object> exitP = statsService.exitPareto(from, to, deptId, templateId, scope);
        Map<String, Object> eff = statsService.efficiency(from, to, deptId, templateId, scope);
        Map<String, Object> cov = statsService.coverage(from, to, scope);
        Map<String, Object> det = statsService.detail(from, to, deptId, doctorId, templateId, null, 1, 1000, scope);

        List<Map<String, Object>> sheets = new ArrayList<>();
        // 概览(窗口与总体率值一览)
        Map<String, Object> overall = asMap(rates.get("overall"));
        List<List<Object>> ovRows = new ArrayList<>();
        ovRows.add(row("统计窗口(出院口径)", rates.get("from") + " ~ " + rates.get("to")));
        ovRows.add(row("出院目标类目病例数(入径率分母)", overall.get("denominator")));
        ovRows.add(row("入径病例数", overall.get("entered")));
        ovRows.add(row("入径率(%)", pct(overall.get("enterRate"))));
        ovRows.add(row("完成率(%)", pct(overall.get("completeRate"))));
        ovRows.add(row("退出率(%)", pct(overall.get("exitRate"))));
        ovRows.add(row("病例变异率(%)", pct(overall.get("caseVarianceRate"))));
        ovRows.add(row("项次变异率(%)", pct(overall.get("itemVarianceRate"))));
        sheets.add(sheet("概览", head("指标", "数值"), ovRows));
        sheets.add(sheet("科室四大率", ratesHead(), ratesRows(asList(rates.get("byDept")))));
        sheets.add(sheet("医生四大率", ratesHead(), ratesRows(asList(rates.get("byDoctor")))));
        sheets.add(sheet("病种四大率", ratesHead(), ratesRows(asList(rates.get("byDisease")))));
        List<List<Object>> trendRows = new ArrayList<>();
        for (Map<String, Object> m : asList(trend.get("months"))) {
            trendRows.add(row(m.get("ym"), m.get("enterCount"), pct(m.get("completeRate")),
                    pct(m.get("exitRate")), pct(m.get("caseVarianceRate"))));
        }
        sheets.add(sheet("月度趋势", head("月份", "入径例数", "完成率(%)", "退出率(%)", "病例变异率(%)"), trendRows));
        sheets.add(sheet("变异原因柏拉图", paretoHead(), paretoRows(asList(varP.get("items")))));
        sheets.add(sheet("退出原因柏拉图", paretoHead(), paretoRows(asList(exitP.get("items")))));
        // 效率与费用三口径对比(行=口径, 列=例数/平均住院日/平均费用)
        Map<String, Object> f = asMap(eff.get("pathwayFinished"));
        Map<String, Object> nf = asMap(eff.get("nonPathwaySameDisease"));
        Map<String, Object> std = asMap(eff.get("templateStandard"));
        Map<String, Object> dif = asMap(eff.get("diff"));
        Map<String, Object> comp = asMap(eff.get("standardCompliance"));
        List<List<Object>> effRows = new ArrayList<>();
        effRows.add(row("入径完成组", f.get("caseCount"), f.get("avgLos"), money(f.get("avgCost")), "", ""));
        effRows.add(row("非入径同病种出院组", nf.get("caseCount"), nf.get("avgLos"), money(nf.get("avgCost")), "", ""));
        effRows.add(row("模板标准组(加权)", "", std.get("stdLos"), money(std.get("stdCost")), "", ""));
        effRows.add(row("差值(完成组-标准)", "", dif.get("losVsStandard"), money(dif.get("costVsStandard")), "", ""));
        effRows.add(row("差值(完成组-非入径)", "", dif.get("losVsNonPathway"), money(dif.get("costVsNonPathway")), "", ""));
        effRows.add(row("标准达标符合率", comp.get("totalCnt"), "", "", pct(comp.get("complianceRate")), ""));
        sheets.add(sheet("效率费用对比", head("口径", "例数", "平均住院日(天)", "平均费用(元)", "达标符合率(%)", "备注"), effRows));
        List<List<Object>> detRows = new ArrayList<>();
        for (Map<String, Object> d : asList(det.get("records"))) {
            detRows.add(row(d.get("inpNo"), d.get("patientName"), d.get("deptName"), d.get("doctorName"),
                    d.get("pathwayName"), d.get("startDate"), d.get("endDate"), statusName(d.get("status")),
                    d.get("currentDay"), d.get("varianceCount"), d.get("losDays"), money(d.get("totalCost")),
                    d.get("dischargeDate")));
        }
        sheets.add(sheet("病例明细", head("住院号", "患者", "科室", "主管医生", "路径", "入径日期", "出径日期",
                "状态", "当前天数", "变异项数", "实际住院日", "实际费用", "出院日期"), detRows));
        writeSheets(sheets, "临床路径统计质控_" + LocalDate.now() + ".xlsx", resp);
    }

    private static List<List<String>> ratesHead() {
        return head("维度名称", "出院类目病例", "入径例数", "入径率(%)", "完成率(%)", "退出率(%)",
                "病例变异率(%)", "项次变异率(%)");
    }

    private static List<List<Object>> ratesRows(List<Map<String, Object>> rows) {
        List<List<Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(row(r.get("dimName"), r.get("denominator"), r.get("entered"), pct(r.get("enterRate")),
                    pct(r.get("completeRate")), pct(r.get("exitRate")), pct(r.get("caseVarianceRate")),
                    pct(r.get("itemVarianceRate"))));
        }
        return out;
    }

    private static List<List<String>> paretoHead() {
        return head("原因分类码", "原因分类", "例数", "占比(%)", "累计占比(%)");
    }

    private static List<List<Object>> paretoRows(List<Map<String, Object>> items) {
        List<List<Object>> out = new ArrayList<>();
        for (Map<String, Object> it : items) {
            out.add(row(it.get("code"), it.get("name"), it.get("cnt"), pct(it.get("rate")),
                    pct(it.get("cumulativeRate"))));
        }
        return out;
    }

    private static String statusName(Object status) {
        int s = status == null ? 0 : ((Number) status).intValue();
        switch (s) {
            case 1: return "进行中";
            case 2: return "已完成";
            case 3: return "已退出";
            case 4: return "暂停";
            default: return "";
        }
    }

    /** 多Sheet Excel 导出公用写法(与 InpReportController 同范式): sheets=[{sheetName, head, rows}]。 */
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
        ExportGuard.checkRows(totalRows, "临床路径统计质控");
        ExcelWriter writer = EasyExcel.write(resp.getOutputStream()).build();
        try {
            for (int i = 0; i < sheets.size(); i++) {
                Map<String, Object> sh = sheets.get(i);
                WriteSheet ws = EasyExcel.writerSheet(i, String.valueOf(sh.get("sheetName")))
                        .head((List<List<String>>) sh.get("head")).build();
                writer.write((List<List<Object>>) sh.get("rows"), ws);
            }
        } finally {
            writer.finish();
        }
    }

    private static List<List<String>> head(String... cols) {
        List<List<String>> h = new ArrayList<>();
        for (String c : cols) {
            List<String> col = new ArrayList<>();
            col.add(c);
            h.add(col);
        }
        return h;
    }

    private static Map<String, Object> sheet(String name, List<List<String>> head, List<List<Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sheetName", name);
        m.put("head", head);
        m.put("rows", rows);
        return m;
    }

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
}
