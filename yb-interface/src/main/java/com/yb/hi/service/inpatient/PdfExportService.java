package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.Utilities;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
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
import java.util.List;
import java.util.Map;
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

    /** 分钟级时间(体温记录) */
    private static final DateTimeFormatter DT_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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

    public PdfExportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
}
