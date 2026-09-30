package com.yb.hi.framework.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 序列化统一配置: 雪花ID(Long)全局转字符串下发
 *
 * 背景: 前端 JS Number 安全整数上限为 2^53-1(9007199254740991), 19位雪花ID 超出该范围,
 * 若以 JSON 数字下发, 会在浏览器 JSON.parse 阶段静默丢精度(如尾数 ...7890 变 ...7800),
 * 造成列表行选中、详情回查、跨页传参等场景携带错误 ID。
 *
 * 治理口径: 仅对 Long.class 与 Long.TYPE(long) 注册 ToStringSerializer, 将 ID 序列化为带引号的字符串;
 * 经 Spring Boot 2.7 标准 Jackson2ObjectMapperBuilderCustomizer 扩展点参与 Jackson2ObjectMapperBuilder 构建,
 * 不自建 ObjectMapper、不替换 MappingJackson2HttpMessageConverter, 因此:
 * 1) JavaTimeModule(时间类型)及既有模块注册不受影响;
 * 2) BigDecimal/Integer(金额/状态列)仍按 JSON 数字输出;
 * 3) 反序列化不受影响(字符串数字照常绑定 Long/List<Long>, 依赖 Jackson 默认标量强制转换)。
 *
 * 前端契约: static/js/api.js 的 HIS.id / HIS.idKey / HIS.idParam / HIS.sameId,
 * 页面间传递 ID 用 HIS.id, 拼接 URL 参数用 HIS.idParam, 相等比较一律 HIS.sameId(禁止 === 直比)。
 */
@Configuration
public class JacksonConfig {

    /** 注册 Long 序列化器(包装类型 Long 与原生类型 long): 只覆盖序列化, 不注册反序列化器 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
