package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.Utilities;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.PdfWriter;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.service.emr.EmrDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 住院单据PDF导出服务(OpenPDF): 将 {@link InpPrintService} 渲染出的打印HTML转成PDF。
 *
 * HTMLWorker 对本项目模板(flex布局/@page/repeating-gradient等CSS)解析能力不足,
 * 故采用"轻量自解析"策略: 按文档流顺序扫描 body 下的 div/table 顶级元素,
 * 语义类名(div.doc-title/doc-sub/info/total/sign/memo/grid-note/band)转段落,
 * table 转 PdfPTable(支持 colspan/rowspan 与表头灰底, 列宽按 th 的 width% 近似);
 * 未识别的容器 div(如 .doc)递归扫描其内部, 保证新模板不丢内容。
 *
 * 中文字体降级链(静态只加载一次):
 * STSong-Light(需 iTextAsian) → Windows 宋体 simsun.ttc → 黑体/雅黑/仿宋 →
 * Linux 常见 CJK 路径 → Helvetica(兜底, 中文不可见但保证PDF可生成)。
 *
 * 体温单无后端HTML渲染(前端自渲染), 此处直接查 his_inp_nursing_record(record_type=1)
 * 生命体征记录构建 A4 横向 PDF, 与前端 buildTempChartHtml 同口径。
 */
@Slf4j
@Service
public class PdfExportService {

    /** 常规单据页边距(pt) */
    private static final float[] DOC_MARGIN = {42f, 42f, 40f, 40f};

    /** 表头底色/正文灰 */
    private static final Color HEADER_BG = new Color(0xEE, 0xEE, 0xEE);
    private static final Color TEXT_DARK = new Color(0x33, 0x33, 0x33);
    private static final Color TEXT_GRAY = new Color(0x66, 0x66, 0x66);

    /** 分钟级时间(体温记录/打印时间) */
    private static final DateTimeFormatter DT_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 病历PDF页边距(pt): {左, 右, 上(留页眉), 下(留页脚)} */
    private static final float[] EMR_MARGIN = {56f, 56f, 76f, 52f};

    /** 归档章红色 */
    private static final Color ARCHIVE_RED = new Color(0xC8, 0x2A, 0x2A);

    /** 住院病历记录类型标签(1-15, 与 InpMedRecordService.RECORD_TYPE_LABELS 口径一致) */
    private static final Map<Integer, String> EMR_RECORD_TYPES = new LinkedHashMap<>();
    static {
        EMR_RECORD_TYPES.put(1, "入院记录");
        EMR_RECORD_TYPES.put(2, "首次病程记录");
        EMR_RECORD_TYPES.put(3, "日常病程记录");
        EMR_RECORD_TYPES.put(4, "查房记录");
        EMR_RECORD_TYPES.put(5, "术前小结");
        EMR_RECORD_TYPES.put(6, "手术记录");
        EMR_RECORD_TYPES.put(7, "术后病程记录");
        EMR_RECORD_TYPES.put(8, "出院小结");
        EMR_RECORD_TYPES.put(9, "死亡记录");
        EMR_RECORD_TYPES.put(10, "病案首页");
        EMR_RECORD_TYPES.put(11, "交接班记录");
        EMR_RECORD_TYPES.put(12, "转科记录");
        EMR_RECORD_TYPES.put(13, "知情同意书");
        EMR_RECORD_TYPES.put(14, "讨论记录");
        EMR_RECORD_TYPES.put(15, "会诊记录");
    }

    /** 中文字体(懒加载缓存) */
    private static volatile BaseFont cachedFont;

    /** 顶级元素扫描: div 或 table 开标签 */
    private static final Pattern TOP_TAG = Pattern.compile("<(div|table)\\b", Pattern.CASE_INSENSITIVE);

    /** div 配对扫描(含嵌套计数) */
    private static final Pattern DIV_TAG = Pattern.compile("<div\\b|</div\\b", Pattern.CASE_INSENSITIVE);

    /** 表格行 */
    private static final Pattern TR_PATTERN = Pattern.compile("(?i)<tr[^>]*>([\\s\\S]*?)</tr>");

    /** 表格单元格(th/td) */
    private static final Pattern CELL_PATTERN = Pattern.compile("(?i)<(t[dh])([^>]*)>([\\s\\S]*?)</\\1>");

    /** 腕带条码绘制: 黑白竖条近似 Code39 观感(PdfPCell 渲染事件) */
    private static final PdfPCellEvent BARCODE_PAINTER = new PdfPCellEvent() {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BASECANVAS];
            cb.saveState();
            cb.setColorFill(Color.BLACK);
            float x = position.getLeft() + 2f;
            float right = position.getRight() - 2f;
            float bottom = position.getBottom() + 2f;
            float height = position.getHeight() - 4f;
            if (height <= 0) {
                height = Utilities.millimetersToPoints(8);
            }
            while (x + 1.1f <= right) {
                cb.rectangle(x, bottom, 1.1f, height);
                cb.fill();
                x += 2.4f;
            }
            cb.restoreState();
        }
    };

    private final JdbcTemplate jdbcTemplate;
    private final EmrDocumentService emrDocumentService;
    private final EmrMacroService macroService;

    public PdfExportService(JdbcTemplate jdbcTemplate, EmrDocumentService emrDocumentService,
                            EmrMacroService macroService) {
        this.jdbcTemplate = jdbcTemplate;
        this.emrDocumentService = emrDocumentService;
        this.macroService = macroService;
    }

    /* ==================== 对外入口 ==================== */

    /** HTML内容转PDF(A4纵向)。 */
    public byte[] generatePdf(String htmlContent, String title) {
        return generatePdf(htmlContent, title, PageSize.A4, false);
    }

    /** HTML内容转PDF(指定纸张; compact=小票纸张: 零边距且不打印页码)。 */
    public byte[] generatePdf(String htmlContent, String title, Rectangle pageSize, boolean compact) {
        if (!StringUtils.hasText(htmlContent)) {
            throw new BizException(400, "PDF导出内容为空");
        }
        final String body = extractBody(htmlContent);
        final String docTitle = StringUtils.hasText(title) ? title.trim() : "住院单据";
        Rectangle paper = pageSize != null ? pageSize : PageSize.A4;
        return writeDoc(paper, compact, new DocFiller() {
            @Override
            public void fill(Document doc, PdfWriter writer) throws DocumentException {
                doc.addTitle(docTitle);
                renderElements(doc, body);
            }
        });
    }

    /**
     * 体温单PDF(A4横向): 查询护理体温记录(record_type=1)按区间过滤后构建,
     * 与前端 buildTempChartHtml 同数据口径(/api/his/inp/nursing/temperature/{visitId})。
     */
    public byte[] generateTempChartPdf(Long visitId, LocalDate startDate, LocalDate endDate) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        LocalDate start = startDate != null ? startDate : LocalDate.now().minusDays(6);
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        if (end.isBefore(start)) {
            throw new BizException(400, "结束日期不能早于开始日期");
        }
        // 患者信息头(与 InpPrintService.baseData 同口径)
        Map<String, Object> visit = requireVisit(visitId);
        String hospitalName = orgName(toLong(visit.get("org_id")));
        String patientName = "";
        String gender = "";
        String age = "";
        if (visit.get("patient_id") != null) {
            List<Map<String, Object>> ps = jdbcTemplate.queryForList(
                    "SELECT name, IFNULL(gender_name, gender) gender, age FROM his_patient"
                            + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    visit.get("patient_id"), tenantId());
            if (!ps.isEmpty()) {
                Map<String, Object> p = ps.get(0);
                patientName = str(p.get("name"));
                String g = str(p.get("gender"));
                gender = "1".equals(g) ? "男" : "2".equals(g) ? "女" : g;
                age = p.get("age") == null ? "" : String.valueOf(p.get("age"));
            }
        }
        final String fTitle = "体　温　单";
        final String fSub = hospitalName;
        final String fInfo = joinBits("姓名：" + patientName, gender,
                age.isEmpty() ? "" : age + "岁",
                "住院号：" + str(visit.get("inp_no")),
                "科室：" + deptName(toLong(visit.get("dept_id"))),
                "床号：" + bedNo(visit.get("bed_id")),
                "区间：" + start + " ~ " + end);
        final List<Map<String, Object>> vitals = loadVitals(visitId, start, end);
        final List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> v : vitals) {
            rows.add(new Object[]{v.get("time"), v.get("temperature"), v.get("pulse"),
                    v.get("respiration"), v.get("bloodPressure")});
        }
        return writeDoc(PageSize.A4.rotate(), false, new DocFiller() {
            @Override
            public void fill(Document doc, PdfWriter writer) throws DocumentException {
                doc.addTitle("体温单");
                addCentered(doc, fTitle, 16f, Font.BOLD, Color.BLACK, 4f);
                addCentered(doc, fSub, 10.5f, Font.NORMAL, TEXT_DARK, 4f);
                addCentered(doc, fInfo, 10.5f, Font.NORMAL, Color.BLACK, 8f);
                if (rows.isEmpty()) {
                    Paragraph empty = new Paragraph("所选区间内暂无体温单记录", font(11f, Font.NORMAL, TEXT_GRAY));
                    empty.setAlignment(Element.ALIGN_CENTER);
                    doc.add(empty);
                    return;
                }
                PdfPTable t = buildTable(headRow("记录时间", "体温(℃)", "脉搏(次/分)", "呼吸(次/分)", "血压(mmHg)"),
                        rows, null);
                doc.add(t);
            }
        });
    }

    /* ==================== 文档骨架 ==================== */

    /** 文档填充回调(填正文, 出参为 Document/PdfWriter)。 */
    private interface DocFiller {
        void fill(Document doc, PdfWriter writer) throws DocumentException;
    }

    /** 通用文档骨架: 建文档/写正文/页脚页码(compact 时不加)。 */
    private byte[] writeDoc(Rectangle pageSize, boolean compact, DocFiller filler) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(pageSize);
        PdfWriter writer = null;
        try {
            writer = PdfWriter.getInstance(doc, baos);
            if (!compact) {
                writer.setPageEvent(new PageNumberFooter());
            }
            if (compact) {
                doc.setMargins(2f, 2f, 2f, 2f);
            } else {
                doc.setMargins(DOC_MARGIN[0], DOC_MARGIN[1], DOC_MARGIN[2], DOC_MARGIN[3]);
            }
            doc.open();
            filler.fill(doc, writer);
            doc.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            log.warn("PDF生成失败(DocumentException): {}", e.getMessage());
            throw new BizException(500, "PDF生成失败: " + e.getMessage());
        } finally {
            if (doc != null && doc.isOpen()) {
                try {
                    doc.close();
                } catch (Exception ignore) {
                    // 双重关闭无副作用
                }
            }
        }
    }

    /** 页脚页码(第 N 页)。 */
    private class PageNumberFooter extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter w, Document d) {
            try {
                PdfContentByte cb = w.getDirectContent();
                Font f = font(8f, Font.NORMAL, TEXT_GRAY);
                String text = "第 " + w.getPageNumber() + " 页";
                ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Paragraph(text, f),
                        (d.left() + d.right()) / 2, d.bottom() - 14f, 0);
            } catch (Exception ignore) {
                // 页脚失败不影响正文
            }
        }
    }

    /* ==================== HTML 解析渲染 ==================== */

    /** 提取 body 内容并剔除 style/script/注释。 */
    private static String extractBody(String html) {
        String s = html;
        Matcher m = Pattern.compile("(?i)<body[^>]*>([\\s\\S]*)</body>").matcher(s);
        if (m.find()) {
            s = m.group(1);
        }
        s = s.replaceAll("(?i)<style[\\s\\S]*?</style>", "");
        s = s.replaceAll("(?i)<script[\\s\\S]*?</script>", "");
        s = s.replaceAll("<!--[\\s\\S]*?-->", "");
        return s;
    }

    /**
     * 按文档流顺序渲染顶级 div/table 元素。
     * 未知容器 div(如 .doc)递归扫描, 保证语义类名之外的模板内容不丢失。
     */
    private void renderElements(Document doc, String html) throws DocumentException {
        if (!StringUtils.hasText(html)) {
            return;
        }
        int pos = 0;
        while (true) {
            Matcher m = TOP_TAG.matcher(html);
            m.region(Math.min(pos, html.length()), html.length());
            if (!m.find()) {
                break;
            }
            int gt = html.indexOf('>', m.end());
            if (gt < 0) {
                break;
            }
            String openTag = html.substring(m.start(), gt + 1);
            if (openTag.toLowerCase().startsWith("<div")) {
                int bodyStart = gt + 1;
                int[] close = findDivEnd(html, bodyStart);
                if (close == null) {
                    break;
                }
                dispatchDiv(doc, attrClass(openTag), html.substring(bodyStart, close[0]));
                pos = close[1];
            } else {
                int end = indexOfIgnoreCase(html, "</table>", gt + 1);
                if (end < 0) {
                    break;
                }
                renderTable(doc, html.substring(gt + 1, end));
                pos = end + "</table>".length();
            }
        }
    }

    /** div 深度配对: 返回 [配对 </div> 起始位置, '>' 结束位置]。 */
    private static int[] findDivEnd(String html, int from) {
        Matcher m = DIV_TAG.matcher(html);
        m.region(from, html.length());
        int depth = 1;
        while (m.find()) {
            if (m.group().toLowerCase().startsWith("</")) {
                depth--;
                if (depth == 0) {
                    int gt = html.indexOf('>', m.end());
                    return gt < 0 ? null : new int[]{m.start(), gt + 1};
                }
            } else {
                depth++;
            }
        }
        return null;
    }

    /** 语义类名分发: 已知类名转段落, 未知容器递归扫描。 */
    private void dispatchDiv(Document doc, String cls, String inner) throws DocumentException {
        String content = inner == null ? "" : inner;
        if ("doc-title".equals(cls)) {
            addCentered(doc, textOf(content), 17f, Font.BOLD, Color.BLACK, 6f);
        } else if ("doc-sub".equals(cls)) {
            addCentered(doc, textOf(content), 10.5f, Font.NORMAL, TEXT_DARK, 8f);
        } else if ("info".equals(cls)) {
            addLines(doc, textOf(content), 10.5f, Font.NORMAL, Color.BLACK, 12f, 1.6f);
        } else if ("total".equals(cls)) {
            addRight(doc, textOf(content), 11.5f, Font.BOLD, Color.BLACK, 8f);
        } else if ("sign".equals(cls)) {
            addRight(doc, textOf(content), 10.5f, Font.NORMAL, Color.BLACK, 12f);
        } else if ("memo".equals(cls) || "grid-note".equals(cls)) {
            addLines(doc, textOf(content), 9f, Font.NORMAL, TEXT_GRAY, 8f, 1.5f);
        } else if ("emr-sec".equals(cls)) {
            /* 病历章节标题(一级, 来自 emrSection/heading): 左对齐加粗 */
            addLines(doc, textOf(content), 12.5f, Font.BOLD, Color.BLACK, 4f, 1.6f);
        } else if ("emr-sub".equals(cls)) {
            /* 病历小节标题(嵌套 emrSection/heading): 左对齐加粗小号 */
            addLines(doc, textOf(content), 11f, Font.BOLD, TEXT_DARK, 3f, 1.6f);
        } else if ("emr-p".equals(cls)) {
            /* 病历正文段落 */
            addLines(doc, textOf(content), 10.5f, Font.NORMAL, Color.BLACK, 5f, 1.75f);
        } else if ("emr-break".equals(cls)) {
            /* 强制分页(emrPageBreak 节点) */
            doc.newPage();
        } else if ("band".equals(cls)) {
            renderWristband(doc, content);
        } else {
            renderElements(doc, content);
        }
    }

    /** 腕带渲染: 黑底过敏警示 + 患者信息(姓名加粗) + 黑白竖条条码区。 */
    private void renderWristband(Document doc, String bandHtml) throws DocumentException {
        String warn = innerDiv(bandHtml, "warn");
        String col = innerDiv(bandHtml, "col");
        PdfPTable t = new PdfPTable(3);
        t.setWidthPercentage(100);
        try {
            t.setWidths(new float[]{0.17f, 0.59f, 0.24f});
        } catch (DocumentException ignore) {
            // 宽度比例异常时按默认等宽
        }
        // 过敏警示(黑底白字, 逐行)
        if (StringUtils.hasText(warn)) {
            PdfPCell wc = new PdfPCell();
            wc.setBackgroundColor(Color.BLACK);
            wc.setVerticalAlignment(Element.ALIGN_MIDDLE);
            wc.setPadding(4f);
            for (String ln : textOf(warn).split("\n")) {
                if (ln.isEmpty()) {
                    continue;
                }
                Paragraph p = new Paragraph(ln, font(8.5f, Font.BOLD, Color.WHITE));
                p.setAlignment(Element.ALIGN_CENTER);
                wc.addElement(p);
            }
            t.addCell(wc);
        } else {
            t.addCell(new PdfPCell(new Phrase(" ")));
        }
        // 患者信息: strong 为姓名, 其余为说明行
        PdfPCell cc = new PdfPCell();
        cc.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cc.setPadding(4f);
        if (StringUtils.hasText(col)) {
            Matcher sm = Pattern.compile("(?i)<strong[^>]*>([\\s\\S]*?)</strong>").matcher(col);
            String name = sm.find() ? textOf(sm.group(1)) : "";
            String rest = textOf(col.replaceAll("(?i)<strong[^>]*>[\\s\\S]*?</strong>", ""));
            if (StringUtils.hasText(name)) {
                Paragraph np = new Paragraph(name, font(13f, Font.BOLD, Color.BLACK));
                np.setAlignment(Element.ALIGN_CENTER);
                cc.addElement(np);
            }
            for (String ln : rest.split("\n")) {
                if (ln.isEmpty()) {
                    continue;
                }
                Paragraph p = new Paragraph(ln, font(9.5f, Font.NORMAL, Color.BLACK));
                p.setAlignment(Element.ALIGN_LEFT);
                p.setLeading(13f);
                cc.addElement(p);
            }
        }
        t.addCell(cc);
        // 条码区(黑白竖条)
        PdfPCell bc = new PdfPCell(new Phrase(" "));
        bc.setMinimumHeight(Utilities.millimetersToPoints(9));
        bc.setCellEvent(BARCODE_PAINTER);
        t.addCell(bc);
        doc.add(t);
    }

    /** 提取指定 class 的子 div 内容(腕带内部无更深嵌套, 非贪婪匹配)。 */
    private static String innerDiv(String html, String cls) {
        if (html == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?i)<div[^>]*class=\"([a-z-]+)\"[^>]*>([\\s\\S]*?)</div>").matcher(html);
        while (m.find()) {
            if (cls.equals(m.group(1))) {
                return m.group(2);
            }
        }
        return null;
    }

    /** 表格渲染: tr/th/td → PdfPTable(支持 colspan/rowspan, 表头灰底加粗, 列宽按 width% 近似)。 */
    private void renderTable(Document doc, String tableHtml) throws DocumentException {
        List<List<Object[]>> grid = new ArrayList<>();
        List<float[]> widths = new ArrayList<>();
        int maxCols = 0;
        Matcher rm = TR_PATTERN.matcher(tableHtml);
        while (rm.find()) {
            List<Object[]> row = new ArrayList<>();
            float[] rowWidths = new float[0];
            Matcher cm = CELL_PATTERN.matcher(rm.group(1));
            while (cm.find()) {
                boolean header = cm.group(1).equalsIgnoreCase("th");
                String attrs = cm.group(2);
                int colspan = intAttr(attrs, "colspan", 1);
                int rowspan = intAttr(attrs, "rowspan", 1);
                boolean left = attrs.toLowerCase().contains("\"lt\"")
                        || Pattern.compile("(?i)class=\"lt\"").matcher(attrs).find();
                row.add(new Object[]{textOf(cm.group(3)), header, colspan, rowspan, left});
                rowWidths = appendWidths(rowWidths, widthPct(attrs), colspan);
            }
            if (!row.isEmpty()) {
                grid.add(row);
                widths.add(rowWidths);
                int rowCols = 0;
                for (Object[] c : row) {
                    rowCols += (Integer) c[2];
                }
                maxCols = Math.max(maxCols, rowCols);
            }
        }
        if (grid.isEmpty() || maxCols == 0) {
            return;
        }
        PdfPTable t = new PdfPTable(maxCols);
        t.setWidthPercentage(100);
        float[] colPct = mergeColumnWidths(widths, maxCols);
        if (colPct != null) {
            try {
                t.setWidths(colPct);
            } catch (DocumentException ignore) {
                // 列宽异常回落等宽
            }
        }
        for (List<Object[]> row : grid) {
            for (Object[] c : row) {
                String text = (String) c[0];
                boolean header = (Boolean) c[1];
                int colspan = (Integer) c[2];
                int rowspan = (Integer) c[3];
                boolean left = (Boolean) c[4];
                PdfPCell cell = new PdfPCell(new Phrase(text, font(9.5f,
                        header ? Font.BOLD : Font.NORMAL, Color.BLACK)));
                if (colspan > 1) {
                    cell.setColspan(colspan);
                }
                if (rowspan > 1) {
                    cell.setRowspan(rowspan);
                }
                cell.setHorizontalAlignment(left ? Element.ALIGN_LEFT : Element.ALIGN_CENTER);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                cell.setPadding(3f);
                if (header) {
                    cell.setBackgroundColor(HEADER_BG);
                }
                t.addCell(cell);
            }
        }
        doc.add(t);
        doc.add(spacing(6f));
    }

    /** 按表头行+数据行构建表格(体温单直建数据用)。 */
    private PdfPTable buildTable(List<Object[]> headRow, List<Object[]> dataRows, float[] colPct)
            throws DocumentException {
        int cols = headRow.size();
        PdfPTable t = new PdfPTable(cols);
        t.setWidthPercentage(100);
        if (colPct != null && colPct.length == cols) {
            try {
                t.setWidths(colPct);
            } catch (DocumentException ignore) {
                // 回落等宽
            }
        }
        for (Object[] h : headRow) {
            PdfPCell cell = new PdfPCell(new Phrase(str(h[0]), font(9.5f, Font.BOLD, Color.BLACK)));
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setBackgroundColor(HEADER_BG);
            cell.setPadding(3f);
            t.addCell(cell);
        }
        for (Object[] r : dataRows) {
            for (Object v : r) {
                PdfPCell cell = new PdfPCell(new Phrase(str(v), font(9.5f, Font.NORMAL, Color.BLACK)));
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setPadding(3f);
                t.addCell(cell);
            }
        }
        return t;
    }

    private static List<Object[]> headRow(Object... cols) {
        List<Object[]> head = new ArrayList<>();
        for (Object c : cols) {
            head.add(new Object[]{c});
        }
        return head;
    }

    /* ==================== 文本/属性工具 ==================== */

    /** HTML 片段转纯文本: br→换行, 行内标签结束→全角空格分隔, 去标签+反转义+压缩空白。 */
    private static String textOf(String html) {
        if (html == null) {
            return "";
        }
        String s = html;
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(span|strong|b|em|i|font)>", "　");
        s = s.replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = unescape(s).replace('\u00A0', ' ');
        StringBuilder sb = new StringBuilder();
        for (String ln : s.split("\n")) {
            String t = ln.replaceAll("[ \\t]+", " ").trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /** 常用 HTML 实体反转义(&amp; 最后处理避免双重解码)。 */
    private static String unescape(String s) {
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    /** 提取开标签 class 属性值。 */
    private static String attrClass(String openTag) {
        Matcher m = Pattern.compile("(?i)class\\s*=\\s*\"([^\"]*)\"").matcher(openTag);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 提取数值属性(缺省返回 def)。 */
    private static int intAttr(String attrs, String name, int def) {
        Matcher m = Pattern.compile("(?i)" + name + "\\s*=\\s*\"?(\\d+)\"?").matcher(attrs);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    /** 提取单元格宽百分比(style="width:14%" 或 width="14%"; 无则 -1)。 */
    private static float widthPct(String attrs) {
        Matcher m = Pattern.compile("(?i)width\\s*:\\s*([\\d.]+)\\s*%").matcher(attrs);
        if (m.find()) {
            return Float.parseFloat(m.group(1));
        }
        m = Pattern.compile("(?i)\\bwidth\\s*=\\s*\"?([\\d.]+)%").matcher(attrs);
        return m.find() ? Float.parseFloat(m.group(1)) : -1f;
    }

    /** 行宽数组按列序追加(未知列 -1 占位)。 */
    private static float[] appendWidths(float[] arr, float pct, int colspan) {
        float[] out = new float[arr.length + colspan];
        System.arraycopy(arr, 0, out, 0, arr.length);
        for (int i = arr.length; i < out.length; i++) {
            out[i] = pct;
        }
        return out;
    }

    /** 各行宽度信息合并为列宽数组: 未指定的列用剩余宽度均分; 全无信息返回 null。 */
    private static float[] mergeColumnWidths(List<float[]> widths, int maxCols) {
        float[] col = new float[maxCols];
        boolean any = false;
        for (float[] row : widths) {
            for (int i = 0; i < row.length && i < maxCols; i++) {
                if (row[i] > 0 && col[i] <= 0) {
                    col[i] = row[i];
                    any = true;
                }
            }
        }
        if (!any) {
            return null;
        }
        float known = 0f;
        int unknown = 0;
        for (float v : col) {
            if (v > 0) {
                known += v;
            } else {
                unknown++;
            }
        }
        float avg = unknown > 0 ? Math.max((100f - known) / unknown, 4f) : 0f;
        float sum = 0f;
        for (int i = 0; i < maxCols; i++) {
            if (col[i] <= 0) {
                col[i] = avg;
            }
            sum += col[i];
        }
        for (int i = 0; i < maxCols; i++) {
            col[i] = col[i] * 100f / sum;
        }
        return col;
    }

    private static int indexOfIgnoreCase(String s, String target, int from) {
        int n = s.length();
        int m = target.length();
        for (int i = from; i + m <= n; i++) {
            boolean ok = true;
            for (int j = 0; j < m; j++) {
                if (Character.toUpperCase(s.charAt(i + j)) != Character.toUpperCase(target.charAt(j))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return i;
            }
        }
        return -1;
    }

    /* ==================== 排版段落工具 ==================== */

    private Font font(float size, int style, Color color) {
        return new Font(cjkFont(), size, style, color);
    }

    private Paragraph spacing(float pt) {
        return new Paragraph(" ", font(Math.max(pt, 1f), Font.NORMAL, Color.WHITE));
    }

    private void addCentered(Document doc, String text, float size, int style, Color color, float after)
            throws DocumentException {
        for (String ln : text.split("\n")) {
            if (ln.isEmpty()) {
                continue;
            }
            Paragraph p = new Paragraph(ln, font(size, style, color));
            p.setAlignment(Element.ALIGN_CENTER);
            doc.add(p);
        }
        doc.add(spacing(after));
    }

    private void addRight(Document doc, String text, float size, int style, Color color, float after)
            throws DocumentException {
        for (String ln : text.split("\n")) {
            if (ln.isEmpty()) {
                continue;
            }
            Paragraph p = new Paragraph(ln, font(size, style, color));
            p.setAlignment(Element.ALIGN_RIGHT);
            doc.add(p);
        }
        doc.add(spacing(after));
    }

    private void addLines(Document doc, String text, float size, int style, Color color, float after, float leading)
            throws DocumentException {
        for (String ln : text.split("\n")) {
            if (ln.isEmpty()) {
                continue;
            }
            Paragraph p = new Paragraph(ln, font(size, style, color));
            p.setLeading(size * leading);
            doc.add(p);
        }
        doc.add(spacing(after));
    }

    /* ==================== 中文字体(降级链) ==================== */

    /** 中文字体: STSong-Light → Windows 宋体/黑体/雅黑/仿宋 → Linux CJK → Helvetica 兜底。 */
    private static BaseFont cjkFont() {
        BaseFont bf = cachedFont;
        if (bf != null) {
            return bf;
        }
        synchronized (PdfExportService.class) {
            if (cachedFont != null) {
                return cachedFont;
            }
            String[][] candidates = new String[][]{
                    {"STSong-Light", "UniGB-UCS2-H"},
                    {"C:/Windows/Fonts/simsun.ttc,0", BaseFont.IDENTITY_H},
                    {"C:/Windows/Fonts/simhei.ttf", BaseFont.IDENTITY_H},
                    {"C:/Windows/Fonts/msyh.ttc,0", BaseFont.IDENTITY_H},
                    {"C:/Windows/Fonts/simfang.ttf", BaseFont.IDENTITY_H},
                    {"C:/Windows/Fonts/simkai.ttf", BaseFont.IDENTITY_H},
                    {"/usr/share/fonts/truetype/arphic/uming.ttc,0", BaseFont.IDENTITY_H},
                    {"/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0", BaseFont.IDENTITY_H},
                    {"/System/Library/Fonts/PingFang.ttc,0", BaseFont.IDENTITY_H}
            };
            for (String[] c : candidates) {
                try {
                    BaseFont f = BaseFont.createFont(c[0], c[1],
                            c[0].indexOf('/') >= 0 || c[0].indexOf(':') >= 0);
                    cachedFont = f;
                    log.info("PDF中文字体加载成功: {}", c[0]);
                    return f;
                } catch (Exception ignore) {
                    // 尝试下一个候选
                }
            }
            log.warn("PDF中文字体全部候选加载失败, 回落 Helvetica(中文将不可见)");
            try {
                cachedFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false);
            } catch (Exception e) {
                throw new BizException(500, "PDF字体初始化失败");
            }
            return cachedFont;
        }
    }

    /* ==================== 体温单数据 ==================== */

    /** 体温单生命体征记录: content JSON 解析 + 区间过滤(与前端口径一致)。 */
    private List<Map<String, Object>> loadVitals(Long visitId, LocalDate start, LocalDate end) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT record_time, content FROM his_inp_nursing_record"
                        + " WHERE inp_visit_id = ? AND record_type = 1 AND deleted = 0 AND tenant_id = ?"
                        + " AND record_time BETWEEN ? AND ? ORDER BY record_time",
                visitId, tenantId(), start.atStartOfDay(), end.atTime(LocalTime.MAX));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!StringUtils.hasText(str(r.get("content")))) {
                continue;
            }
            JSONObject json;
            try {
                json = JSON.parseObject(str(r.get("content")));
            } catch (Exception e) {
                continue;
            }
            if (json == null) {
                continue;
            }
            String time = str(json.get("time"));
            if (!StringUtils.hasText(time) && r.get("record_time") != null) {
                time = ((LocalDateTime) r.get("record_time")).format(DT_SHORT);
            }
            String bp;
            Object sys = json.get("systolicBp");
            Object dia = json.get("diastolicBp");
            if (sys != null || dia != null) {
                bp = str(sys) + "/" + str(dia);
            } else {
                bp = str(json.get("blood_pressure"));
            }
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("time", time);
            v.put("temperature", str(json.get("temperature")));
            v.put("pulse", str(json.get("pulse")));
            v.put("respiration", str(json.get("respiration")));
            v.put("bloodPressure", bp);
            out.add(v);
        }
        return out;
    }

    /* ==================== 数据查询工具 ==================== */

    /** 就诊必读(含机构/患者/科室/床位定位字段)。 */
    private Map<String, Object> requireVisit(Long visitId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, org_id, patient_id, inp_no, dept_id, bed_id, doctor_id"
                        + " FROM his_inp_visit WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                visitId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        return rows.get(0);
    }

    /** 机构名称(查不到返回空串)。 */
    private String orgName(Long orgId) {
        if (orgId == null) {
            return "";
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT org_name FROM sys_org WHERE id = ? AND deleted = 0", String.class, orgId);
        return names.isEmpty() ? "" : names.get(0);
    }

    /** 科室名称(查不到返回空串)。 */
    private String deptName(Long deptId) {
        if (deptId == null) {
            return "";
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT dept_name FROM his_dept WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                String.class, deptId, tenantId());
        return names.isEmpty() ? "" : names.get(0);
    }

    /** 床位号(查不到返回空串)。 */
    private String bedNo(Object bedId) {
        if (bedId == null) {
            return "";
        }
        List<String> nos = jdbcTemplate.queryForList(
                "SELECT bed_no FROM his_bed WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                String.class, bedId, tenantId());
        return nos.isEmpty() ? "" : nos.get(0);
    }

    /** 非空片段以全角空格连接。 */
    private static String joinBits(String... bits) {
        StringBuilder sb = new StringBuilder();
        for (String b : bits) {
            if (!StringUtils.hasText(b)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("　");
            }
            sb.append(b);
        }
        return sb.toString();
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /* ==================== P7a-3 病历PDF(生成/归档/批量/选页) ==================== */

    /**
     * 病历PDF生成(Tiptap文档主链): his_inp_medical_record.content(AES-GCM 密文/历史明文)
     * 解密 → 片段展开(EmrDocumentService.resolveFragmentsInDocument) → Tiptap JSON 转语义HTML
     * (章节/段落/表格/条件块/分页符) → OpenPDF 渲染。
     * 兼容回退: content 为富文书 HTML('<' 开头)走既有 HTML 语义管线; 密文不可读时回退 structureData(明文 JSON);
     * 均不可用时按纯文本逐行输出。版式: A4 + 页眉(机构/科室) + 页脚(页码/打印时间) + 签名区。
     */
    public byte[] generateFromTiptap(Long recordId) {
        if (recordId == null) {
            throw new BizException(400, "病历ID不能为空");
        }
        Map<String, Object> rec = requireRecord(recordId);
        Map<String, Object> visit = requireVisit(toLong(rec.get("inp_visit_id")));
        String body = tiptapContentToHtml(
                str(emrDocumentService.loadDocument(1, recordId, str(rec.get("content")))),
                str(rec.get("structure_data")), toLong(rec.get("inp_visit_id")));
        if (!StringUtils.hasText(body)) {
            throw new BizException(400, "病历内容为空, 无法生成PDF");
        }
        final String title = StringUtils.hasText(str(rec.get("title"))) ? str(rec.get("title"))
                : emrTypeName(toInteger(rec.get("record_type")));
        final String hospital = orgName(toLong(visit.get("org_id")));
        final String dept = deptName(toLong(visit.get("dept_id")));
        final String printNote = "打印时间：" + LocalDateTime.now().format(DT_SHORT);
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"doc-title\">").append(escapeHtml(title)).append("</div>");
        if (StringUtils.hasText(hospital)) {
            html.append("<div class=\"doc-sub\">").append(escapeHtml(hospital)).append("</div>");
        }
        html.append("<div class=\"info\">").append(escapeHtml(patientBrief(visit))).append("</div>");
        html.append("<div class=\"memo\">").append(escapeHtml(recordMetaText(rec))).append("</div>");
        html.append(body);
        html.append("<div class=\"sign\">").append(escapeHtml(signatureText(rec))).append("</div>");
        final String htmlFinal = html.toString();
        return writeDoc(PageSize.A4, new EmrHeaderFooter(hospital, dept, printNote), EMR_MARGIN, new DocFiller() {
            @Override
            public void fill(Document doc, PdfWriter writer) throws DocumentException {
                doc.addTitle(title);
                renderElements(doc, htmlFinal);
            }
        });
    }

    /**
     * 归档PDF(签名水印 + 归档章): 基于 {@link #generateFromTiptap} 生成正文后,
     * 经 PdfStamper 逐页叠加: 半透明 45° 签名水印(书写/审核/主治/主任医师+时间, 未签以"未签名"占位)
     * 与右下角红色双框"已归档"章(归档日期+归档人; 未归档时取当天)。
     */
    public byte[] generateArchivePdf(Long recordId) {
        if (recordId == null) {
            throw new BizException(400, "病历ID不能为空");
        }
        byte[] base = generateFromTiptap(recordId);
        Map<String, Object> rec = requireRecord(recordId);
        final List<String> marks = archiveMarkLines(rec);
        marks.addAll(caSignLines(recordId));
        final List<String> signImages = loadSignImages(recordId);
        String stampDate = fmtTime(rec.get("archive_time"));
        if (!StringUtils.hasText(stampDate)) {
            stampDate = LocalDate.now().toString();
        }
        String stampBy = str(rec.get("archive_by")).trim();
        final String stampLine = stampBy.isEmpty() ? stampDate : stampDate + " · " + stampBy;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfReader reader = null;
        try {
            reader = new PdfReader(base);
            PdfStamper stamper = new PdfStamper(reader, baos);
            int pages = reader.getNumberOfPages();
            for (int i = 1; i <= pages; i++) {
                Rectangle size = reader.getPageSize(i);
                PdfContentByte over = stamper.getOverContent(i);
                drawSignatureWatermark(over, size, marks);
                drawSignatureImages(over, size, signImages);
                drawArchiveStamp(over, size, stampLine);
                if (pages > 1) {
                    drawSealMark(over, size, i, pages);
                }
            }
            stamper.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("归档PDF叠章失败: {}", e.getMessage());
            throw new BizException(500, "归档PDF生成失败: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignore) {
                    // 已由 stamper.close 关闭, 双重关闭无副作用
                }
            }
        }
    }

    /**
     * 批量生成合并PDF: 逐条 {@link #generateFromTiptap}(单份失败跳过并记日志),
     * 经 PdfCopy 顺序合并为单文档; 单次上限60份防止内存峰值。
     * 全部失败时抛业务异常; 仅1份成功时原样返回。
     */
    public byte[] batchGenerate(List<Long> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            throw new BizException(400, "批量打印病历ID列表不能为空");
        }
        if (recordIds.size() > 60) {
            throw new BizException(400, "单次批量最多60份病历");
        }
        List<byte[]> docs = new ArrayList<>();
        int failed = 0;
        for (Long id : recordIds) {
            if (id == null) {
                continue;
            }
            try {
                docs.add(generateFromTiptap(id));
            } catch (Exception e) {
                failed++;
                log.warn("批量PDF跳过病历{}: {}", id, e.getMessage());
            }
        }
        if (docs.isEmpty()) {
            throw new BizException(500, "批量生成失败: " + failed + " 份病历均无法生成PDF");
        }
        if (docs.size() == 1) {
            return docs.get(0);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document merged = new Document();
        PdfReader reader = null;
        try {
            PdfCopy copy = new PdfCopy(merged, baos);
            merged.open();
            for (byte[] doc : docs) {
                reader = new PdfReader(doc);
                int pages = reader.getNumberOfPages();
                for (int i = 1; i <= pages; i++) {
                    copy.addPage(copy.getImportedPage(reader, i));
                }
                copy.freeReader(reader);
                reader.close();
                reader = null;
            }
            merged.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("批量PDF合并失败: {}", e.getMessage());
            throw new BizException(500, "批量PDF合并失败: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignore) {
                    // 双重关闭无副作用
                }
            }
            if (merged.isOpen()) {
                try {
                    merged.close();
                } catch (Exception ignore) {
                    // 双重关闭无副作用
                }
            }
        }
    }

    /**
     * 选页导出: 生成完整PDF后经 PdfCopy 仅复制 [startPage, endPage] 页区间(超出总页数自动收敛)。
     * startPage<1 / endPage<startPage / startPage 超出总页数时抛 400。
     */
    public byte[] selectivePages(Long recordId, int startPage, int endPage) {
        if (recordId == null) {
            throw new BizException(400, "病历ID不能为空");
        }
        if (startPage < 1 || endPage < startPage) {
            throw new BizException(400, "选页范围无效: 起始页须≥1且不大于结束页");
        }
        byte[] full = generateFromTiptap(recordId);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfReader reader = null;
        try {
            reader = new PdfReader(full);
            int total = reader.getNumberOfPages();
            if (startPage > total) {
                throw new BizException(400, "起始页超出总页数(" + total + "页)");
            }
            int end = Math.min(endPage, total);
            Document out = new Document(reader.getPageSizeWithRotation(startPage));
            PdfCopy copy = new PdfCopy(out, baos);
            out.open();
            for (int i = startPage; i <= end; i++) {
                copy.addPage(copy.getImportedPage(reader, i));
            }
            out.close();
            return baos.toByteArray();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("选页PDF生成失败: {}", e.getMessage());
            throw new BizException(500, "选页PDF生成失败: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignore) {
                    // 双重关闭无副作用
                }
            }
        }
    }

    /* ==================== P7a-3 病历PDF内部支撑(数据/版式/绘制) ==================== */

    /** 病历记录必读(含签名链与归档字段)。 */
    private Map<String, Object> requireRecord(Long recordId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, org_id, inp_visit_id, record_type, title, content, structure_data, status,"
                        + " record_time, doctor_id, audit_doctor_id, audit_time,"
                        + " attending_sign_id, attending_sign_time, director_sign_id, director_sign_time,"
                        + " archive_time, archive_by"
                        + " FROM his_inp_medical_record WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                recordId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(404, "病历记录不存在");
        }
        return rows.get(0);
    }

    /** 患者信息行: 姓名/性别/年龄/住院号/科室/床号(与体温单头同口径)。 */
    private String patientBrief(Map<String, Object> visit) {
        String name = "";
        String gender = "";
        String age = "";
        if (visit.get("patient_id") != null) {
            List<Map<String, Object>> ps = jdbcTemplate.queryForList(
                    "SELECT name, IFNULL(gender_name, gender) gender, age FROM his_patient"
                            + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    visit.get("patient_id"), tenantId());
            if (!ps.isEmpty()) {
                Map<String, Object> p = ps.get(0);
                name = str(p.get("name"));
                String g = str(p.get("gender"));
                gender = "1".equals(g) ? "男" : "2".equals(g) ? "女" : g;
                age = p.get("age") == null ? "" : String.valueOf(p.get("age"));
            }
        }
        return joinBits("姓名：" + name, gender,
                age.isEmpty() ? "" : age + "岁",
                "住院号：" + str(visit.get("inp_no")),
                "科室：" + deptName(toLong(visit.get("dept_id"))),
                "床号：" + bedNo(visit.get("bed_id")));
    }

    /** 病历元信息行: 记录类型/记录时间/状态。 */
    private String recordMetaText(Map<String, Object> rec) {
        return joinBits("记录类型：" + emrTypeName(toInteger(rec.get("record_type"))),
                "记录时间：" + fmtTime(rec.get("record_time")),
                "状态：" + statusName(toInteger(rec.get("status"))));
    }

    /** 签名行(已签名医师才列出; 全未签则留签名线)。 */
    private String signatureText(Map<String, Object> rec) {
        StringBuilder sb = new StringBuilder();
        appendSign(sb, "记录医师", rec.get("doctor_id"), rec.get("record_time"));
        appendSign(sb, "审核医师", rec.get("audit_doctor_id"), rec.get("audit_time"));
        appendSign(sb, "主治医师", rec.get("attending_sign_id"), rec.get("attending_sign_time"));
        appendSign(sb, "主任医师", rec.get("director_sign_id"), rec.get("director_sign_time"));
        return sb.length() == 0 ? "医师签名：＿＿＿＿＿＿" : sb.toString();
    }

    private void appendSign(StringBuilder sb, String label, Object staffIdObj, Object timeObj) {
        String name = staffName(toLong(staffIdObj));
        if (!StringUtils.hasText(name)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("　　");
        }
        sb.append(label).append("：").append(name);
        String time = fmtTime(timeObj);
        if (StringUtils.hasText(time)) {
            sb.append("（").append(time).append("）");
        }
    }

    /** 归档水印行: 书写/审核/主治/主任医师 + 时间(未签以"未签名"占位提示补签)。 */
    private List<String> archiveMarkLines(Map<String, Object> rec) {
        List<String> lines = new ArrayList<>();
        lines.add(markLine("记录医师", rec.get("doctor_id"), rec.get("record_time")));
        lines.add(markLine("审核医师", rec.get("audit_doctor_id"), rec.get("audit_time")));
        lines.add(markLine("主治医师", rec.get("attending_sign_id"), rec.get("attending_sign_time")));
        lines.add(markLine("主任医师", rec.get("director_sign_id"), rec.get("director_sign_time")));
        return lines;
    }

    private String markLine(String label, Object staffIdObj, Object timeObj) {
        String name = staffName(toLong(staffIdObj));
        if (!StringUtils.hasText(name)) {
            return label + "：未签名";
        }
        String time = fmtTime(timeObj);
        return label + "：" + name + (StringUtils.hasText(time) ? " " + time : "");
    }

    /** 职工姓名(查不到返回空串)。 */
    private String staffName(Long staffId) {
        if (staffId == null) {
            return "";
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT staff_name FROM his_staff WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                String.class, staffId, tenantId());
        return names.isEmpty() ? "" : names.get(0);
    }

    /** 病历类型名(未知回落"病历")。 */
    private static String emrTypeName(Integer type) {
        if (type == null) {
            return "病历";
        }
        String name = EMR_RECORD_TYPES.get(type);
        return name == null ? "病历" : name;
    }

    /** 病历状态名(1草稿/2已提交/3已审核/4已归档/5召回中/6已封存)。 */
    private static String statusName(Integer status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case 1:
                return "草稿";
            case 2:
                return "已提交";
            case 3:
                return "已审核";
            case 4:
                return "已归档";
            case 5:
                return "召回中";
            case 6:
                return "已封存";
            default:
                return "状态" + status;
        }
    }

    /** 时间格式化(分钟级): LocalDateTime/Date → "yyyy-MM-dd HH:mm"; ISO 串截断; 其余原样。 */
    private static String fmtTime(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof LocalDateTime) {
            return ((LocalDateTime) v).format(DT_SHORT);
        }
        if (v instanceof java.util.Date) {
            return new java.sql.Timestamp(((java.util.Date) v).getTime()).toLocalDateTime().format(DT_SHORT);
        }
        String s = String.valueOf(v).trim();
        if (s.length() >= 16 && (s.charAt(10) == 'T' || s.charAt(10) == ' ')) {
            return s.substring(0, 16).replace('T', ' ');
        }
        return s;
    }

    /** 整型转换(类型不符返回 null)。 */
    private static Integer toInteger(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** HTML 实体转义(与 unescape 互逆)。 */
    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 是否 JSON 文档串(对象/数组开头)。 */
    private static boolean isJsonDoc(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    /** 是否 Base64 密文特征(长且仅含 Base64 字符、无空白; 解密失败透传原文时用于回退)。 */
    private static boolean looksLikeCipher(String s) {
        if (s == null || s.length() < 32) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '+' || ch == '/' || ch == '=';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 依序取首个非空字符串属性。 */
    private static String firstNonEmptyStr(JSONObject obj, String... keys) {
        if (obj == null) {
            return "";
        }
        for (String k : keys) {
            Object v = obj.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return "";
    }

    /** 病历文档骨架: 自定义页眉页脚事件 + 边距。 */
    private byte[] writeDoc(Rectangle pageSize, PdfPageEventHelper event, float[] margins, DocFiller filler) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(pageSize);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, baos);
            if (event != null) {
                writer.setPageEvent(event);
            }
            doc.setMargins(margins[0], margins[1], margins[2], margins[3]);
            doc.open();
            filler.fill(doc, writer);
            doc.close();
            return baos.toByteArray();
        } catch (DocumentException e) {
            log.warn("病历PDF生成失败(DocumentException): {}", e.getMessage());
            throw new BizException(500, "病历PDF生成失败: " + e.getMessage());
        } finally {
            if (doc.isOpen()) {
                try {
                    doc.close();
                } catch (Exception ignore) {
                    // 双重关闭无副作用
                }
            }
        }
    }

    /** 病历版式页眉页脚: 页眉=机构名左 / 科室名右 + 细分隔线; 页脚=页码居中 / 打印时间右。 */
    private class EmrHeaderFooter extends PdfPageEventHelper {
        private final String headLeft;
        private final String headRight;
        private final String footNote;

        EmrHeaderFooter(String headLeft, String headRight, String footNote) {
            this.headLeft = headLeft == null ? "" : headLeft;
            this.headRight = headRight == null ? "" : headRight;
            this.footNote = footNote == null ? "" : footNote;
        }

        @Override
        public void onEndPage(PdfWriter w, Document d) {
            try {
                PdfContentByte cb = w.getDirectContent();
                Font f = font(8.5f, Font.NORMAL, TEXT_GRAY);
                if (StringUtils.hasText(headLeft)) {
                    ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(headLeft, f),
                            d.left(), d.top() + 26f, 0);
                }
                if (StringUtils.hasText(headRight)) {
                    ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase(headRight, f),
                            d.right(), d.top() + 26f, 0);
                }
                /* 页眉分隔线 */
                cb.saveState();
                cb.setColorStroke(new Color(0x99, 0x99, 0x99));
                cb.setLineWidth(0.6f);
                cb.moveTo(d.left(), d.top() + 18f);
                cb.lineTo(d.right(), d.top() + 18f);
                cb.stroke();
                cb.restoreState();
                /* 页脚: 页码居中 / 打印时间右 */
                ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                        new Phrase("第 " + w.getPageNumber() + " 页", f),
                        (d.left() + d.right()) / 2, d.bottom() - 24f, 0);
                if (StringUtils.hasText(footNote)) {
                    ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase(footNote, f),
                            d.right(), d.bottom() - 24f, 0);
                }
            } catch (Exception ignore) {
                // 页眉页脚失败不影响正文
            }
        }
    }

    /** 45° 半透明签名水印: 各行绕页心居中排列(垂直偏移保持行距)。 */
    private void drawSignatureWatermark(PdfContentByte over, Rectangle size, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        try {
            over.saveState();
            PdfGState gs = new PdfGState();
            gs.setFillOpacity(0.12f);
            over.setGState(gs);
            float cx = (size.getLeft() + size.getRight()) / 2;
            float cy = (size.getBottom() + size.getTop()) / 2;
            float lineH = 30f;
            float startY = cy + (lines.size() - 1) * lineH / 2f;
            Font f = font(14f, Font.NORMAL, TEXT_GRAY);
            for (int i = 0; i < lines.size(); i++) {
                ColumnText.showTextAligned(over, Element.ALIGN_CENTER,
                        new Phrase(lines.get(i), f), cx, startY - i * lineH, 45);
            }
            over.restoreState();
        } catch (Exception e) {
            log.warn("签名水印绘制失败(跳过): {}", e.getMessage());
        }
    }

    /** 右下角红色双框"已归档"章(归档日期 · 归档人)。 */
    private void drawArchiveStamp(PdfContentByte over, Rectangle size, String stampLine) {
        try {
            over.saveState();
            over.setColorStroke(ARCHIVE_RED);
            over.setColorFill(ARCHIVE_RED);
            float w = 130f;
            float h = 48f;
            float x = size.getRight() - 60f - w;
            float y = size.getBottom() + 62f;
            /* 外框 */
            over.setLineWidth(1.8f);
            over.rectangle(x, y, w, h);
            over.stroke();
            /* 内框 */
            over.setLineWidth(0.7f);
            over.rectangle(x + 3f, y + 3f, w - 6f, h - 6f);
            over.stroke();
            ColumnText.showTextAligned(over, Element.ALIGN_CENTER,
                    new Phrase("已归档", font(15f, Font.BOLD, ARCHIVE_RED)),
                    x + w / 2, y + h - 21f, 0);
            if (StringUtils.hasText(stampLine)) {
                ColumnText.showTextAligned(over, Element.ALIGN_CENTER,
                        new Phrase(stampLine, font(8f, Font.NORMAL, ARCHIVE_RED)),
                        x + w / 2, y + 9f, 0);
            }
            over.restoreState();
        } catch (Exception e) {
            log.warn("归档章绘制失败(跳过): {}", e.getMessage());
        }
    }

    /* ==================== P7a-3 签章可视化叠加(签名图/CA/骑缝章) ==================== */

    /**
     * 加载病历有效签名图(base64): his_emr_signature(scope=1, valid=1) 的
     * sign_image(手写)/patient_sign_image(患者)/family_sign_image(家属), 非空按签署时序收集。
     * 迁移未执行或查询失败返回空列表(best-effort)。
     */
    private List<String> loadSignImages(Long recordId) {
        List<String> out = new ArrayList<>();
        if (recordId == null) {
            return out;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT sign_image AS a, patient_sign_image AS b, family_sign_image AS c"
                            + " FROM his_emr_signature WHERE scope = 1 AND record_id = ? AND valid = 1"
                            + " AND tenant_id = ? AND deleted = 0 ORDER BY sign_time ASC, id ASC",
                    recordId, tenantId());
            for (Map<String, Object> r : rows) {
                addBase64(out, r.get("a"));
                addBase64(out, r.get("b"));
                addBase64(out, r.get("c"));
            }
        } catch (Exception e) {
            log.warn("签名图加载失败(跳过叠加): recordId={}, {}", recordId, e.getMessage());
        }
        return out;
    }

    private static void addBase64(List<String> out, Object v) {
        if (v == null) {
            return;
        }
        String s = String.valueOf(v).trim();
        if (s.length() > 32) {
            out.add(s);
        }
    }

    /** CA 数字签名水印行: sign_mode=3 或 provider=ca 的签名追加 证书SN + 时间戳(纯可视化, 不做密码学验签)。 */
    private List<String> caSignLines(Long recordId) {
        List<String> lines = new ArrayList<>();
        if (recordId == null) {
            return lines;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT signer_name AS nm, ca_cert_sn AS sn, ca_timestamp AS ts"
                            + " FROM his_emr_signature WHERE record_id = ? AND tenant_id = ? AND deleted = 0"
                            + " AND (sign_mode = 3 OR provider = 'ca') AND valid = 1 ORDER BY sign_time ASC, id ASC",
                    recordId, tenantId());
            for (Map<String, Object> r : rows) {
                String nm = str(r.get("nm"));
                String sn = str(r.get("sn"));
                String ts = fmtTime(r.get("ts"));
                if (!StringUtils.hasText(sn) && !StringUtils.hasText(ts)) {
                    continue;
                }
                StringBuilder sb = new StringBuilder("CA签名");
                if (StringUtils.hasText(nm)) {
                    sb.append("·").append(nm);
                }
                if (StringUtils.hasText(sn)) {
                    sb.append(" 证书:").append(sn);
                }
                if (StringUtils.hasText(ts)) {
                    sb.append(" ").append(ts);
                }
                lines.add(sb.toString());
            }
        } catch (Exception e) {
            log.warn("CA签名行加载失败(跳过): recordId={}, {}", recordId, e.getMessage());
        }
        return lines;
    }

    /** 底部落款区横向盖手写/患者/家属签名图(最高46pt, 避开右下角归档章); 解码失败或超宽的图跳过。 */
    private void drawSignatureImages(PdfContentByte over, Rectangle size, List<String> base64List) {
        if (base64List == null || base64List.isEmpty()) {
            return;
        }
        float maxH = 46f;
        float maxW = 96f;
        float x = size.getLeft() + 48f;
        float baseY = size.getBottom() + 40f;
        for (String b64 : base64List) {
            byte[] bytes = decodeBase64Image(b64);
            if (bytes == null) {
                continue;
            }
            try {
                Image img = Image.getInstance(bytes);
                float w = img.getWidth();
                float h = img.getHeight();
                if (w <= 0 || h <= 0) {
                    continue;
                }
                float scale = Math.min(maxW / w, maxH / h);
                if (scale <= 0f || Float.isNaN(scale)) {
                    scale = 0.5f;
                }
                if (scale > 1f) {
                    scale = 1f;
                }
                float dw = w * scale;
                float dh = h * scale;
                if (x + dw > size.getRight() - 20f) {
                    break;
                }
                img.scaleAbsolute(dw, dh);
                img.setAbsolutePosition(x, baseY);
                over.addImage(img);
                x += dw + 10f;
            } catch (Exception e) {
                log.warn("签名图叠加失败(跳过一张): {}", e.getMessage());
            }
        }
    }

    /** data URL 前缀剥离 + Base64 解码; 非法返回 null。 */
    private static byte[] decodeBase64Image(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        try {
            return Base64.getMimeDecoder().decode(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 骑缝章: 页面右缘中线盖半圆红章(逐页拼合形成整章), 仅多页时绘制。 */
    private void drawSealMark(PdfContentByte over, Rectangle size, int page, int total) {
        try {
            over.saveState();
            over.setColorStroke(ARCHIVE_RED);
            over.setColorFill(ARCHIVE_RED);
            float r = 26f;
            float cx = size.getRight() - 2f;
            float cy = (size.getTop() + size.getBottom()) / 2;
            over.setLineWidth(1.4f);
            over.circle(cx, cy, r);
            over.stroke();
            ColumnText.showTextAligned(over, Element.ALIGN_CENTER,
                    new Phrase("骑缝", font(7f, Font.BOLD, ARCHIVE_RED)),
                    cx - r / 2, cy + 3f, 0);
            ColumnText.showTextAligned(over, Element.ALIGN_CENTER,
                    new Phrase(page + "/" + total, font(6.5f, Font.NORMAL, ARCHIVE_RED)),
                    cx - r / 2, cy - 6f, 0);
            over.restoreState();
        } catch (Exception e) {
            log.warn("骑缝章绘制失败(跳过): {}", e.getMessage());
        }
    }

    /* ==================== P7a-3 Tiptap JSON → 语义HTML ==================== */

    /**
     * 病历正文 → 语义HTML(供 renderElements 渲染):
     * 富文书HTML('<'开头, 含 div/table 时原样透传) / Tiptap JSON('{'/'['开头, 片段展开后转换) /
     * 其余(解密失败透传的密文或纯文本)优先取 structureData, 最终纯文本逐行。
     */
    private String tiptapContentToHtml(String content, String structure, Long visitId) {
        String c = content == null ? "" : content.trim();
        if (c.isEmpty()) {
            return isJsonDoc(structure) ? tiptapJsonToHtml(structure, visitId) : plaintextToHtml(structure);
        }
        char first = c.charAt(0);
        if (first == '<') {
            String body = extractBody(c);
            String lower = body.toLowerCase();
            if (lower.contains("<div") || lower.contains("<table")) {
                return body;
            }
            return plaintextToHtml(textOf(body));
        }
        if (first == '{' || first == '[') {
            return tiptapJsonToHtml(c, visitId);
        }
        /* 密文不可解时透传原文: 回退结构化数据, 避免把 Base64 噪音铺进 PDF */
        if (isJsonDoc(structure)) {
            return tiptapJsonToHtml(structure, visitId);
        }
        return looksLikeCipher(c) ? plaintextToHtml(structure) : plaintextToHtml(c);
    }

    /** Tiptap JSON 串 → HTML(doc 解析失败回退纯文本); visitId 非空时尽力解析宏填 resolvedValue。 */
    private String tiptapJsonToHtml(String json, Long visitId) {
        try {
            String resolved = emrDocumentService.resolveFragmentsInDocument(json);
            JSONObject doc = JSON.parseObject(resolved);
            if (doc == null) {
                return plaintextToHtml(json);
            }
            injectMacroValues(doc, visitId);
            return tiptapToHtml(doc);
        } catch (Exception e) {
            log.warn("Tiptap 解析失败, 按纯文本渲染: {}", e.getMessage());
            return plaintextToHtml(json);
        }
    }

    /**
     * 宏解析注入(尽力而为): 收集文档 emrMacro 节点 macroCode → EmrMacroService.resolveMacros 批量取值 →
     * 回填各节点 attrs.resolvedValue。无 visitId / 无宏 / 解析抛异常(如异步归档线程无机构上下文)
     * 均原样返回, 保持占位【macroCode】不回归。
     */
    private void injectMacroValues(JSONObject doc, Long visitId) {
        if (doc == null || visitId == null || macroService == null) {
            return;
        }
        Set<String> codes = new LinkedHashSet<>();
        collectMacroCodes(doc, codes);
        if (codes.isEmpty()) {
            return;
        }
        Map<String, String> values;
        try {
            R<Map<String, String>> r = macroService.resolveMacros(visitId, new ArrayList<>(codes));
            if (r == null || r.getData() == null || r.getData().isEmpty()) {
                return;
            }
            values = r.getData();
        } catch (Exception e) {
            log.warn("病历PDF宏解析跳过(无上下文/失败, 保持占位): visitId={}, {}", visitId, e.getMessage());
            return;
        }
        applyMacroValues(doc, values);
    }

    /** 递归收集 emrMacro 节点 attrs.macroCode。 */
    private static void collectMacroCodes(JSONObject node, Set<String> out) {
        if (node == null) {
            return;
        }
        if ("emrMacro".equals(node.getString("type"))) {
            JSONObject a = node.getJSONObject("attrs");
            String code = a == null ? "" : firstNonEmptyStr(a, "macroCode");
            if (StringUtils.hasText(code)) {
                out.add(code);
            }
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                Object child = content.get(i);
                if (child instanceof JSONObject) {
                    collectMacroCodes((JSONObject) child, out);
                }
            }
        }
    }

    /** 回填 emrMacro 节点 attrs.resolvedValue(已有值不覆盖; 无对应解析值保持占位)。 */
    private static void applyMacroValues(JSONObject node, Map<String, String> values) {
        if (node == null) {
            return;
        }
        if ("emrMacro".equals(node.getString("type"))) {
            JSONObject a = node.getJSONObject("attrs");
            if (a != null) {
                String code = firstNonEmptyStr(a, "macroCode");
                if (StringUtils.hasText(code) && values.containsKey(code)
                        && !StringUtils.hasText(firstNonEmptyStr(a, "resolvedValue"))) {
                    a.put("resolvedValue", values.get(code));
                }
            }
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                Object child = content.get(i);
                if (child instanceof JSONObject) {
                    applyMacroValues((JSONObject) child, values);
                }
            }
        }
    }

    /** Tiptap 文档对象 → HTML: doc.content 块级遍历; 无 content 时按扁平 JSON 键值行渲染。 */
    private String tiptapToHtml(JSONObject doc) {
        JSONArray content = doc.getJSONArray("content");
        if (content == null || content.isEmpty()) {
            StringBuilder flat = new StringBuilder();
            for (Map.Entry<String, Object> e : doc.entrySet()) {
                Object v = e.getValue();
                if (v == null || v instanceof JSONObject || v instanceof JSONArray) {
                    continue;
                }
                flat.append("<div class=\"emr-p\">").append(escapeHtml(e.getKey()))
                        .append("：").append(escapeHtml(fmtFieldValue(v))).append("</div>");
            }
            return flat.toString();
        }
        StringBuilder sb = new StringBuilder();
        Map<String, Object> fields = new LinkedHashMap<>();
        collectTiptapFieldValues(doc, fields);
        walkTiptapBlocks(content, sb, 0, fields);
        return sb.toString();
    }

    /** 块级节点遍历(深度上限防环): 段落/标题/章节/条件块/列表/表格/分页符等 → 语义 div/table。 */
    private void walkTiptapBlocks(JSONArray nodes, StringBuilder sb, int depth, Map<String, Object> fields) {
        if (nodes == null || depth > 12) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node == null) {
                continue;
            }
            String type = node.getString("type");
            if (type == null) {
                continue;
            }
            JSONObject attrs = node.getJSONObject("attrs");
            switch (type) {
                case "paragraph":
                    appendInlineBlock(node.getJSONArray("content"), sb, depth, "emr-p", "");
                    break;
                case "heading": {
                    int level = attrs == null ? 1 : Math.max(attrs.getIntValue("level"), 1);
                    appendInlineBlock(node.getJSONArray("content"), sb, depth,
                            level <= 2 ? "emr-sec" : "emr-sub", "");
                    break;
                }
                case "emrSection": {
                    /* 打印隐藏章节整段跳过(printHidden=true) */
                    if (attrs != null && attrs.getBooleanValue("printHidden")) {
                        break;
                    }
                    String title = attrs == null ? "" : firstNonEmptyStr(attrs, "title", "sectionKey");
                    if (StringUtils.hasText(title)) {
                        sb.append("<div class=\"").append(depth == 0 ? "emr-sec" : "emr-sub").append("\">")
                                .append(escapeHtml(title)).append("</div>");
                    }
                    walkTiptapBlocks(node.getJSONArray("content"), sb, depth + 1, fields);
                    break;
                }
                case "emrConditionalBlock":
                    if (evalTiptapCondition(attrs, fields)) {
                        walkTiptapBlocks(node.getJSONArray("content"), sb, depth + 1, fields);
                    }
                    break;
                case "bulletList":
                case "orderedList":
                    renderTiptapList(node, sb, "orderedList".equals(type), depth);
                    break;
                case "blockquote": {
                    StringBuilder inner = new StringBuilder();
                    appendInline(node.getJSONArray("content"), inner, depth);
                    for (String ln : inner.toString().split("<br>")) {
                        if (!ln.trim().isEmpty()) {
                            sb.append("<div class=\"emr-p\">　　").append(ln).append("</div>");
                        }
                    }
                    break;
                }
                case "codeBlock":
                    for (String ln : rawTextOf(node).split("\n")) {
                        if (!ln.isEmpty()) {
                            sb.append("<div class=\"memo\">").append(escapeHtml(ln)).append("</div>");
                        }
                    }
                    break;
                case "horizontalRule":
                    sb.append("<div class=\"memo\">──────────────</div>");
                    break;
                case "emrPageBreak":
                    sb.append("<div class=\"emr-break\"></div>");
                    break;
                case "table":
                    renderTiptapTable(node, sb, depth);
                    break;
                case "emrDrawing":
                    sb.append("<div class=\"memo\">［附图")
                            .append(escapeHtml(attrs == null ? "" : firstNonEmptyStr(attrs, "title")))
                            .append("］</div>");
                    break;
                case "emrFragment":
                    /* resolveFragmentsInDocument 已展开片段; 残留节点以占位提示 */
                    sb.append("<div class=\"memo\">［片段：")
                            .append(escapeHtml(attrs == null ? "" : firstNonEmptyStr(attrs, "title", "fragmentId")))
                            .append("］</div>");
                    break;
                case "emrField":
                case "emrMacro": {
                    StringBuilder line = new StringBuilder();
                    appendInlineNode(node, line, depth);
                    if (line.length() > 0) {
                        sb.append("<div class=\"emr-p\">").append(line).append("</div>");
                    }
                    break;
                }
                default:
                    /* 未知块级节点: 深挖 content 防丢内容 */
                    walkTiptapBlocks(node.getJSONArray("content"), sb, depth + 1, fields);
                    break;
            }
        }
    }

    /** 行内节点序列 → HTML(text/硬换行/字段/宏; 嵌套块递归)。 */
    private void appendInline(JSONArray nodes, StringBuilder sb, int depth) {
        if (nodes == null || depth > 12) {
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            appendInlineNode(nodes.getJSONObject(i), sb, depth);
        }
    }

    private void appendInlineNode(JSONObject node, StringBuilder sb, int depth) {
        if (node == null) {
            return;
        }
        String type = node.getString("type");
        if ("text".equals(type)) {
            if (trackDeleted(node.getJSONArray("marks"))) {
                return;
            }
            sb.append(escapeHtml(node.getString("text")));
            return;
        }
        if ("hardBreak".equals(type)) {
            sb.append("<br>");
            return;
        }
        if ("emrField".equals(type)) {
            JSONObject a = node.getJSONObject("attrs");
            String label = a == null ? "" : firstNonEmptyStr(a, "fieldName", "label", "fieldLabel", "fieldKey");
            String val = a == null ? "" : fmtFieldValue(a.get("value"));
            if (StringUtils.hasText(label)) {
                sb.append(escapeHtml(label)).append("：");
            }
            sb.append(StringUtils.hasText(val) ? escapeHtml(val) : "＿＿＿＿");
            return;
        }
        if ("emrMacro".equals(type)) {
            JSONObject a = node.getJSONObject("attrs");
            String resolved = a == null ? "" : firstNonEmptyStr(a, "resolvedValue");
            if (StringUtils.hasText(resolved)) {
                sb.append(escapeHtml(resolved));
            } else if (a != null) {
                sb.append("【").append(escapeHtml(firstNonEmptyStr(a, "macroCode"))).append("】");
            }
            return;
        }
        if ("emrDrawing".equals(type)) {
            sb.append("［附图］");
            return;
        }
        /* 嵌套块(段落/标题等): 递归提取并补换行分隔 */
        appendInline(node.getJSONArray("content"), sb, depth + 1);
        if ("paragraph".equals(type) || "heading".equals(type) || "blockquote".equals(type)) {
            sb.append("<br>");
        }
    }

    /**
     * 文本节点打印介质判定(一期约定): marks 含 emrTrack(op=delete) 则正式件丢弃该文本;
     * emrTrack(op=insert) 视为正文正常输出, emrComment 仅去高亮保留文本(此处本无样式, 直接保留)。
     */
    private static boolean trackDeleted(JSONArray marks) {
        if (marks == null || marks.isEmpty()) {
            return false;
        }
        for (int i = 0; i < marks.size(); i++) {
            JSONObject mk = marks.getJSONObject(i);
            if (mk == null || !"emrTrack".equals(mk.getString("type"))) {
                continue;
            }
            JSONObject a = mk.getJSONObject("attrs");
            if (a != null && "delete".equals(a.getString("op"))) {
                return true;
            }
        }
        return false;
    }

    /** 行内序列包成块级 div(空内容跳过); prefix 为行前缀(引用缩进等)。 */
    private void appendInlineBlock(JSONArray inline, StringBuilder sb, int depth, String cls, String prefix) {
        StringBuilder line = new StringBuilder();
        appendInline(inline, line, depth);
        String html = line.toString();
        /* 去除尾部换行分隔(hardBreak 的 <br> 保留) */
        while (html.endsWith("<br>")) {
            html = html.substring(0, html.length() - 4).trim();
        }
        html = html.trim();
        if (html.isEmpty()) {
            return;
        }
        sb.append("<div class=\"").append(cls).append("\">").append(prefix).append(html).append("</div>");
    }

    /** 表格 → HTML table(保留 th/td 与 colspan/rowspan; 单元格内块以 <br> 分隔)。 */
    private void renderTiptapTable(JSONObject tableNode, StringBuilder sb, int depth) {
        JSONArray rows = tableNode.getJSONArray("content");
        if (rows == null || depth > 12) {
            return;
        }
        StringBuilder t = new StringBuilder("<table>");
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row == null) {
                continue;
            }
            t.append("<tr>");
            JSONArray cells = row.getJSONArray("content");
            if (cells != null) {
                for (int j = 0; j < cells.size(); j++) {
                    appendTableCell(cells.getJSONObject(j), t, depth);
                }
            }
            t.append("</tr>");
        }
        t.append("</table>");
        sb.append(t);
    }

    private void appendTableCell(JSONObject cell, StringBuilder t, int depth) {
        if (cell == null) {
            return;
        }
        boolean header = "tableHeader".equals(cell.getString("type"));
        JSONObject attrs = cell.getJSONObject("attrs");
        StringBuilder cellSb = new StringBuilder();
        JSONArray cellContent = cell.getJSONArray("content");
        if (cellContent != null) {
            for (int k = 0; k < cellContent.size(); k++) {
                JSONObject cn = cellContent.getJSONObject(k);
                if (cn == null) {
                    continue;
                }
                String ct = cn.getString("type");
                if ("paragraph".equals(ct) || "heading".equals(ct)) {
                    if (cellSb.length() > 0) {
                        cellSb.append("<br>");
                    }
                    appendInline(cn.getJSONArray("content"), cellSb, depth + 1);
                } else {
                    appendInlineNode(cn, cellSb, depth + 1);
                }
            }
        }
        t.append('<').append(header ? "th" : "td");
        if (attrs != null) {
            int cs = attrs.getIntValue("colspan");
            int rs = attrs.getIntValue("rowspan");
            if (cs > 1) {
                t.append(" colspan=\"").append(cs).append('"');
            }
            if (rs > 1) {
                t.append(" rowspan=\"").append(rs).append('"');
            }
        }
        t.append('>').append(cellSb).append("</").append(header ? "th" : "td").append('>');
    }

    /** 列表 → 前缀行(·/N.), 嵌套列表行缩进两全角。 */
    private void renderTiptapList(JSONObject listNode, StringBuilder sb, boolean ordered, int depth) {
        JSONArray items = listNode.getJSONArray("content");
        if (items == null || depth > 12) {
            return;
        }
        int idx = 1;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null || !"listItem".equals(item.getString("type"))) {
                continue;
            }
            String prefix = ordered ? (idx++) + ". " : "· ";
            JSONArray itemContent = item.getJSONArray("content");
            if (itemContent == null) {
                continue;
            }
            for (int j = 0; j < itemContent.size(); j++) {
                JSONObject child = itemContent.getJSONObject(j);
                if (child == null) {
                    continue;
                }
                String ct = child.getString("type");
                if ("bulletList".equals(ct) || "orderedList".equals(ct)) {
                    StringBuilder nested = new StringBuilder();
                    renderTiptapList(child, nested, "orderedList".equals(ct), depth + 1);
                    sb.append(nested.toString().replace("<div class=\"emr-p\">", "<div class=\"emr-p\">　　"));
                } else {
                    appendInlineBlock(child.getJSONArray("content"), sb, depth, "emr-p", prefix);
                    /* 同项后续段落对齐到首个文字位 */
                    prefix = "　";
                }
            }
        }
    }

    /** 递归收集 emrField 的 fieldKey→value(条件块求值数据源, 与前端 collectFieldValuesFromDoc 同口径)。 */
    private void collectTiptapFieldValues(JSONObject node, Map<String, Object> out) {
        if (node == null) {
            return;
        }
        if ("emrField".equals(node.getString("type"))) {
            JSONObject a = node.getJSONObject("attrs");
            if (a != null) {
                String key = firstNonEmptyStr(a, "fieldKey");
                if (StringUtils.hasText(key)) {
                    out.put(key, a.get("value"));
                }
            }
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                collectTiptapFieldValues(content.getJSONObject(i), out);
            }
        }
    }

    /** 条件块求值(与 emr-extensions.js evaluateCondition 同口径): eq/ne/contains/empty/notEmpty, 缺省可见。 */
    private boolean evalTiptapCondition(JSONObject attrs, Map<String, Object> fields) {
        if (attrs == null) {
            return true;
        }
        String op = attrs.getString("conditionOperator");
        if (!StringUtils.hasText(op)) {
            return true;
        }
        Object raw = fields.get(attrs.getString("conditionFieldKey"));
        String cv = attrs.getString("conditionValue");
        cv = cv == null ? "" : cv;
        String sv;
        List<String> arr = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray ja = (JSONArray) raw;
            for (int i = 0; i < ja.size(); i++) {
                arr.add(str(ja.get(i)));
            }
            sv = String.join(",", arr);
        } else {
            sv = raw == null ? "" : fmtFieldValue(raw);
        }
        switch (op) {
            case "eq":
                return sv.equals(cv);
            case "ne":
                return !sv.equals(cv);
            case "contains":
                return raw instanceof JSONArray ? arr.contains(cv) : (!cv.isEmpty() && sv.contains(cv));
            case "empty":
                return sv.isEmpty();
            case "notEmpty":
                return !sv.isEmpty();
            default:
                return true;
        }
    }

    /** 节点提纯文本(text/字段/宏/硬换行, 深度递归)。 */
    private String rawTextOf(JSONObject node) {
        StringBuilder sb = new StringBuilder();
        rawTextCollect(node, sb);
        return sb.toString();
    }

    private void rawTextCollect(JSONObject node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        String type = node.getString("type");
        if ("text".equals(type)) {
            if (trackDeleted(node.getJSONArray("marks"))) {
                return;
            }
            sb.append(str(node.getString("text")));
            return;
        }
        if ("hardBreak".equals(type)) {
            sb.append('\n');
            return;
        }
        if ("emrField".equals(type)) {
            JSONObject a = node.getJSONObject("attrs");
            if (a != null) {
                String label = firstNonEmptyStr(a, "fieldName", "fieldKey");
                if (StringUtils.hasText(label)) {
                    sb.append(label).append("：");
                }
                sb.append(fmtFieldValue(a.get("value")));
            }
            return;
        }
        if ("emrMacro".equals(type)) {
            JSONObject a = node.getJSONObject("attrs");
            if (a != null) {
                String resolved = firstNonEmptyStr(a, "resolvedValue");
                sb.append(StringUtils.hasText(resolved) ? resolved
                        : "【" + firstNonEmptyStr(a, "macroCode") + "】");
            }
            return;
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                rawTextCollect(content.getJSONObject(i), sb);
            }
        }
    }

    /** 字段值 → 文本: 数组顿号拼接 / 对象取常识键 / 其他字符串化(与前端 String(v) 口径一致)。 */
    private static String fmtFieldValue(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof JSONArray) {
            JSONArray ja = (JSONArray) v;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < ja.size(); i++) {
                String s = fmtFieldValue(ja.get(i));
                if (StringUtils.hasText(s)) {
                    parts.add(s);
                }
            }
            return String.join("、", parts);
        }
        if (v instanceof JSONObject) {
            JSONObject jo = (JSONObject) v;
            String s = firstNonEmptyStr(jo, "name", "label", "text", "value", "diagName", "itemName", "code");
            return StringUtils.hasText(s) ? s : JSON.toJSONString(v);
        }
        return String.valueOf(v);
    }

    /** 纯文本 → 逐行 emr-p 段落。 */
    private static String plaintextToHtml(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String ln : text.split("\r?\n")) {
            if (ln.trim().isEmpty()) {
                continue;
            }
            sb.append("<div class=\"emr-p\">").append(escapeHtml(ln.trim())).append("</div>");
        }
        return sb.toString();
    }
}
