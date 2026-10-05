package com.yb.hi.service.ris;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.medtech.HisExamReport;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.entity.ris.HisRisReportData;
import com.yb.hi.entity.ris.HisRisReportElement;
import com.yb.hi.entity.ris.HisRisReportTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.medtech.HisExamReportMapper;
import com.yb.hi.mapper.ris.HisRisReportDataMapper;
import com.yb.hi.mapper.ris.HisRisReportElementMapper;
import com.yb.hi.mapper.ris.HisRisReportTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 结构化报告数据服务(报告×数据元键值存储 + 模板实例化 + 所见/结论分段渲染)。
 * 口径:
 * 1) 数据载体: 报告数据行(his_ris_report_data)按 数据元(element) 键值落库, 文本/数值/编码三载体并存;
 * 2) 保存为整体替换: 旧行逻辑删除(保留审计), 新行重新插入; 同时回写 his_exam_report.template_id
 *    与 structured_data 快照 JSON(供报告列表/审核端免 join 概览);
 * 3) 渲染: 按数据元所属段(FINDINGS/CONCLUSION/TECHNIQUE)分段拼接自由文本,
 *    取值优先 value_text -> value_code(数值附单位), 空值跳过;
 * 4) 模板实例化: 模板数据元按 defaultValue 填充生成空报告数据(不落库), 供书写端初始编辑态。
 */
@Slf4j
@Service
public class RisStructuredReportService {

    private final HisRisReportDataMapper dataMapper;
    private final HisRisReportElementMapper elementMapper;
    private final HisRisReportTemplateMapper templateMapper;
    private final HisExamReportMapper reportMapper;
    private final JdbcTemplate jdbcTemplate;

    public RisStructuredReportService(HisRisReportDataMapper dataMapper,
                                      HisRisReportElementMapper elementMapper,
                                      HisRisReportTemplateMapper templateMapper,
                                      HisExamReportMapper reportMapper,
                                      JdbcTemplate jdbcTemplate) {
        this.dataMapper = dataMapper;
        this.elementMapper = elementMapper;
        this.templateMapper = templateMapper;
        this.reportMapper = reportMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 结构化报告数据项(轻量入参/出参; 任务书未定义 RisReportDataDTO, 以静态内部类承载)。 */
    public static class ReportDataItem {
        /** 数据元ID(可选, 优先按 elementCode 匹配) */
        private Long elementId;
        /** 数据元编码(必填键) */
        private String elementCode;
        /** 文本值 */
        private String valueText;
        /** 数值 */
        private BigDecimal valueNumber;
        /** 编码值 */
        private String valueCode;

        public ReportDataItem() {
        }

        public ReportDataItem(String elementCode, String valueText) {
            this.elementCode = elementCode;
            this.valueText = valueText;
        }

        public Long getElementId() {
            return elementId;
        }

        public void setElementId(Long elementId) {
            this.elementId = elementId;
        }

        public String getElementCode() {
            return elementCode;
        }

        public void setElementCode(String elementCode) {
            this.elementCode = elementCode;
        }

        public String getValueText() {
            return valueText;
        }

        public void setValueText(String valueText) {
            this.valueText = valueText;
        }

        public BigDecimal getValueNumber() {
            return valueNumber;
        }

        public void setValueNumber(BigDecimal valueNumber) {
            this.valueNumber = valueNumber;
        }

        public String getValueCode() {
            return valueCode;
        }

        public void setValueCode(String valueCode) {
            this.valueCode = valueCode;
        }
    }

    /* ================= 保存与查询 ================= */

    /**
     * 保存报告结构化数据(整体替换): 校验报告存在 -> 旧行逻辑删除 -> 逐行插入 ->
     * 回写 his_exam_report.template_id + structured_data 快照。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<HisRisReportData> saveReportData(Long reportId, Long templateId, List<ReportDataItem> dataList) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisExamReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(400, "报告不存在");
        }
        if (dataList == null) {
            dataList = new ArrayList<>();
        }
        // 整体替换: 旧行逻辑删除(保留审计), 新行重新插入
        dataMapper.delete(Wrappers.<HisRisReportData>lambdaQuery()
                .eq(HisRisReportData::getReportId, reportId));
        // 数据元编码 -> 定义(默认值/单位等快照用)
        Map<String, HisRisReportElement> elementMap = elementMapOf(templateId != null ? templateId : templateIdOf(reportId));
        List<HisRisReportData> saved = new ArrayList<>();
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (ReportDataItem item : dataList) {
            if (item == null || !StringUtils.hasText(item.getElementCode())) {
                continue;
            }
            boolean empty = !StringUtils.hasText(item.getValueText())
                    && item.getValueNumber() == null
                    && !StringUtils.hasText(item.getValueCode());
            if (empty) {
                continue;
            }
            HisRisReportData row = new HisRisReportData();
            row.setOrgId(report.getOrgId());
            row.setReportId(reportId);
            row.setElementId(item.getElementId());
            row.setElementCode(item.getElementCode().trim());
            row.setValueText(StringUtils.hasText(item.getValueText()) ? item.getValueText() : null);
            row.setValueNumber(item.getValueNumber());
            row.setValueCode(StringUtils.hasText(item.getValueCode()) ? item.getValueCode() : null);
            dataMapper.insert(row);
            saved.add(row);
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("elementCode", row.getElementCode());
            HisRisReportElement def = elementMap.get(row.getElementCode());
            snap.put("elementName", def == null ? row.getElementCode() : def.getElementName());
            snap.put("section", def == null ? null : def.getSection());
            snap.put("valueText", row.getValueText());
            snap.put("valueNumber", row.getValueNumber());
            snap.put("valueCode", row.getValueCode());
            snapshot.add(snap);
        }
        // 回写报告挂接: 模板ID + 结构化快照(审核端免 join 概览); 两列为 RIS 迁移补列, 实体未映射, 走 JdbcTemplate
        String snapJson = JSON.toJSONString(snapshot);
        if (templateId != null) {
            jdbcTemplate.update(
                    "UPDATE his_exam_report SET template_id = ?, structured_data = ?, update_time = NOW()"
                            + " WHERE id = ? AND tenant_id = ?",
                    templateId, snapJson, reportId, tenantId());
        } else {
            jdbcTemplate.update(
                    "UPDATE his_exam_report SET structured_data = ?, update_time = NOW()"
                            + " WHERE id = ? AND tenant_id = ?",
                    snapJson, reportId, tenantId());
        }
        log.info("RIS结构化报告保存: reportId={}, templateId={}, 数据{}项", reportId, templateId, saved.size());
        return saved;
    }

    /** 报告的结构化数据(附数据元名称/单位, 按数据元ID排序)。 */
    public List<Map<String, Object>> getReportData(Long reportId) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        List<HisRisReportData> rows = dataMapper.selectList(Wrappers.<HisRisReportData>lambdaQuery()
                .eq(HisRisReportData::getReportId, reportId)
                .orderByAsc(HisRisReportData::getElementId));
        Map<String, HisRisReportElement> elementMap = elementMapOf(templateIdOf(reportId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (HisRisReportData r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("elementId", r.getElementId());
            m.put("elementCode", r.getElementCode());
            HisRisReportElement def = elementMap.get(r.getElementCode());
            m.put("elementName", def == null ? r.getElementCode() : def.getElementName());
            m.put("elementType", def == null ? null : def.getElementType());
            m.put("section", def == null ? null : def.getSection());
            m.put("valueUnit", def == null ? null : def.getValueUnit());
            m.put("valueText", r.getValueText());
            m.put("valueNumber", r.getValueNumber());
            m.put("valueCode", r.getValueCode());
            out.add(m);
        }
        return out;
    }

    /* ================= 渲染 ================= */

    /**
     * 结构化数据渲染为自由文本(所见/结论/技术描述分段拼接):
     * 每行 "数据元名称：取值"(数值附单位), 空值跳过; 同段行以换行拼接。
     * 返回 { findings, conclusion, technique, fullText }。
     */
    public Map<String, Object> renderToText(Long reportId) {
        List<Map<String, Object>> data = getReportData(reportId);
        StringBuilder findings = new StringBuilder();
        StringBuilder conclusion = new StringBuilder();
        StringBuilder technique = new StringBuilder();
        for (Map<String, Object> row : data) {
            String section = str(row.get("section"));
            String line = renderLine(row);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if ("CONCLUSION".equalsIgnoreCase(section)) {
                appendLine(conclusion, line);
            } else if ("TECHNIQUE".equalsIgnoreCase(section)) {
                appendLine(technique, line);
            } else {
                // 未标识段默认归入所见(FINDINGS), 与数据元段定义兜底口径一致
                appendLine(findings, line);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findings", findings.length() == 0 ? null : findings.toString());
        out.put("conclusion", conclusion.length() == 0 ? null : conclusion.toString());
        out.put("technique", technique.length() == 0 ? null : technique.toString());
        StringBuilder full = new StringBuilder();
        if (findings.length() > 0) {
            full.append(findings);
        }
        if (conclusion.length() > 0) {
            if (full.length() > 0) {
                full.append("\n\n");
            }
            full.append(conclusion);
        }
        out.put("fullText", full.length() == 0 ? null : full.toString());
        return out;
    }

    /** 单行渲染: "名称：取值 单位"; 文本值优先, 编码值兜底。 */
    private static String renderLine(Map<String, Object> row) {
        String name = str(row.get("elementName"));
        String text = str(row.get("valueText"));
        String code = str(row.get("valueCode"));
        Object number = row.get("valueNumber");
        String value = StringUtils.hasText(text) ? text : (number != null ? String.valueOf(number) : code);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        StringBuilder line = new StringBuilder();
        if (StringUtils.hasText(name)) {
            line.append(name).append("：");
        }
        line.append(value.trim());
        String unit = str(row.get("valueUnit"));
        if (number != null && StringUtils.hasText(unit)) {
            line.append(" ").append(unit);
        }
        return line.toString();
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(line);
    }

    /* ================= 模板实例化 ================= */

    /**
     * 实例化模板为空报告数据(不落库): 模板实体 + 数据元定义 + defaultValue 填充的数据项列表,
     * 供报告书写端作为初始编辑态, 保存时经 saveReportData 落库。
     */
    public Map<String, Object> instantiateTemplate(Long templateId) {
        if (templateId == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        HisRisReportTemplate t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException(400, "报告模板不存在");
        }
        List<HisRisReportElement> elements = elementMapper.selectList(Wrappers.<HisRisReportElement>lambdaQuery()
                .eq(HisRisReportElement::getTemplateId, templateId)
                .orderByAsc(HisRisReportElement::getSortOrder)
                .orderByAsc(HisRisReportElement::getId));
        List<ReportDataItem> items = new ArrayList<>();
        for (HisRisReportElement el : elements) {
            ReportDataItem item = new ReportDataItem();
            item.setElementId(el.getId());
            item.setElementCode(el.getElementCode());
            item.setValueText(StringUtils.hasText(el.getDefaultValue()) ? el.getDefaultValue() : null);
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", t);
        out.put("elements", elements);
        out.put("items", items);
        return out;
    }

    /* ================= 辅助 ================= */

    /** 模板数据元编码 -> 定义映射(模板未挂或已删返回空映射, 渲染时以数据元编码兜底)。 */
    private Map<String, HisRisReportElement> elementMapOf(Long templateId) {
        Map<String, HisRisReportElement> map = new HashMap<>();
        if (templateId == null) {
            return map;
        }
        List<HisRisReportElement> elements = elementMapper.selectList(Wrappers.<HisRisReportElement>lambdaQuery()
                .eq(HisRisReportElement::getTemplateId, templateId));
        for (HisRisReportElement el : elements) {
            map.put(el.getElementCode(), el);
        }
        return map;
    }

    /** 报告挂接的模板ID(RIS 迁移补列, 实体未映射, JdbcTemplate 读取)。 */
    private Long templateIdOf(Long reportId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT template_id FROM his_exam_report WHERE id = ? AND tenant_id = ? AND deleted = 0",
                reportId, tenantId());
        if (rows.isEmpty() || rows.get(0).get("template_id") == null) {
            return null;
        }
        Object v = rows.get(0).get("template_id");
        return v instanceof Number ? ((Number) v).longValue() : Long.valueOf(v.toString());
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
