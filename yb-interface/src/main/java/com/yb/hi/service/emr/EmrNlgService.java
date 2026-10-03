package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisEmrNlgTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.inpatient.EmrNlgTemplateMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 病历NLG自然语言生成服务: 按章节将结构化字段值转换为叙述文本(his_emr_nlg_template)。
 *
 * 模板语法:
 * - templateText 含占位符 {fieldKey}(仅字母/数字/下划线/点/连字符, 中文等其余文本为字面量, 不受影响);
 *   占位符集合即参与生成的字段集, 模板语序即缺省输出语序;
 * - sortRules 输出语序(JSON数组, 如 ["location","nature","symptom","duration"]): 列出字段按给定顺序前移,
 *   未列出的模板字段按占位符首现序补后; 兼容 {"order":[...]} 对象形式;
 * - connectors 连接词JSON {"default":"兜底连接词","rules":[{"after":"前字段","before":"后字段","word":"连接词"}]}:
 *   after/before 可省略其一(仅约束单侧), 首条命中规则生效; 命中优先于模板字面量间隔, 其次兜底 default。
 *   连接词仅作用于"相邻且均有值"的字段 —— 字段为空时连同连接词一并跳过(条件连接);
 * - 模板中两个相邻占位符之间的非空字面量自动作为二者间隔连接词(如 "{a}，{b}" 的 "，"); 首/尾字面量在
 *   至少一个字段有值时原样保留; 全部字段为空返回空串。
 * 示例: {location:"上腹部", symptom:"疼痛", nature:"阵发性", duration:"3天"}
 *   + 模板 "{location}{nature}{symptom}{duration}" → "上腹部阵发性疼痛3天"。
 *
 * 约定:
 * - 写操作(新建/更新/删除)仅牵头机构管理员({@link OrgAccessGuard#requireLeadOrg}), 新建行绑定当前机构;
 *   读操作受 MyBatis-Plus 租户插件隔离;
 * - 生成(读操作)不限角色: generate 单章节(无模板抛 400), generateForDocument 整文逐章节(无模板章节静默跳过,
 *   入参为明文 Tiptap JSON, 密文先经 EmrDocumentService.loadDocument 解密);
 * - 同一章节可配多条模板(scope 区分住院/门诊), 选取顺序: 具体范围优先(scope desc) → id 升序;
 * - 模板数量少, 列表不分页。
 */
@Slf4j
@Service
public class EmrNlgService {

    /** 占位符模式: {fieldKey} */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");

    private final EmrNlgTemplateMapper templateMapper;
    private final OrgAccessGuard guard;

    public EmrNlgService(EmrNlgTemplateMapper templateMapper, OrgAccessGuard guard) {
        this.templateMapper = templateMapper;
        this.guard = guard;
    }

    /* ================= 模板查询 ================= */

    /**
     * 模板列表(不分页, 模板量少)。
     * recordType 映射适用范围: "2"/门诊/OUTP → 2, "1"/住院/INP 及其他数值(住院文书类型) → 1,
     * 空/"0"/"全部" → 不过滤; 过滤时命中 scope IN (0, 推导值)(含通用模板)。
     * sectionKey 可选精确过滤; 按 section_key → id 升序。
     */
    public R<List<HisEmrNlgTemplate>> list(String recordType, String sectionKey) {
        Integer scope = resolveScope(recordType);
        LambdaQueryWrapper<HisEmrNlgTemplate> qw = Wrappers.<HisEmrNlgTemplate>lambdaQuery()
                .eq(StringUtils.hasText(sectionKey), HisEmrNlgTemplate::getSectionKey,
                        sectionKey == null ? null : sectionKey.trim());
        if (scope != null) {
            qw.in(HisEmrNlgTemplate::getScope, Arrays.asList(0, scope));
        }
        qw.orderByAsc(HisEmrNlgTemplate::getSectionKey).orderByAsc(HisEmrNlgTemplate::getId);
        return R.ok(templateMapper.selectList(qw));
    }

    /** 模板详情 */
    public R<HisEmrNlgTemplate> get(Long id) {
        HisEmrNlgTemplate t = id == null ? null : templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(400, "病历NLG模板不存在");
        }
        return R.ok(t);
    }

    /* ================= 模板维护 ================= */

    /** 新建/更新模板(id 空=新建并绑定当前机构; 仅牵头机构管理员; 章节key必填, 新建时模板文本必填; null=更新时不修改) */
    public R<HisEmrNlgTemplate> save(HisEmrNlgTemplate in) {
        if (in == null) {
            throw new BizException(400, "模板内容不能为空");
        }
        guard.requireLeadOrg("仅牵头机构管理员可维护病历NLG模板");
        String sectionKey = requireText(in.getSectionKey(), "章节key不能为空");
        if (in.getId() == null) {
            String templateText = requireText(in.getTemplateText(), "模板文本不能为空");
            HisEmrNlgTemplate t = new HisEmrNlgTemplate();
            t.setOrgId(guard.currentOrgId());
            t.setScope(in.getScope() != null ? in.getScope() : 0);
            t.setSectionKey(sectionKey);
            t.setSectionName(trimToNull(in.getSectionName()));
            t.setTemplateText(templateText);
            t.setConnectors(trimToNull(in.getConnectors()));
            t.setSortRules(trimToNull(in.getSortRules()));
            t.setEnabled(in.getEnabled() != null ? in.getEnabled() : 1);
            templateMapper.insert(t);
            log.info("新建病历NLG模板: id={}, sectionKey={}, scope={}", t.getId(), sectionKey, t.getScope());
            return R.ok(templateMapper.selectById(t.getId()));
        }
        HisEmrNlgTemplate exist = templateMapper.selectById(in.getId());
        if (exist == null) {
            throw new BizException(400, "病历NLG模板不存在");
        }
        exist.setSectionKey(sectionKey);
        if (in.getSectionName() != null) {
            exist.setSectionName(trimToNull(in.getSectionName()));
        }
        if (in.getTemplateText() != null) {
            exist.setTemplateText(requireText(in.getTemplateText(), "模板文本不能为空"));
        }
        if (in.getConnectors() != null) {
            exist.setConnectors(trimToNull(in.getConnectors()));
        }
        if (in.getSortRules() != null) {
            exist.setSortRules(trimToNull(in.getSortRules()));
        }
        if (in.getScope() != null) {
            exist.setScope(in.getScope());
        }
        if (in.getEnabled() != null) {
            exist.setEnabled(in.getEnabled());
        }
        templateMapper.updateById(exist);
        log.info("更新病历NLG模板: id={}, sectionKey={}", exist.getId(), exist.getSectionKey());
        return R.ok(templateMapper.selectById(exist.getId()));
    }

    /** 删除模板(逻辑删除; 仅牵头机构管理员) */
    public R<Void> delete(Long id) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历NLG模板");
        HisEmrNlgTemplate exist = id == null ? null : templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "病历NLG模板不存在");
        }
        templateMapper.deleteById(id);
        log.info("删除病历NLG模板: id={}, sectionKey={}", id, exist.getSectionKey());
        return R.ok();
    }

    /* ================= NLG 生成 ================= */

    /** 单章节生成: 按 sectionKey 取启用模板(scope 不限, 具体范围优先), 字段值替换占位符并组装; 章节无模板抛 400 */
    public R<String> generate(String sectionKey, Map<String, Object> fieldValues) {
        if (!StringUtils.hasText(sectionKey)) {
            throw new BizException(400, "章节key不能为空");
        }
        String key = sectionKey.trim();
        HisEmrNlgTemplate tpl = pickTemplate(key, null);
        if (tpl == null) {
            throw new BizException(400, "未配置章节[" + key + "]的NLG生成模板");
        }
        return R.ok(render(tpl, fieldValues));
    }

    /**
     * 整文生成: 解析 Tiptap 明文 JSON, 逐章节(emrSection.attrs.key)抽取直属 emrField 字段值,
     * 命中启用模板的章节生成叙述文本, 返回 Map<sectionKey, text>(未命中模板/生成空文本的章节跳过)。
     * recordType 映射适用范围(门诊2/住院1)用于模板选取; 嵌套子章节作为独立章节登记(字段归最内层章节)。
     * 解析失败仅告警返回已生成部分(不抛异常); 入参为明文 JSON(密文先经 EmrDocumentService.loadDocument 解密)。
     */
    public R<Map<String, String>> generateForDocument(String tiptapJson, String recordType) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!StringUtils.hasText(tiptapJson)) {
            return R.ok(out);
        }
        try {
            JSONObject doc = JSON.parseObject(tiptapJson);
            if (doc == null) {
                return R.ok(out);
            }
            Integer scope = resolveScope(recordType);
            Map<String, Map<String, String>> sectionFields = new LinkedHashMap<>();
            walkSections(doc, sectionFields);
            for (Map.Entry<String, Map<String, String>> e : sectionFields.entrySet()) {
                HisEmrNlgTemplate tpl = pickTemplate(e.getKey(), scope);
                if (tpl == null) {
                    continue; // 未配置模板的章节静默跳过
                }
                String text = render(tpl, e.getValue());
                if (StringUtils.hasText(text)) {
                    out.put(e.getKey(), text);
                }
            }
            log.info("病历NLG整文生成: 章节命中{}/{}", out.size(), sectionFields.size());
        } catch (Exception e) {
            log.warn("病历NLG整文生成失败(返回已生成部分): {}", e.getMessage());
        }
        return R.ok(out);
    }

    /* ================= 内部实现: 模板选取 ================= */

    /** 取启用模板: section_key 精确命中, scope 给定时命中 IN(0, scope), 具体范围优先(scope desc)→id 升序 */
    private HisEmrNlgTemplate pickTemplate(String sectionKey, Integer scope) {
        LambdaQueryWrapper<HisEmrNlgTemplate> qw = Wrappers.<HisEmrNlgTemplate>lambdaQuery()
                .eq(HisEmrNlgTemplate::getSectionKey, sectionKey)
                .eq(HisEmrNlgTemplate::getEnabled, 1);
        if (scope != null) {
            qw.in(HisEmrNlgTemplate::getScope, Arrays.asList(0, scope));
        }
        qw.orderByDesc(HisEmrNlgTemplate::getScope).orderByAsc(HisEmrNlgTemplate::getId);
        List<HisEmrNlgTemplate> list = templateMapper.selectList(qw);
        return list.isEmpty() ? null : list.get(0);
    }

    /* ================= 内部实现: 渲染 ================= */

    /**
     * 渲染核心:
     * 1) 词法切分模板文本为 字面量/占位符(占位符定义参与字段集);
     * 2) 输出语序 = sortRules 数组优先(未列出字段按占位符首现序补后), 缺省按占位符首现序;
     * 3) 仅保留有值字段; 相邻字段间连接词 = 显式规则 > 模板字面量间隔 > default > 无;
     * 4) 模板首/尾字面量在至少一个字段有值时原样保留; 全部字段为空返回空串; 无占位符原样返回模板。
     */
    private String render(HisEmrNlgTemplate tpl, Map<String, ?> fieldValues) {
        String templateText = tpl.getTemplateText();
        if (!StringUtils.hasText(templateText)) {
            return "";
        }
        // 1) 词法切分 + 占位符首现序 + 相邻占位符间的非空字面量(隐式连接词) + 首/尾字面量
        List<String> templateOrder = new ArrayList<>();
        Map<String, String> gapLiterals = new LinkedHashMap<>();
        String leading = "";
        String trailing = "";
        String prevField = null;
        StringBuilder literalBuf = new StringBuilder();
        boolean seenPlaceholder = false;
        Matcher m = PLACEHOLDER.matcher(templateText);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) {
                literalBuf.append(templateText, pos, m.start());
            }
            String field = m.group(1);
            if (!seenPlaceholder) {
                leading = literalBuf.toString();
            } else if (literalBuf.length() > 0) {
                gapLiterals.put(prevField + "|" + field, literalBuf.toString());
            }
            literalBuf.setLength(0);
            if (!templateOrder.contains(field)) {
                templateOrder.add(field);
            }
            prevField = field;
            seenPlaceholder = true;
            pos = m.end();
        }
        if (seenPlaceholder && pos < templateText.length()) {
            literalBuf.append(templateText, pos, templateText.length());
        }
        trailing = seenPlaceholder ? literalBuf.toString() : "";
        if (templateOrder.isEmpty()) {
            return templateText; // 无占位符: 原样返回(视为静态文本模板)
        }
        // 2) 输出语序
        List<String> finalOrder = new ArrayList<>();
        for (String f : parseOrderArray(tpl.getSortRules())) {
            if (templateOrder.contains(f) && !finalOrder.contains(f)) {
                finalOrder.add(f);
            }
        }
        for (String f : templateOrder) {
            if (!finalOrder.contains(f)) {
                finalOrder.add(f);
            }
        }
        // 3) 逐字段组装(仅保留有值字段, 连接词随字段条件生效)
        JSONObject connectors = parseConnectors(tpl.getConnectors());
        StringBuilder body = new StringBuilder();
        String lastField = null;
        boolean any = false;
        for (String f : finalOrder) {
            String v = valueText(fieldValues == null ? null : fieldValues.get(f));
            if (v == null) {
                continue;
            }
            if (any) {
                body.append(connectorBetween(lastField, f, gapLiterals, connectors));
            }
            body.append(v);
            lastField = f;
            any = true;
        }
        if (!any) {
            return ""; // 全部字段为空: 不产出文本
        }
        // 4) 首/尾字面量在至少一个字段有值时保留
        StringBuilder out = new StringBuilder();
        if (!leading.isEmpty()) {
            out.append(leading);
        }
        out.append(body);
        if (!trailing.isEmpty()) {
            out.append(trailing);
        }
        return out.toString().trim();
    }

    /** 相邻字段连接词: 显式规则(after/before 可省略其一, 首条命中生效) > 模板字面量间隔 > default > 无 */
    private String connectorBetween(String after, String before, Map<String, String> gapLiterals, JSONObject connectors) {
        if (connectors != null) {
            JSONArray rules = connectors.getJSONArray("rules");
            if (rules != null) {
                for (int i = 0; i < rules.size(); i++) {
                    JSONObject rule = rules.getJSONObject(i);
                    if (rule == null) {
                        continue;
                    }
                    String ra = rule.getString("after");
                    String rb = rule.getString("before");
                    if (ra == null && rb == null) {
                        continue; // 无约束规则无意义, 跳过
                    }
                    if ((ra == null || ra.equals(after)) && (rb == null || rb.equals(before))) {
                        String w = rule.getString("word");
                        return w == null ? "" : w;
                    }
                }
            }
        }
        String lit = gapLiterals.get(after + "|" + before);
        if (lit != null) {
            return lit;
        }
        if (connectors != null) {
            String d = connectors.getString("default");
            if (d != null) {
                return d;
            }
        }
        return "";
    }

    /** 语序规则解析: 数组或 {"order":[...]}/{"fieldOrder":[...]} 对象; 解析失败按模板顺序(仅告警) */
    private List<String> parseOrderArray(String sortRulesJson) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(sortRulesJson)) {
            return out;
        }
        try {
            Object parsed = JSON.parse(sortRulesJson);
            JSONArray arr = null;
            if (parsed instanceof JSONArray) {
                arr = (JSONArray) parsed;
            } else if (parsed instanceof JSONObject) {
                JSONObject o = (JSONObject) parsed;
                arr = o.getJSONArray("order");
                if (arr == null) {
                    arr = o.getJSONArray("fieldOrder");
                }
            }
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    String s = str(arr.get(i));
                    if (StringUtils.hasText(s) && !out.contains(s)) {
                        out.add(s);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("NLG语序规则JSON解析失败(按模板顺序生成): {}", e.getMessage());
        }
        return out;
    }

    /** 连接词配置解析(非法JSON按无连接词配置处理, 仅告警) */
    private JSONObject parseConnectors(String connectorsJson) {
        if (!StringUtils.hasText(connectorsJson)) {
            return null;
        }
        try {
            return JSON.parseObject(connectorsJson);
        } catch (Exception e) {
            log.warn("NLG连接词JSON解析失败(按模板字面量生成): {}", e.getMessage());
            return null;
        }
    }

    /** 字段值 → 叙述文本: null/空白不产出; 集合逐项取值后以"、"连接; 对象值取 名称类字段 > 编码类字段; 整数值的Double去掉尾缀.0 */
    private static String valueText(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Collection<?>) {
            List<String> parts = new ArrayList<>();
            for (Object item : (Collection<?>) v) {
                String s = valueText(item);
                if (s != null) {
                    parts.add(s);
                }
            }
            return parts.isEmpty() ? null : String.join("、", parts);
        }
        if (v instanceof Map<?, ?>) {
            Map<?, ?> mp = (Map<?, ?>) v;
            for (String k : new String[]{"name", "label", "text", "diagName", "itemName", "value", "code"}) {
                String s = valueText(mp.get(k));
                if (s != null) {
                    return s;
                }
            }
            return null;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equals(s)) {
            return null;
        }
        return s;
    }

    /* ================= 内部实现: Tiptap 章节与字段抽取 ================= */

    /**
     * 章节登记(递归): 每个 emrSection(key 非空) 收集其"直属"字段(不下钻嵌套子章节, 子章节字段归各自章节);
     * 嵌套 emrSection 作为独立章节入口登记; 同 key 章节合并(首值优先, 幂等)。
     */
    private void walkSections(JSONObject node, Map<String, Map<String, String>> acc) {
        if (node == null) {
            return;
        }
        if ("emrSection".equalsIgnoreCase(str(node.get("type")))) {
            JSONObject attrs = node.getJSONObject("attrs");
            String key = attrs == null ? null : str(attrs.get("key"));
            Map<String, String> fields = StringUtils.hasText(key)
                    ? acc.computeIfAbsent(key.trim(), k -> new LinkedHashMap<>()) : null;
            JSONArray content = node.getJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.size(); i++) {
                    Object c = content.get(i);
                    if (c instanceof JSONObject) {
                        collectFieldsFrom((JSONObject) c, fields, acc);
                    }
                }
            }
            return;
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                Object c = content.get(i);
                if (c instanceof JSONObject) {
                    walkSections((JSONObject) c, acc);
                }
            }
        }
    }

    /** 字段收集(递归): emrField 命中则取值入表(重复字段首值优先); 遇 emrSection 交回章节登记(作为独立章节) */
    private void collectFieldsFrom(JSONObject node, Map<String, String> fields, Map<String, Map<String, String>> acc) {
        if (node == null) {
            return;
        }
        String type = str(node.get("type"));
        if ("emrSection".equalsIgnoreCase(type)) {
            walkSections(node, acc);
            return;
        }
        if ("emrField".equalsIgnoreCase(type)) {
            if (fields != null) {
                JSONObject attrs = node.getJSONObject("attrs");
                String key = attrs == null ? null : str(attrs.get("fieldKey"));
                String text = fieldValueText(attrs);
                if (StringUtils.hasText(key) && text != null && !fields.containsKey(key.trim())) {
                    fields.put(key.trim(), text);
                }
            }
            return;
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                Object c = content.get(i);
                if (c instanceof JSONObject) {
                    collectFieldsFrom((JSONObject) c, fields, acc);
                }
            }
        }
    }

    /** emrField 取值: 跳过 valueType=signature(与 EmrDocumentService 口径一致); value 空不产出; 数组逐项取值 */
    private static String fieldValueText(JSONObject attrs) {
        if (attrs == null) {
            return null;
        }
        String valueType = str(attrs.get("valueType"));
        if ("signature".equalsIgnoreCase(valueType)) {
            return null;
        }
        return valueText(attrs.get("value"));
    }

    /* ================= 内部实现: 辅助 ================= */

    /**
     * recordType → 适用范围 scope: "2"/门诊/OUTP → 2; "1"/住院/INP → 1; 数值 2→2, 其余(住院文书类型1..9) → 1;
     * 空/"0"/"全部"/ALL → null(不过滤); 无法识别 → 1(按住院口径)。
     */
    private static Integer resolveScope(String recordType) {
        if (!StringUtils.hasText(recordType)) {
            return null;
        }
        String rt = recordType.trim().toUpperCase(Locale.ROOT);
        if ("0".equals(rt) || "全部".equals(rt) || "ALL".equals(rt)) {
            return null;
        }
        if ("2".equals(rt) || "门诊".equals(rt) || "OUTP".equals(rt) || "OUTPATIENT".equals(rt)) {
            return 2;
        }
        if ("1".equals(rt) || "住院".equals(rt) || "INP".equals(rt) || "INPATIENT".equals(rt)) {
            return 1;
        }
        try {
            return Integer.parseInt(rt) == 2 ? 2 : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String requireText(String v, String msg) {
        if (!StringUtils.hasText(v)) {
            throw new BizException(400, msg);
        }
        return v.trim();
    }

    private static String trimToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
