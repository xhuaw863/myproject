package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisCdssRule;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.inpatient.CdssRuleMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CDSS 临床决策支持引擎: 规则维护 + 条件评估。
 *
 * 规则模型(his_cdss_rule): condition_expr 为条件JSON, 命中后按 severity 产出分级告警。
 * 条件表达:
 * - 单条件 {@code {"field":"chiefComplaint","op":"lenGt","value":"20"}};
 * - 组合 {@code {"and":[...]}} / {@code {"or":[...]}}(可嵌套, 空组合视为无效不命中);
 * - 运算符: eq/ne(数值对数值比较, 否则文本等值, 忽略大小写回退)、gt/lt/gte/lte(数值比较, 非数值按字符串字典序,
 *   适配 ISO 日期串)、contains(文本包含)、empty/notEmpty(空值判定)、in(枚举命中),
 *   另扩展 lenGt/lenLt(文本长度比较, 支撑"主诉>20字"类质控)。
 * 输出告警: {@code [{ruleId, ruleCode, ruleName, ruleType, level, message, knowledgeSource}]},
 * 按 block > warning > info 稳定排序(同级保持 sort_no→id 序); message 对 action_message 的
 * {fieldKey} 占位符以命中字段值插值(未知占位符保留原样)。
 *
 * 约定:
 * - 规则为全医共体共享主数据: 读仅租户隔离不按机构过滤(同 his_emr_template/数据集口径);
 *   写操作(保存/删除)仅牵头机构管理员({@link OrgAccessGuard#requireLeadOrg}), 新建行绑定当前机构;
 * - 科室适用性: applicable_depts 为空(null/空白/[])=全院适用; 非空须包含 deptCode(字符串或数值等值);
 *   评估未传 deptCode 时仅全院规则生效;
 * - evaluate 的 recordType 作为上下文注入评估字段(键 recordType), 供条件按文书类型区分, 不参与规则加载过滤;
 * - 单条规则条件解析/评估失败仅告警跳过, 不阻断整体评估(患者安全优先, 规则异常不拦截业务)。
 */
@Slf4j
@Service
public class CdssEngineService {

    /** 严重程度合法值 */
    private static final String SEV_BLOCK = "block";
    private static final String SEV_WARNING = "warning";
    private static final String SEV_INFO = "info";

    /** action_message 占位符: {fieldKey}(字母/数字/下划线/短横线/点) */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_\\-.]+)}");
    /** 消息插值单值截断长度 */
    private static final int MSG_VALUE_MAX = 60;

    private final CdssRuleMapper ruleMapper;
    private final OrgAccessGuard guard;

    public CdssEngineService(CdssRuleMapper ruleMapper, OrgAccessGuard guard) {
        this.ruleMapper = ruleMapper;
        this.guard = guard;
    }

    /* ================= 规则维护 ================= */

    /**
     * 规则列表: ruleType 可选精确过滤; deptCode 非空时按适用科室过滤(全局规则 + applicable_depts 含该科室的规则),
     * deptCode 为空返回全部。列表含停用规则(enabled 字段区分)供管理端停用/启用, 评估引擎仅取 enabled=1。
     * 读操作仅受租户插件隔离(规则为租户共享主数据)。
     */
    public R<List<HisCdssRule>> listRules(String ruleType, String deptCode) {
        LambdaQueryWrapper<HisCdssRule> qw = Wrappers.<HisCdssRule>lambdaQuery()
                .eq(StringUtils.hasText(ruleType), HisCdssRule::getRuleType,
                        ruleType == null ? null : ruleType.trim())
                .orderByAsc(HisCdssRule::getSortNo).orderByAsc(HisCdssRule::getId);
        List<HisCdssRule> rows = ruleMapper.selectList(qw);
        if (StringUtils.hasText(deptCode)) {
            List<HisCdssRule> filtered = new ArrayList<>();
            for (HisCdssRule r : rows) {
                if (isDeptApplicable(r, deptCode)) {
                    filtered.add(r);
                }
            }
            rows = filtered;
        }
        return R.ok(rows);
    }

    /** 规则详情 */
    public R<HisCdssRule> getRule(Long id) {
        HisCdssRule r = id == null ? null : ruleMapper.selectById(id);
        if (r == null) {
            throw new BizException(400, "CDSS规则不存在");
        }
        return R.ok(r);
    }

    /**
     * 新建/更新规则(id 空=新建并绑定当前机构; 仅牵头机构管理员)。
     * 校验: 名称/类型/条件表达式必填; 条件须为合法JSON且含 field/op 或 and/or; severity 仅 info/warning/block;
     * rule_code 非空时租户内唯一(应用层预检); applicable_depts 非空时须为JSON数组。
     */
    public R<HisCdssRule> saveRule(HisCdssRule in) {
        if (in == null) {
            throw new BizException(400, "规则内容不能为空");
        }
        guard.requireLeadOrg("仅牵头机构管理员可维护CDSS规则");
        if (!StringUtils.hasText(in.getName())) {
            throw new BizException(400, "规则名称不能为空");
        }
        if (!StringUtils.hasText(in.getRuleType())) {
            throw new BizException(400, "规则类型不能为空");
        }
        if (!StringUtils.hasText(in.getConditionExpr())) {
            throw new BizException(400, "条件表达式不能为空");
        }
        parseCondition(in.getConditionExpr());
        String severity = null;
        if (StringUtils.hasText(in.getSeverity())) {
            severity = in.getSeverity().trim().toLowerCase();
            if (!SEV_INFO.equals(severity) && !SEV_WARNING.equals(severity) && !SEV_BLOCK.equals(severity)) {
                throw new BizException(400, "严重程度仅支持 info/warning/block");
            }
        }
        String ruleCode = StringUtils.hasText(in.getRuleCode()) ? in.getRuleCode().trim() : null;
        if (StringUtils.hasText(in.getApplicableDepts())) {
            validateDeptList(in.getApplicableDepts());
        }
        if (in.getId() == null) {
            ensureCodeAvailable(ruleCode, null);
            HisCdssRule r = new HisCdssRule();
            r.setOrgId(guard.currentOrgId());
            r.setRuleCode(ruleCode);
            r.setName(in.getName().trim());
            r.setRuleType(in.getRuleType().trim());
            r.setConditionExpr(in.getConditionExpr().trim());
            r.setActionMessage(blankToNull(in.getActionMessage()));
            r.setSeverity(severity != null ? severity : SEV_INFO);
            r.setKnowledgeSource(blankToNull(in.getKnowledgeSource()));
            r.setApplicableDepts(blankToNull(in.getApplicableDepts()));
            r.setEnabled(in.getEnabled() != null ? in.getEnabled() : 1);
            r.setSortNo(in.getSortNo() != null ? in.getSortNo() : 0);
            ruleMapper.insert(r);
            log.info("新建CDSS规则: id={}, code={}, name={}, severity={}", r.getId(), r.getRuleCode(), r.getName(), r.getSeverity());
            return R.ok(ruleMapper.selectById(r.getId()));
        }
        HisCdssRule exist = ruleMapper.selectById(in.getId());
        if (exist == null) {
            throw new BizException(400, "CDSS规则不存在");
        }
        if (ruleCode != null && !ruleCode.equals(exist.getRuleCode())) {
            ensureCodeAvailable(ruleCode, exist.getId());
            exist.setRuleCode(ruleCode);
        }
        exist.setName(in.getName().trim());
        exist.setRuleType(in.getRuleType().trim());
        exist.setConditionExpr(in.getConditionExpr().trim());
        if (in.getActionMessage() != null) {
            exist.setActionMessage(blankToNull(in.getActionMessage()));
        }
        if (severity != null) {
            exist.setSeverity(severity);
        }
        if (in.getKnowledgeSource() != null) {
            exist.setKnowledgeSource(blankToNull(in.getKnowledgeSource()));
        }
        if (in.getApplicableDepts() != null) {
            exist.setApplicableDepts(blankToNull(in.getApplicableDepts()));
        }
        if (in.getEnabled() != null) {
            exist.setEnabled(in.getEnabled());
        }
        if (in.getSortNo() != null) {
            exist.setSortNo(in.getSortNo());
        }
        ruleMapper.updateById(exist);
        log.info("更新CDSS规则: id={}, code={}, name={}", exist.getId(), exist.getRuleCode(), exist.getName());
        return R.ok(ruleMapper.selectById(exist.getId()));
    }

    /** 删除规则(逻辑删除; 仅牵头机构管理员) */
    public R<Void> deleteRule(Long id) {
        guard.requireLeadOrg("仅牵头机构管理员可维护CDSS规则");
        HisCdssRule exist = id == null ? null : ruleMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "CDSS规则不存在");
        }
        ruleMapper.deleteById(id);
        log.info("删除CDSS规则: id={}, code={}", id, exist.getRuleCode());
        return R.ok();
    }

    /* ================= 评估引擎 ================= */

    /**
     * 条件评估(核心): 取当前科室可用的启用规则, 逐条解析 condition_expr 对照 fieldValues 求值,
     * 命中产出告警并按 block > warning > info 排序返回。
     * recordType 非空时作为上下文注入评估字段(键 recordType); fieldValues 为空按空值评估(empty 类条件可命中)。
     */
    public R<List<Map<String, Object>>> evaluate(String recordType, Map<String, Object> fieldValues, String deptCode) {
        List<HisCdssRule> rules = loadActiveRules(deptCode);
        Map<String, Object> fields = fieldValues == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fieldValues);
        if (StringUtils.hasText(recordType)) {
            fields.putIfAbsent("recordType", recordType.trim());
        }
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (HisCdssRule r : rules) {
            try {
                JSONObject cond = JSON.parseObject(r.getConditionExpr());
                if (cond != null && matchExpr(cond, fields)) {
                    alerts.add(buildAlert(r, fields));
                }
            } catch (Exception e) {
                log.warn("CDSS规则[{}]评估失败, 跳过: {}", ref(r), e.getMessage());
            }
        }
        // 稳定排序: block > warning > info(同级保持 sort_no→id 序)
        alerts.sort((a, b) -> severityRank(String.valueOf(a.get("level"))) - severityRank(String.valueOf(b.get("level"))));
        if (!alerts.isEmpty()) {
            log.info("CDSS评估命中: recordType={}, dept={}, 命中{}/{}条规则", recordType, deptCode, alerts.size(), rules.size());
        }
        return R.ok(alerts);
    }

    /**
     * 病历文档评估: 静态抽取 Tiptap JSON 中全部 emrField 要素(与数据元落库同口径), 按 fieldKey 聚合
     * (重复键以"、"连接, 便于 contains/长度判断; 相同值去重)后走 {@link #evaluate}。
     * 解析失败/无字段产出按空字段评估(不抛异常, 与 parseTiptapFields 口径一致)。
     */
    public R<List<Map<String, Object>>> evaluateDocument(String tiptapJson, String recordType, String deptCode) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (EmrDocumentService.EmrFieldValue f : EmrDocumentService.parseTiptapFields(tiptapJson)) {
            if (!StringUtils.hasText(f.getFieldKey()) || !StringUtils.hasText(f.getValueText())) {
                continue;
            }
            Object prev = fields.get(f.getFieldKey());
            if (prev == null) {
                fields.put(f.getFieldKey(), f.getValueText());
            } else {
                String merged = String.valueOf(prev);
                if (!merged.contains(f.getValueText())) {
                    fields.put(f.getFieldKey(), merged + "、" + f.getValueText());
                }
            }
        }
        return evaluate(recordType, fields, deptCode);
    }

    /* ================= 条件求值实现 ================= */

    /** 加载科室可用启用规则: enabled=1(租户隔离, sort_no→id 序) + applicable_depts 适用性过滤 */
    private List<HisCdssRule> loadActiveRules(String deptCode) {
        List<HisCdssRule> all = ruleMapper.selectList(Wrappers.<HisCdssRule>lambdaQuery()
                .eq(HisCdssRule::getEnabled, 1)
                .orderByAsc(HisCdssRule::getSortNo).orderByAsc(HisCdssRule::getId));
        List<HisCdssRule> out = new ArrayList<>();
        for (HisCdssRule r : all) {
            if (isDeptApplicable(r, deptCode)) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * 科室适用性: applicable_depts 为空(null/空白/[]/"null")=全院适用;
     * 否则须包含 deptCode(字符串等值或数值等值); deptCode 为空时仅全院规则适用。
     * 列表JSON解析失败按全院适用处理并告警(宁可多提示, 不静默失效)。
     */
    private boolean isDeptApplicable(HisCdssRule r, String deptCode) {
        String json = r.getApplicableDepts();
        if (!StringUtils.hasText(json) || "null".equalsIgnoreCase(json.trim())) {
            return true;
        }
        JSONArray arr;
        try {
            arr = JSON.parseArray(json.trim());
        } catch (Exception e) {
            log.warn("CDSS规则[{}] applicable_depts 解析失败, 按全院适用处理: {}", ref(r), e.getMessage());
            return true;
        }
        if (arr == null || arr.isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(deptCode)) {
            return false;
        }
        String target = deptCode.trim();
        for (int i = 0; i < arr.size(); i++) {
            String el = text(arr.get(i));
            if (el == null) {
                continue;
            }
            if (el.equals(target) || el.equalsIgnoreCase(target)) {
                return true;
            }
            BigDecimal a = toNum(el);
            BigDecimal b = toNum(target);
            if (a != null && b != null && a.compareTo(b) == 0) {
                return true;
            }
        }
        return false;
    }

    /** 条件求值: {and:[...]} 全部命中 / {or:[...]} 任一命中 / 单条 {field,op,value}; 非法节点不命中 */
    private boolean matchExpr(Object exprObj, Map<String, Object> fields) {
        if (!(exprObj instanceof Map)) {
            return false;
        }
        Map<?, ?> m = (Map<?, ?>) exprObj;
        Object andNode = m.get("and");
        if (andNode != null) {
            List<?> children = asList(andNode);
            if (children == null || children.isEmpty()) {
                return false;
            }
            for (Object c : children) {
                if (!matchExpr(c, fields)) {
                    return false;
                }
            }
            return true;
        }
        Object orNode = m.get("or");
        if (orNode != null) {
            List<?> children = asList(orNode);
            if (children == null || children.isEmpty()) {
                return false;
            }
            for (Object c : children) {
                if (matchExpr(c, fields)) {
                    return true;
                }
            }
            return false;
        }
        String field = text(m.get("field"));
        String opRaw = text(m.get("op"));
        String op = opRaw == null ? "" : opRaw.toLowerCase();
        if (!StringUtils.hasText(field) || op.isEmpty()) {
            return false;
        }
        return matchCondition(field, op, m.get("value"), fields.get(field));
    }

    /** 单条件求值(运算符口径见类注释) */
    private boolean matchCondition(String field, String op, Object expected, Object actual) {
        switch (op) {
            case "eq":
                return valueEquals(actual, expected);
            case "ne":
                return !valueEquals(actual, expected);
            case "gt":
                return actual != null && expected != null && compareValues(actual, expected) > 0;
            case "lt":
                return actual != null && expected != null && compareValues(actual, expected) < 0;
            case "gte":
                return actual != null && expected != null && compareValues(actual, expected) >= 0;
            case "lte":
                return actual != null && expected != null && compareValues(actual, expected) <= 0;
            case "contains": {
                if (actual == null || expected == null) {
                    return false;
                }
                String needle = text(expected);
                String hay = text(actual);
                return StringUtils.hasText(needle) && hay != null && hay.contains(needle);
            }
            case "empty":
                return isEmptyValue(actual);
            case "notempty":
                return !isEmptyValue(actual);
            case "in": {
                List<?> opts = asList(expected);
                if (opts == null || opts.isEmpty()) {
                    return false;
                }
                for (Object o : opts) {
                    if (valueEquals(actual, o)) {
                        return true;
                    }
                }
                return false;
            }
            // 扩展运算符: 文本长度比较(如主诉>20字)
            case "lengt":
                return actual != null && compareLength(actual, expected) > 0;
            case "lenlt":
                return actual != null && compareLength(actual, expected) < 0;
            default:
                log.warn("CDSS条件运算符不支持: {} (field={})", op, field);
                return false;
        }
    }

    /** 等值: 实际值为集合时任一元素命中; 双侧可解析为数值按数值比较, 否则文本等值(忽略大小写回退) */
    private boolean valueEquals(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual instanceof Collection) {
            for (Object o : (Collection<?>) actual) {
                if (valueEquals(o, expected)) {
                    return true;
                }
            }
            return false;
        }
        String a = text(actual);
        String b = text(expected);
        if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
            return false;
        }
        BigDecimal na = toNum(a);
        BigDecimal nb = toNum(b);
        if (na != null && nb != null) {
            return na.compareTo(nb) == 0;
        }
        return a.equals(b) || a.equalsIgnoreCase(b);
    }

    /** 大小比较: 双侧数值可解析按数值比较, 否则字符串字典序(适配 ISO 日期串) */
    private int compareValues(Object a, Object b) {
        BigDecimal na = toNum(a);
        BigDecimal nb = toNum(b);
        if (na != null && nb != null) {
            return na.compareTo(nb);
        }
        String sa = text(a);
        String sb = text(b);
        return sa == null ? (sb == null ? 0 : -1) : (sb == null ? 1 : sa.compareTo(sb));
    }

    /** 文本长度与期望数值比较; 期望值非法返回 0(既不命中 lenGt 也不命中 lenLt) */
    private int compareLength(Object actual, Object expected) {
        BigDecimal n = toNum(expected);
        if (n == null) {
            return 0;
        }
        return BigDecimal.valueOf(text(actual).length()).compareTo(n);
    }

    /** 空值判定: null/"null"/空白/空集合/空对象/空数组串 视为空 */
    private boolean isEmptyValue(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Collection) {
            return ((Collection<?>) v).isEmpty();
        }
        if (v instanceof Map) {
            return ((Map<?, ?>) v).isEmpty();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) || "[]".equals(s);
    }

    /* ================= 告警构建 ================= */

    /** 命中 → 告警行: ruleCode 空时回落规则名称, message 按 {fieldKey} 插值(未知占位符保留原样) */
    private Map<String, Object> buildAlert(HisCdssRule r, Map<String, Object> fields) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("ruleId", r.getId());
        alert.put("ruleCode", StringUtils.hasText(r.getRuleCode()) ? r.getRuleCode() : r.getName());
        alert.put("ruleName", r.getName());
        alert.put("ruleType", r.getRuleType());
        alert.put("level", normalizeSeverity(r.getSeverity()));
        alert.put("message", renderMessage(r.getActionMessage(), fields));
        alert.put("knowledgeSource", r.getKnowledgeSource());
        return alert;
    }

    /** 消息模板插值: {fieldKey} → 字段值(单值截断60字), 字段缺省时保留占位符原样便于排查 */
    private static String renderMessage(String tpl, Map<String, Object> fields) {
        if (!StringUtils.hasText(tpl)) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(tpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object v = fields == null ? null : fields.get(key);
            String rep;
            if (v == null) {
                rep = m.group(0);
            } else {
                String s = text(v);
                if (s == null) {
                    s = "";
                }
                if (s.length() > MSG_VALUE_MAX) {
                    s = s.substring(0, MSG_VALUE_MAX) + "…";
                }
                rep = s;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /* ================= 校验与工具 ================= */

    /** 条件JSON解析与结构预检(保存时强校验; 评估时按跳过容错) */
    private JSONObject parseCondition(String expr) {
        JSONObject obj;
        try {
            obj = JSON.parseObject(expr.trim());
        } catch (Exception e) {
            throw new BizException(400, "条件表达式JSON解析失败: " + e.getMessage());
        }
        if (obj == null || !(obj.containsKey("field") || obj.containsKey("and") || obj.containsKey("or"))) {
            throw new BizException(400, "条件表达式须为 {field,op,value} 或 {and:[...]}/{or:[...]} 结构");
        }
        return obj;
    }

    /** 适用科室列表JSON校验(须为数组; 元素可为ID数值或编码字符串) */
    private void validateDeptList(String depts) {
        try {
            JSONArray arr = JSON.parseArray(depts.trim());
            if (arr == null) {
                throw new BizException(400, "适用科室须为JSON数组, 如 [\"1001\"]");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "适用科室须为JSON数组, 如 [\"1001\"]");
        }
    }

    /** 规则编码租户内查重(非空才校验; 逻辑删除行不参与) */
    private void ensureCodeAvailable(String ruleCode, Long excludeId) {
        if (!StringUtils.hasText(ruleCode)) {
            return;
        }
        boolean dup = !ruleMapper.selectList(Wrappers.<HisCdssRule>lambdaQuery()
                .eq(HisCdssRule::getRuleCode, ruleCode)
                .ne(excludeId != null, HisCdssRule::getId, excludeId)).isEmpty();
        if (dup) {
            throw new BizException(400, "规则编码已存在: " + ruleCode);
        }
    }

    private static String normalizeSeverity(String severity) {
        if (SEV_BLOCK.equalsIgnoreCase(severity)) {
            return SEV_BLOCK;
        }
        if (SEV_WARNING.equalsIgnoreCase(severity)) {
            return SEV_WARNING;
        }
        return SEV_INFO;
    }

    /** 告警排序权重: block 0 < warning 1 < info 2(升序即严重优先) */
    private static int severityRank(String level) {
        if (SEV_BLOCK.equals(level)) {
            return 0;
        }
        if (SEV_WARNING.equals(level)) {
            return 1;
        }
        return 2;
    }

    /** 规则引用标识(日志用): 编码优先, 回落名称/ID */
    private static String ref(HisCdssRule r) {
        if (StringUtils.hasText(r.getRuleCode())) {
            return r.getRuleCode();
        }
        return StringUtils.hasText(r.getName()) ? r.getName() : String.valueOf(r.getId());
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    /** 值 → 文本(集合按"、"连接; 对象取JSON串; null 保留 null) */
    private static String text(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Collection) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (Collection<?>) v) {
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append(String.valueOf(o).trim());
            }
            return sb.toString();
        }
        if (v instanceof Map) {
            return JSON.toJSONString(v);
        }
        return String.valueOf(v).trim();
    }

    /** 数值解析(Number / 纯数字串), 失败返回 null */
    private static BigDecimal toNum(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return new BigDecimal(v.toString());
        }
        String s = text(v);
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 值 → 列表: 集合/数组直接取, 字符串按 JSON 数组或逗号分隔解析, 其余 null */
    private static List<?> asList(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof List) {
            return (List<?>) v;
        }
        if (v instanceof Collection) {
            return new ArrayList<>((Collection<?>) v);
        }
        if (v instanceof Object[]) {
            return Arrays.asList((Object[]) v);
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) {
                return null;
            }
            if (s.startsWith("[")) {
                try {
                    JSONArray arr = JSON.parseArray(s);
                    return arr == null ? null : new ArrayList<>(arr);
                } catch (Exception e) {
                    return null;
                }
            }
            List<String> parts = new ArrayList<>();
            for (String p : s.split(",")) {
                if (StringUtils.hasText(p)) {
                    parts.add(p.trim());
                }
            }
            return parts;
        }
        return null;
    }
}
