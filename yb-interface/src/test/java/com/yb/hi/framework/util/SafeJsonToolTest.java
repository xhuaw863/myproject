package com.yb.hi.framework.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.yb.hi.framework.config.JacksonConfig;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SafeJsonTool(嵌套 JSON 字符串安全序列化, T57)最小验证。
 *
 * 走与 JacksonConfigTest 一致的装配路径: Jackson2ObjectMapperBuilder 收集 JacksonConfig 的 customizer
 * 后 build, 与 Spring Boot JacksonAutoConfiguration 内部一致; 不启动 Spring 上下文, 不依赖数据库。
 * 覆盖面: 生成端 toJson(嵌套雪花 Long ruleId 带引号, 评分/扣分/状态保持数字) /
 * 存量兼容 normalizeEmbeddedJson(裸数字 ID 补引号、尾零保留、幂等、非法 JSON 容错) /
 * 前端 JSON.parse 视角逐字符一致(模拟 Node 字符串比较)。
 * T61 补充: 顶层对象与深层嵌套数组的裸大整数转换、2^53 边界值、已字符串化 ID 文本稳定。
 */
class SafeJsonToolTest {

    /** 与全局一致的对象映射器(同 JacksonConfigTest 的构建路径) */
    private static ObjectMapper buildObjectMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new JacksonConfig().longToStringCustomizer().customize(builder);
        return builder.build();
    }

    private static final ObjectMapper OM = buildObjectMapper();
    private static final SafeJsonTool TOOL = new SafeJsonTool(OM);

    /** 模拟病历质控 evaluateQuality 的 details 结构: ruleId 为 19 位雪花 Long, 业务数值为 Integer/BigDecimal */
    private static List<Map<String, Object>> sampleDetails() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ruleId", 2104508685810741249L);
        d.put("ruleCode", "ADMIT_TIME_24H");
        d.put("ruleName", "入院记录24小时内完成");
        d.put("ruleType", 2);
        d.put("severity", 2);
        d.put("passed", false);
        d.put("deductScore", new BigDecimal("20.0"));
        d.put("message", "【扣分】入院记录须在患者入院后24小时内完成");
        return Collections.singletonList(d);
    }

    /** 旧格式 quality_detail: ruleId 为裸数字(fastjson 历史产物) */
    private static final String LEGACY_JSON =
            "[{\"ruleId\":2104508685810741249,\"ruleCode\":\"ADMIT_TIME_24H\",\"ruleName\":\"入院记录24小时内完成\","
                    + "\"ruleType\":2,\"severity\":2,\"passed\":false,\"deductScore\":20.0,"
                    + "\"message\":\"【扣分】入院记录须在患者入院后24小时内完成\"}]";

    @Test
    @DisplayName("生成端 toJson: 嵌套 19 位雪花 ruleId 带引号, 评分/扣分/状态保持数字")
    void toJsonQuotesSnowflakeIdAndKeepsNumbers() throws Exception {
        String json = TOOL.toJson(sampleDetails());
        JsonNode d = OM.readTree(json).get(0);
        assertNotNull(d);
        assertTrue(d.get("ruleId").isTextual(), "ruleId 应为字符串节点");
        assertEquals("2104508685810741249", d.get("ruleId").asText());
        assertTrue(d.get("ruleType").isInt(), "ruleType 应保持整数");
        assertTrue(d.get("severity").isInt(), "severity 应保持整数");
        assertTrue(d.get("deductScore").isNumber(), "deductScore 应保持数字");
        assertEquals(new BigDecimal("20.0"), d.get("deductScore").decimalValue());
        assertFalse(d.get("passed").asBoolean(), "passed 应保持布尔");
        assertEquals("ADMIT_TIME_24H", d.get("ruleCode").asText());
    }

    @Test
    @DisplayName("存量兼容 normalizeEmbeddedJson: 裸数字 ruleId 补引号, 数值与尾零保持")
    void normalizeQuotesLegacyBareId() throws Exception {
        String fixed = TOOL.normalizeEmbeddedJson(LEGACY_JSON);
        JsonNode d = OM.readTree(fixed).get(0);
        assertNotNull(d);
        assertTrue(d.get("ruleId").isTextual(), "存量裸数字 ruleId 应补引号");
        assertEquals("2104508685810741249", d.get("ruleId").asText());
        assertTrue(d.get("ruleType").isInt());
        assertTrue(d.get("severity").isInt());
        assertTrue(d.get("deductScore").isNumber(), "扣分应保持数字");
        assertEquals(new BigDecimal("20.0"), d.get("deductScore").decimalValue(), "浮点尾零不应丢失");
        assertEquals("ADMIT_TIME_24H", d.get("ruleCode").asText());
    }

    @Test
    @DisplayName("前端 JSON.parse 视角: 解析后 ruleId 逐字符一致(模拟 JS 字符串比较)")
    void parsedRuleIdMatchesCharByChar() throws Exception {
        String fixed = TOOL.normalizeEmbeddedJson(LEGACY_JSON);
        String original = "2104508685810741249";
        String parsed = OM.readTree(fixed).get(0).get("ruleId").asText();
        assertEquals(original.length(), parsed.length(), "长度应一致");
        for (int i = 0; i < original.length(); i++) {
            assertEquals(original.charAt(i), parsed.charAt(i), "第 " + i + " 位字符不一致");
        }
    }

    @Test
    @DisplayName("normalizeEmbeddedJson 幂等: 已修复 JSON 再处理结果不变")
    void normalizeIsIdempotent() {
        String fixed = TOOL.normalizeEmbeddedJson(LEGACY_JSON);
        assertEquals(fixed, TOOL.normalizeEmbeddedJson(fixed));
    }

    @Test
    @DisplayName("normalizeEmbeddedJson 容错: 空白/非法 JSON 原样返回, null 返回 null")
    void normalizeTolerant() {
        assertEquals("not-json", TOOL.normalizeEmbeddedJson("not-json"));
        assertEquals("", TOOL.normalizeEmbeddedJson(""));
        assertEquals("   ", TOOL.normalizeEmbeddedJson("   "));
        assertNull(TOOL.normalizeEmbeddedJson(null));
    }

    @Test
    @DisplayName("已是字符串的 ruleId 不受影响(不重复加引号)")
    void stringNumbersUntouched() {
        String json = "[{\"ruleId\":\"2104508685810741249\"}]";
        assertEquals(json, TOOL.normalizeEmbeddedJson(json));
    }

    /** T59 任务指定的原始存量格式(含空格缩进): 19 位裸数字 ruleId + 业务值 */
    private static final String T59_RAW =
            "[ {\"ruleId\":2104508685743632386,\"ruleType\":1,\"deductScore\":2.5,\"passed\":false} ]";

    @Test
    @DisplayName("T59 指定原始串: 19位裸数字 ruleId 补引号逐字符一致, 业务值类型不变, 二次 normalize 语义一致")
    void normalizeTask59RawSample() throws Exception {
        String fixed = TOOL.normalizeEmbeddedJson(T59_RAW);
        JsonNode d = OM.readTree(fixed).get(0);
        assertNotNull(d);
        /* ruleId: 带引号且与原始十进制文本逐字符一致 */
        assertTrue(d.get("ruleId").isTextual(), "ruleId 应带引号");
        String expected = "2104508685743632386";
        String id = d.get("ruleId").asText();
        assertEquals(expected.length(), id.length(), "长度应一致");
        for (int i = 0; i < expected.length(); i++) {
            assertEquals(expected.charAt(i), id.charAt(i), "第 " + i + " 位字符不一致");
        }
        /* 文本层面: 不存在裸数字 ruleId, 业务值未被字符串化 */
        assertFalse(fixed.contains("\"ruleId\":2"), "输出不应出现裸数字 ruleId");
        assertFalse(fixed.contains("\"ruleType\":\""), "ruleType 不应被字符串化");
        /* 业务值类型保持 */
        assertTrue(d.get("ruleType").isInt(), "ruleType 应为 number(int)");
        assertEquals(1, d.get("ruleType").asInt());
        assertTrue(d.get("deductScore").isNumber(), "deductScore 应为 number");
        assertEquals(new BigDecimal("2.5"), d.get("deductScore").decimalValue());
        assertTrue(d.get("passed").isBoolean(), "passed 应为 boolean");
        assertFalse(d.get("passed").asBoolean());
        /* 二次 normalize 语义与文本均稳定 */
        String twice = TOOL.normalizeEmbeddedJson(fixed);
        assertEquals(OM.readTree(fixed), OM.readTree(twice), "二次 normalize 语义应一致");
        assertEquals(fixed, twice, "二次 normalize 文本应稳定");
    }

    @Test
    @DisplayName("T59 安全区间整数保持 number: 50亿/负数/int/0 均不被字符串化")
    void safeRangeIntegersStayNumbers() throws Exception {
        String raw = "[{\"amountFen\":5000000000,\"count\":42,\"zero\":0,\"neg\":-5000000000}]";
        String fixed = TOOL.normalizeEmbeddedJson(raw);
        JsonNode d = OM.readTree(fixed).get(0);
        assertTrue(d.get("amountFen").isNumber(), "50亿(<=2^53-1)应保持 number");
        assertEquals(5000000000L, d.get("amountFen").asLong());
        assertTrue(d.get("count").isInt(), "int 值应保持 int");
        assertTrue(d.get("zero").isInt(), "0 应保持 int");
        assertTrue(d.get("neg").isNumber(), "负 50亿应保持 number");
        assertEquals(-5000000000L, d.get("neg").asLong());
        assertFalse(fixed.contains("\"amountFen\":\""), "业务大整数不应被字符串化");
    }

    @Test
    @DisplayName("T59 超 long 范围整数(21位): 转十进制字符串且逐字符一致")
    void beyondLongIntegerBecomesString() throws Exception {
        String raw = "[{\"bigId\":184467440737095516150}]";
        String fixed = TOOL.normalizeEmbeddedJson(raw);
        JsonNode d = OM.readTree(fixed).get(0);
        assertTrue(d.get("bigId").isTextual(), "超 JS 安全上界整数应转字符串");
        assertEquals("184467440737095516150", d.get("bigId").asText());
    }

    /* ---------- T61 补充: 顶层对象 / 嵌套数组 / 2^53 边界 / 已字符串化 ---------- */

    @Test
    @DisplayName("T61 顶层对象: 裸大整数 ruleId 转字符串, 小整数 score 保持数字")
    void t61TopLevelObjectBareBigInt() throws Exception {
        String fixed = TOOL.normalizeEmbeddedJson("{\"ruleId\":2104508685743632386,\"score\":85}");
        JsonNode root = OM.readTree(fixed);
        assertTrue(root.get("ruleId").isTextual(), "ruleId 应转为字符串节点");
        assertEquals("2104508685743632386", root.get("ruleId").asText());
        assertTrue(root.get("score").isInt(), "score 应保持数字");
        assertEquals(85, root.get("score").asInt());
        assertFalse(fixed.contains("\"ruleId\":2"), "输出不应残留裸数字 ruleId");
        assertFalse(fixed.contains("\"score\":\""), "score 不应被字符串化");
    }

    /** T61 验收标准原串: 对象内嵌数组, 数组内对象携带裸大整数 */
    private static final String T61_NESTED =
            "{\"rules\":[{\"ruleId\":2104508685743632386,\"name\":\"test\"}]}";

    @Test
    @DisplayName("T61 嵌套对象/数组: rules[].ruleId 转字符串, 精确输出符合验收标准")
    void t61NestedObjectInArray() throws Exception {
        String fixed = TOOL.normalizeEmbeddedJson(T61_NESTED);
        assertEquals("{\"rules\":[{\"ruleId\":\"2104508685743632386\",\"name\":\"test\"}]}", fixed,
                "输出应与验收标准文本一致");
        JsonNode rule = OM.readTree(fixed).path("rules").get(0);
        assertTrue(rule.get("ruleId").isTextual(), "嵌套数组内 ruleId 应转为字符串");
        assertEquals("2104508685743632386", rule.get("ruleId").asText());
        assertEquals("test", rule.get("name").asText());
    }

    @Test
    @DisplayName("T61 深层嵌套与 2^53 边界: 2^53-1 保持数字, 2^53 转字符串")
    void t61DeepNestingAndSafeBoundary() throws Exception {
        String raw = "{\"a\":{\"b\":[{\"edgeSafe\":9007199254740991,\"edgeOver\":9007199254740992}]}}";
        String fixed = TOOL.normalizeEmbeddedJson(raw);
        JsonNode node = OM.readTree(fixed).path("a").path("b").get(0);
        assertTrue(node.get("edgeSafe").isNumber(), "2^53-1 应保持数字");
        assertEquals(9007199254740991L, node.get("edgeSafe").asLong());
        assertTrue(node.get("edgeOver").isTextual(), "2^53 应转字符串");
        assertEquals("9007199254740992", node.get("edgeOver").asText());
    }

    @Test
    @DisplayName("T61 已字符串化的大整数不受影响: 对象与嵌套数组内均原样, 文本稳定")
    void t61StringifiedBigIntsUntouched() {
        String raw = "{\"ruleId\":\"2104508685743632386\",\"rules\":[{\"ruleId\":\"184467440737095516150\"}]}";
        assertEquals(raw, TOOL.normalizeEmbeddedJson(raw), "已字符串化大整数应原样保留");
    }
}
