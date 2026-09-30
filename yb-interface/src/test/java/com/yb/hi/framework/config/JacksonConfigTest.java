package com.yb.hi.framework.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JacksonConfig(Long 序列化统一治理)最小验证
 *
 * 走 Spring Boot 真实装配路径: Jackson2ObjectMapperBuilder 收集 JacksonConfig 的 customizer 后 build,
 * 与 JacksonAutoConfiguration 内部装配方式一致; 不启动 Spring 上下文, 不依赖数据库。
 * 覆盖面: 19位Long与primitive long转字符串 / BigDecimal、Integer、状态字段保持数字 /
 * 字符串数字反序列化绑定 Long 与 List<Long> / JavaTimeModule 不受影响。
 */
class JacksonConfigTest {

    /** 与 Spring Boot JacksonAutoConfiguration 一致的构建路径(builder 收全量 customizer 后 build) */
    private static ObjectMapper buildObjectMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        // Spring Boot 默认禁用时间戳输出(与本配置无关, 仅为还原真实装配环境)
        builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new JacksonConfig().longToStringCustomizer().customize(builder);
        return builder.build();
    }

    private static final ObjectMapper OM = buildObjectMapper();

    /** 测试载体: 覆盖 19位雪花ID(Long)、primitive long、状态(Integer)、金额(BigDecimal)、ID集合(List<Long>)、时间 */
    @SuppressWarnings("unused")
    static class IdHolder {
        public Long id;
        public long seq;
        public Integer status;
        public BigDecimal amount;
        public List<Long> ids;
        public LocalDateTime createTime;
    }

    @Test
    @DisplayName("19位雪花ID(Long)序列化为带引号字符串, 前端不丢精度")
    void longSerializesAsQuotedString() throws Exception {
        IdHolder h = new IdHolder();
        h.id = 1934567890123456789L;
        JsonNode node = OM.readTree(OM.writeValueAsString(h)).get("id");
        assertTrue(node.isTextual(), "Long 应为字符串节点");
        assertEquals("1934567890123456789", node.asText());
    }

    @Test
    @DisplayName("primitive long 同样序列化为带引号字符串")
    void primitiveLongSerializesAsQuotedString() throws Exception {
        IdHolder h = new IdHolder();
        h.seq = 1234567890123456789L;
        JsonNode node = OM.readTree(OM.writeValueAsString(h)).get("seq");
        assertTrue(node.isTextual(), "long 应为字符串节点");
        assertEquals("1234567890123456789", node.asText());
    }

    @Test
    @DisplayName("BigDecimal、Integer 与状态字段保持 JSON 数字输出")
    void decimalIntegerAndStatusStayNumeric() throws Exception {
        IdHolder h = new IdHolder();
        h.amount = new BigDecimal("12345.67");
        h.status = 2;
        JsonNode root = OM.readTree(OM.writeValueAsString(h));
        assertTrue(root.get("amount").isNumber(), "BigDecimal 应保持数字");
        assertEquals(new BigDecimal("12345.67"), root.get("amount").decimalValue());
        assertTrue(root.get("status").isInt(), "状态字段应保持整数");
        assertEquals(2, root.get("status").asInt());
    }

    @Test
    @DisplayName("字符串数字可反序列化绑定 Long 与 List<Long>")
    void stringNumbersDeserializeIntoLongAndList() throws Exception {
        String json = "{\"id\":\"1934567890123456789\",\"seq\":\"7\",\"status\":\"2\","
                + "\"ids\":[\"1\",\"1934567890123456789\"]}";
        IdHolder h = OM.readValue(json, IdHolder.class);
        assertEquals(Long.valueOf(1934567890123456789L), h.id);
        assertEquals(7L, h.seq);
        assertEquals(Integer.valueOf(2), h.status);
        assertEquals(Arrays.asList(1L, 1934567890123456789L), h.ids);
    }

    @Test
    @DisplayName("JavaTimeModule 不受影响: LocalDateTime 仍按 ISO 字符串输出且可回读")
    void javaTimeModuleUnaffected() throws Exception {
        IdHolder h = new IdHolder();
        h.createTime = LocalDateTime.of(2026, 9, 29, 10, 30, 15);
        JsonNode node = OM.readTree(OM.writeValueAsString(h)).get("createTime");
        assertTrue(node.isTextual(), "LocalDateTime 应为字符串节点");
        assertEquals(h.createTime, LocalDateTime.parse(node.asText()));
    }
}
