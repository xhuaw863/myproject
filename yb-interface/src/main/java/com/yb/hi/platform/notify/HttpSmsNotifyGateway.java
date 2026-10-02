package com.yb.hi.platform.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 真实通知下发网关(P2c): 配置 yb.notify.gateway=http 时启用, POST 到 yb.notify.http.url。
 * 请求体含 phone/content/appId/appKey/sign; 响应 JSON 取 msgId 回填网关回执。未配 url 时安全返回失败(不抛)。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yb.notify.gateway", havingValue = "http")
public class HttpSmsNotifyGateway implements SmsNotifyGateway {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${yb.notify.http.url:}")
    private String url;
    @Value("${yb.notify.http.app-id:}")
    private String appId;
    @Value("${yb.notify.http.app-key:}")
    private String appKey;

    @Override
    public Result send(String phone, String content) {
        if (!StringUtils.hasText(url)) {
            return Result.fail("未配置 yb.notify.http.url");
        }
        if (!StringUtils.hasText(phone)) {
            return Result.fail("接收号码为空");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("phone", phone);
            body.put("content", content);
            body.put("appId", appId);
            body.put("appKey", appKey);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(url, new HttpEntity<>(body, h), Map.class);
            String msgId = resp == null ? null : String.valueOf(resp.getOrDefault("msgId", resp.get("messageId")));
            log.info("[通知下发-Http] to={}, resp={}", phone, resp);
            return Result.ok(StringUtils.hasText(msgId) && !"null".equals(msgId) ? msgId : null);
        } catch (Exception e) {
            log.warn("[通知下发-Http] 失败 to={}, err={}", phone, e.getMessage());
            return Result.fail(e.getMessage());
        }
    }
}
