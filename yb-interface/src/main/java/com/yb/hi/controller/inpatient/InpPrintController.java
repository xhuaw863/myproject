package com.yb.hi.controller.inpatient;

import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.Utilities;
import com.yb.hi.dto.inpatient.PrintTemplateDTO;
import com.yb.hi.entity.inpatient.HisPrintTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrArchiveService;
import com.yb.hi.service.inpatient.InpPrintService;
import com.yb.hi.service.inpatient.PdfExportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * 住院打印接口: 打印模板管理(列表/详情/保存) + 六类单据HTML渲染(日清单/结算单/医嘱单/护理记录/病历/腕带)
 * + 单据PDF导出(/pdf/{type}, 复用HTML渲染结果转OpenPDF二进制下载)。
 * 渲染结果为完整HTML字符串, 前端打印窗口/iframe 直接输出; 数据按业务ID定位(租户插件自动隔离)。
 */
@RestController
@RequestMapping("/api/his/inp/print")
public class InpPrintController {

    private final InpPrintService inpPrintService;
    private final PdfExportService pdfExportService;
    private final EmrArchiveService emrArchiveService;

    public InpPrintController(InpPrintService inpPrintService, PdfExportService pdfExportService,
                              EmrArchiveService emrArchiveService) {
        this.inpPrintService = inpPrintService;
        this.pdfExportService = pdfExportService;
        this.emrArchiveService = emrArchiveService;
    }

    /** 模板列表(按类型筛选, type 缺省查全部启用模板) */
    @GetMapping("/templates")
    public R<List<HisPrintTemplate>> templates(@RequestParam(required = false) Integer type) {
        return inpPrintService.listTemplates(type);
    }

    /** 获取模板详情(按模板编码, 如 PRINT_DAILY_BILL) */
    @GetMapping("/template/{code}")
    public R<HisPrintTemplate> template(@PathVariable String code) {
        return inpPrintService.getTemplate(code);
    }

    /** 保存模板(编码已存在则更新且版本号+1, 否则新增) */
    @PostMapping("/template")
    public R<HisPrintTemplate> saveTemplate(@RequestBody PrintTemplateDTO dto) {
        return inpPrintService.saveTemplate(dto);
    }

    /** 渲染住院每日费用清单HTML */
    @GetMapping("/render/daily-bill")
    public R<String> renderDailyBill(@RequestParam Long visitId,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return inpPrintService.renderDailyBill(visitId, date);
    }

    /** 渲染住院结算单HTML */
    @GetMapping("/render/settlement")
    public R<String> renderSettlement(@RequestParam Long settleId) {
        return inpPrintService.renderSettlement(settleId);
    }

    /** 渲染医嘱单HTML(orderType: 1长期 2临时) */
    @GetMapping("/render/orders")
    public R<String> renderOrders(@RequestParam Long visitId,
                                  @RequestParam(required = false, defaultValue = "1") Integer orderType) {
        return inpPrintService.renderOrders(visitId, orderType);
    }

    /** 渲染护理记录单HTML(日期区间缺省最近7天) */
    @GetMapping("/render/nursing")
    public R<String> renderNursing(@RequestParam Long visitId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return inpPrintService.renderNursingRecord(visitId, startDate, endDate);
    }

    /** 渲染病历HTML(structureData 结构化字段优先) */
    @GetMapping("/render/emr")
    public R<String> renderEmr(@RequestParam Long recordId) {
        return inpPrintService.renderEmr(recordId);
    }

    /** 患者腕带渲染端点分隔(供PDF端点复用渲染结果) */
    @GetMapping("/render/wristband")
    public R<String> renderWristband(@RequestParam Long visitId) {
        return inpPrintService.renderWristband(visitId);
    }

    /* ==================== PDF导出 ==================== */

    /**
     * 导出住院单据PDF(OpenPDF): 复用 renderXxx() 的HTML渲染结果转PDF二进制下载。
     * type: daily-bill(日清单, 需visitId+date) / settlement(结算单, 需settleId) /
     * orders(医嘱单, 需visitId+orderType 1长期2临时) / nursing(护理记录, 需visitId+区间) /
     * emr(病历, 需recordId) / wristband(腕带, 需visitId, 200x25mm小票) /
     * temp-chart(体温单, 需visitId+区间, 后端直查体征构建A4横向)。
     * case-page(病案首页)尚未接入打印模板, 明确报400。
     */
    @GetMapping("/pdf/{type}")
    public void exportPdf(@PathVariable String type,
                          @RequestParam(required = false) Long visitId,
                          @RequestParam(required = false) Long settleId,
                          @RequestParam(required = false) Long recordId,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                          @RequestParam(required = false, defaultValue = "1") Integer orderType,
                          HttpServletResponse response) throws IOException {
        String t = type == null ? "" : type.trim();
        byte[] bytes;
        String title;
        switch (t) {
            case "daily-bill": {
                LocalDate day = date != null ? date : LocalDate.now();
                bytes = pdfExportService.generatePdf(
                        inpPrintService.renderDailyBill(visitId, day).getData(), "住院患者每日费用清单",
                        PageSize.A4, false);
                title = "住院每日费用清单_" + day;
                break;
            }
            case "settlement": {
                bytes = pdfExportService.generatePdf(
                        inpPrintService.renderSettlement(settleId).getData(), "住院费用结算单",
                        PageSize.A4, false);
                title = "住院费用结算单";
                break;
            }
            case "orders": {
                int ot = orderType != null && orderType == 2 ? 2 : 1;
                bytes = pdfExportService.generatePdf(
                        inpPrintService.renderOrders(visitId, ot).getData(), ot == 1 ? "长期医嘱单" : "临时医嘱单",
                        PageSize.A4, false);
                title = ot == 1 ? "长期医嘱单" : "临时医嘱单";
                break;
            }
            case "nursing": {
                bytes = pdfExportService.generatePdf(
                        inpPrintService.renderNursingRecord(visitId, startDate, endDate).getData(), "护理记录单",
                        PageSize.A4, false);
                title = "护理记录单";
                break;
            }
            case "emr": {
                bytes = pdfExportService.generatePdf(
                        inpPrintService.renderEmr(recordId).getData(), "病历打印",
                        PageSize.A4, false);
                title = "病历";
                break;
            }
            case "wristband": {
                Rectangle paper = new Rectangle(Utilities.millimetersToPoints(200),
                        Utilities.millimetersToPoints(25));
                bytes = pdfExportService.generatePdf(
                        inpPrintService.renderWristband(visitId).getData(), "患者腕带",
                        paper, true);
                title = "患者腕带";
                break;
            }
            case "temp-chart": {
                bytes = pdfExportService.generateTempChartPdf(visitId, startDate, endDate);
                title = "体温单";
                break;
            }
            case "case-page": {
                throw new BizException(400, "病案首页打印模板尚未接入, 暂不支持PDF导出");
            }
            default:
                throw new BizException(400, "未知PDF导出类型: " + type);
        }
        response.setContentType("application/pdf");
        response.setCharacterEncoding("UTF-8");
        String fname = title + "_" + LocalDate.now() + ".pdf";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    /* ==================== P7a-3 病历PDF(生成/批量/选页) ==================== */

    /**
     * 病历PDF预览/下载: Tiptap content 解密 → 语义HTML → A4 PDF(页眉机构/科室, 页脚页码/打印时间)。
     * download=false(缺省) inline 内联预览(浏览器PDF查看器); download=true 附件下载。
     */
    @GetMapping("/emr-pdf/{recordId}")
    public void emrPdf(@PathVariable Long recordId,
                       @RequestParam(required = false, defaultValue = "false") Boolean download,
                       HttpServletResponse response) throws IOException {
        byte[] pdf = pdfExportService.generateFromTiptap(recordId);
        writePdfResponse(response, pdf, "病历_" + recordId + "_" + LocalDate.now() + ".pdf",
                Boolean.TRUE.equals(download));
    }

    /**
     * 批量病历PDF(合并单文档): recordIds 病历ID列表(JSON数组, 单次≤60份), 逐份生成后 PdfCopy 合并。
     * 单份失败自动跳过(全部失败报错)。
     */
    @PostMapping("/batch-pdf")
    public void batchPdf(@RequestBody List<Long> recordIds, HttpServletResponse response) throws IOException {
        byte[] pdf = pdfExportService.batchGenerate(recordIds);
        writePdfResponse(response, pdf, "批量病历_" + LocalDate.now() + ".pdf", true);
    }

    /**
     * 病历选页导出: 生成完整PDF后仅输出 [startPage, endPage] 页区间(超出总页数自动收敛)。
     * 参数经 query string 传递(recordId/startPage/endPage), 固定附件下载。
     */
    @PostMapping("/selective")
    public void selectivePdf(@RequestParam Long recordId,
                             @RequestParam int startPage,
                             @RequestParam int endPage,
                             HttpServletResponse response) throws IOException {
        byte[] pdf = pdfExportService.selectivePages(recordId, startPage, endPage);
        writePdfResponse(response, pdf,
                "病历选页_" + recordId + "_" + startPage + "-" + endPage + ".pdf", true);
    }

    /**
     * 归档PDF防篡改回查: 重读 pdf_path 落盘文件重算 SHA-256, 与归档时持久化的 pdf_sha256 比对。
     * 返回 {valid, expected, actual, generatedTime}(可选 message), 供归档/病案借阅页展示"防篡改校验"。
     */
    @GetMapping("/emr-pdf/{recordId}/verify")
    public R<Map<String, Object>> verifyEmrPdf(@PathVariable Long recordId) {
        return R.ok(emrArchiveService.verifyArchivePdf(recordId));
    }

    /** PDF 响应统一写流: attachment(下载)/inline(预览) + UTF-8 文件名 + 跨域暴露头。 */
    private void writePdfResponse(HttpServletResponse response, byte[] bytes, String fname, boolean attachment)
            throws IOException {
        response.setContentType("application/pdf");
        response.setCharacterEncoding("UTF-8");
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        response.setHeader("Content-Disposition",
                (attachment ? "attachment" : "inline") + "; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }
}
