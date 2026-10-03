package com.yb.hi.service.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 病历事件监听器: 监听 EmrEvent, 将负载序列化为 JSON 后经 SSE 推送。
 *
 * 推送契约(前端 EventSource/fetch 解析):
 * - SSE event 名 = EmrEventType 枚举名(如 QC_REMINDER), 前端按事件名 switch 路由;
 * - data 为 JSON 信封: { type, displayName, targetUserId, targetDeptId, data, timestamp }
 *   - type: 枚举名(与 event 名一致, 便于通用 onmessage 兜底处理);
 *   - displayName: 中文展示名(可直接作提示文案);
 *   - data: 业务负载(recordId/patientName/templateName/message 等, 结构由发布方约定);
 *   - timestamp: 事件构造时刻(毫秒)。
 *
 * 路由: targetUserId -> 定向用户; targetDeptId -> 定向科室; 两者均空 -> 广播。
 * 序列化用 Spring 托管的 ObjectMapper(继承 JacksonConfig 的雪花ID Long→字符串口径, 前端不丢精度);
 * 序列化/推送异常一律捕获不外抛, 避免影响业务发布方主流程。
 */
@Slf4j
@Component
public class EmrEventListener {

    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;

    public EmrEventListener(SseEmitterService sseEmitterService, ObjectMapper objectMapper) {
        this.sseEmitterService = sseEmitterService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onEmrEvent(EmrEvent event) {
        try {
            String payload = toJson(event);
            String eventName = event.getEventName();
            if (event.getTargetUserId() != null) {
                boolean sent = sseEmitterService.sendToUser(event.getTargetUserId(), eventName, payload);
                log.debug("SSE定向事件: type={}, targetUserId={}, 送达={}", eventName, event.getTargetUserId(), sent);
            } else if (event.getTargetDeptId() != null) {
                int sent = sseEmitterService.sendToDept(event.getTargetDeptId(), eventName, payload);
                log.debug("SSE科室事件: type={}, targetDeptId={}, 送达连接数={}", eventName, event.getTargetDeptId(), sent);
            } else {
                int sent = sseEmitterService.broadcast(eventName, payload);
                log.debug("SSE广播事件: type={}, 送达连接数={}", eventName, sent);
            }
        } catch (Exception e) {
            // 推送层异常不得反向影响业务发布方主流程
            log.error("SSE事件推送异常: type={}, 原因={}", event.getEventName(), e.getMessage(), e);
        }
    }

    /** 序列化事件信封; 失败时退化为仅含类型的最小 JSON(保证事件可达) */
    private String toJson(EmrEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", event.getEventName());
        envelope.put("displayName", event.getEventType().getDisplayName());
        envelope.put("targetUserId", event.getTargetUserId());
        envelope.put("targetDeptId", event.getTargetDeptId());
        envelope.put("data", event.getData());
        envelope.put("timestamp", event.getTimestamp());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.error("SSE事件序列化失败, 退化为最小载荷: type={}, 原因={}", event.getEventName(), e.getMessage());
            return "{\"type\":\"" + event.getEventName() + "\",\"displayName\":\""
                    + event.getEventType().getDisplayName() + "\"}";
        }
    }
}
