package com.yb.hi.controller.pharmacy;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.pharmacy.PharmacyStatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 药房统计查询接口(纯只读, 2026-11 药房整合增强): 用量与消耗 / 医保合规 / 处方与退药质量 三报表的查询 + EasyExcel 导出。
 * 发药工作量总览复用既有 /api/his/report/pharmacy-stats(ReportController), 本控制器聚焦新增四维中的用量/医保/质量。
 * 读隔离: 非牵头机构强制本院(guard.scopeOrgId 锁定), 牵头机构 orgId=null 可查全医共体; pharmacyId 可选下钻单药房。
 * 均为 GET, 不触写门禁(BizRoleInterceptor WRITE_ROLES 仅拦截写方法)。
 */
@RestController
@RequestMapping("/api/his/pharmacy/stat")
public class PharmacyStatController {

    private final PharmacyStatService statService;
    private final OrgAccessGuard guard;

    public PharmacyStatController(PharmacyStatService statService, OrgAccessGuard guard) {
        this.statService = statService;
        this.guard = guard;
    }

    /* ---------------- 用量与消耗(stat-usage) ---------------- */

    /** 消耗概况: 本期总额/净量/品种数 + 门诊住院拆分 + 环比上一等长周期 */
    @GetMapping("/usage/summary")
    public R<Map<String, Object>> usageSummary(@RequestParam(required = false) Long orgId,
                                               @RequestParam(required = false) Long pharmacyId,
                                               @RequestParam(required = false) String startDate,
                                               @RequestParam(required = false) String endDate) {
        return R.ok(statService.usageSummary(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate));
    }

    /** 消耗维度排行(ABC): dimType=generic/major_class/manufacturer/dept, 按金额降序含占比/累计/ABC */
    @GetMapping("/usage/dim")
    public R<List<Map<String, Object>>> usageDim(@RequestParam(required = false) Long orgId,
                                                 @RequestParam(required = false) Long pharmacyId,
                                                 @RequestParam(required = false) String startDate,
                                                 @RequestParam(required = false) String endDate,
                                                 @RequestParam(defaultValue = "generic") String dimType,
                                                 @RequestParam(defaultValue = "0") int limit) {
        return R.ok(statService.usageDim(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate, dimType, limit));
    }

    /** 消耗 Top 明细(通用名+大类+厂家三维透视), 默认前 50 行 */
    @GetMapping("/usage/detail")
    public R<List<Map<String, Object>>> usageDetail(@RequestParam(required = false) Long orgId,
                                                    @RequestParam(required = false) Long pharmacyId,
                                                    @RequestParam(required = false) String startDate,
                                                    @RequestParam(required = false) String endDate,
                                                    @RequestParam(defaultValue = "50") int topN) {
        return R.ok(statService.usageDetail(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate, topN));
    }

    /** 用量报表导出(双Sheet: 消耗排行 + Top明细) */
    @GetMapping("/usage/export")
    public void exportUsage(@RequestParam(required = false) Long orgId,
                            @RequestParam(required = false) Long pharmacyId,
                            @RequestParam(required = false) String startDate,
                            @RequestParam(required = false) String endDate,
                            @RequestParam(defaultValue = "major_class") String dimType,
                            HttpServletResponse resp) throws IOException {
        Map<String, Object> data = statService.exportUsage(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate, dimType);
        writeSheets(data, "药品消耗分析_" + LocalDate.now() + ".xlsx", resp);
    }

    /* ---------------- 医保合规分析(stat-yb) ---------------- */

    /** 医保合规指标卡 + 抗菌分级/甲乙丙分布 + 药占比/次均费用 */
    @GetMapping("/yb/summary")
    public R<Map<String, Object>> ybSummary(@RequestParam(required = false) Long orgId,
                                            @RequestParam(required = false) Long pharmacyId,
                                            @RequestParam(required = false) String startDate,
                                            @RequestParam(required = false) String endDate) {
        return R.ok(statService.ybSummary(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate));
    }

    /** 医保合规导出(三Sheet) */
    @GetMapping("/yb/export")
    public void exportYb(@RequestParam(required = false) Long orgId,
                         @RequestParam(required = false) Long pharmacyId,
                         @RequestParam(required = false) String startDate,
                         @RequestParam(required = false) String endDate,
                         HttpServletResponse resp) throws IOException {
        Map<String, Object> data = statService.exportYb(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate);
        writeSheets(data, "医保合规分析_" + LocalDate.now() + ".xlsx", resp);
    }

    /* ---------------- 处方与退药质量(stat-quality) ---------------- */

    /** 审方通过率/驳回原因 + 退药率/退药原因 + 处方点评Top */
    @GetMapping("/quality/summary")
    public R<Map<String, Object>> qualitySummary(@RequestParam(required = false) Long orgId,
                                                 @RequestParam(required = false) Long pharmacyId,
                                                 @RequestParam(required = false) String startDate,
                                                 @RequestParam(required = false) String endDate) {
        return R.ok(statService.qualitySummary(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate));
    }

    /** 处方与退药质量导出(四Sheet) */
    @GetMapping("/quality/export")
    public void exportQuality(@RequestParam(required = false) Long orgId,
                              @RequestParam(required = false) Long pharmacyId,
                              @RequestParam(required = false) String startDate,
                              @RequestParam(required = false) String endDate,
                              HttpServletResponse resp) throws IOException {
        Map<String, Object> data = statService.exportQuality(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate);
        writeSheets(data, "处方与退药质量_" + LocalDate.now() + ".xlsx", resp);
    }

    /* ---------------- 多Sheet Excel 公用写法(与 ReportController 一致) ---------------- */

    @SuppressWarnings("unchecked")
    private void writeSheets(Map<String, Object> data, String fname, HttpServletResponse resp) throws IOException {
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        List<Map<String, Object>> sheets = (List<Map<String, Object>>) data.get("sheets");
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
