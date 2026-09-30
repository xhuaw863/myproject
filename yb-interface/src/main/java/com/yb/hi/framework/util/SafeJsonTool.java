package com.yb.hi.framework.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yb.hi.framework.common.BizException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌套 JSON 字符串安全序列化工具(T57/T59)。
 *
 * 背景: JacksonConfig 的 Long→字符串治理只覆盖"外层 Java 对象"的序列化; 实体中以 String 存放的
 * JSON 字段(如病历质控明细 his_inp_medical_record.quality_detail)此前由 fastjson 生成, 内部
 * 19 位雪花 Long ID 会写成裸 JSON 数字, 前端对字符串字段二次 JSON.parse 时静默丢精度
 * (如 ...386 变 ...400), 导致按 ruleId 回查/比较全部命中错误值。
 *
 * 口径:
 * 1) toJson 为生成端统一入口: 复用 Spring 管理的 ObjectMapper(与全局 Long→字符串策略同源),
 *    含 Long ID 的嵌套 JSON 一律走此入口, 不再使用 fastjson 序列化;
 * 2) normalizeEmbeddedJson 为存量兼容入口(T59 重写): 无法从"无类型"旧 JSON 中区分"雪花 Long ID"
 *    与"业务整数", 因此按 JS 数值安全边界治理——解析阶段启用 USE_BIG_INTEGER_FOR_INTS /
 *    USE_BIG_DECIMAL_FOR_FLOATS 保证读取零精度损失; 再递归遍历 Map/List/数组, 仅把绝对值超过
 *    JS 安全整数上界 2^53-1(9007199254740991) 的整数转为十进制 String, 其余整数保持 JSON number
 *    (int 范围转 Integer, 更大转 BigDecimal scale=0——刻意避开 Long 类型, 防止被全局
 *    Long→字符串策略误字符串化), 评分/扣分/小数(BigDecimal)保持原样, String/Boolean/null 不变;
 * 3) 非法 JSON 原样返回(容忍脏数据, 不阻断读取); 已字符串化的 ID 不再二次处理, normalize 幂等;
 * 4) 未使用任何正则替换, 全部走 Jackson 解析/重序列化。
 */
@Component
public class SafeJsonTool {

    /** JS Number 安全整数上界(2^53-1): 绝对值超过它的整数在浏览器 JSON.parse 后无法逐位精确 */
    private static final BigInteger JS_SAFE_MAX = BigInteger.valueOf(9007199254740991L);

    /** int 边界(供安全区间整数选择 Integer 表示) */
    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

    private final ObjectMapper objectMapper;

    public SafeJsonTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 生成端: 对象 → JSON 字符串(Long 与全局口径一致转字符串, 业务数值保持数字) */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(500, "嵌套JSON序列化失败: " + e.getOriginalMessage());
        }
    }

    /**
     * 读取兼容: 存量 JSON 字符串 → 零精度损失解析(BIG_INTEGER/BIG_DECIMAL) → 递归 JS 安全化重序列化;
     * 空白/非法 JSON 原样返回, 幂等。
     */
    public String normalizeEmbeddedJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return raw;
        }
        try {
            Object parsed = objectMapper.readerFor(Object.class)
                    .with(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(raw);
            if (parsed == null) {
                return raw;
            }
            return objectMapper.writeValueAsString(toJsSafeNode(parsed));
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 递归遍历 JSON 树(Map/List/数组), 只对整数节点做"JS 安全化":
     * - 绝对值 > 2^53-1(雪花ID等) → 十进制 String(前端逐字符精确);
     * - int 范围内 → Integer(number); int 范围外至安全上限 → BigDecimal(scale=0, 输出无小数的 number,
     *   同时避开全局 Long→字符串策略, 业务大整数不被误字符串化);
     * - BigDecimal(评分/扣分/小数)保留精度与尾零; String/Boolean/null/其他类型原样。
     */
    private static Object toJsSafeNode(Object node) {
        if (node instanceof Map) {
            Map<?, ?> src = (Map<?, ?>) node;
            Map<Object, Object> out = new LinkedHashMap<>(Math.max(4, src.size()));
            for (Map.Entry<?, ?> e : src.entrySet()) {
                out.put(e.getKey(), toJsSafeNode(e.getValue()));
            }
            return out;
        }
        if (node instanceof Collection) {
            Collection<?> src = (Collection<?>) node;
            List<Object> out = new ArrayList<>(src.size());
            for (Object item : src) {
                out.add(toJsSafeNode(item));
            }
            return out;
        }
        if (node instanceof Object[]) {
            Object[] src = (Object[]) node;
            List<Object> out = new ArrayList<>(src.length);
            for (Object item : src) {
                out.add(toJsSafeNode(item));
            }
            return out;
        }
        if (node instanceof BigInteger) {
            return safeInteger((BigInteger) node);
        }
        if (node instanceof Long) {
            return safeInteger(BigInteger.valueOf((Long) node));
        }
        /* Integer/Short/Byte/BigDecimal/Double/String/Boolean/null 等: 原样保留 */
        return node;
    }

    /** 整数安全化: 超 JS 安全上界 → 十进制字符串; 界内 → Integer(小值) 或 BigDecimal scale=0(大值, 输出 number) */
    private static Object safeInteger(BigInteger v) {
        if (v.abs().compareTo(JS_SAFE_MAX) > 0) {
            return v.toString();
        }
        if (v.compareTo(INT_MIN) >= 0 && v.compareTo(INT_MAX) <= 0) {
            return v.intValue();
        }
        return new BigDecimal(v);
    }
}
