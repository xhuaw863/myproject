package com.yb.hi.service.emr;

import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.Map;

/**
 * 病历模块领域事件(Spring 事件总线载体): 由 EmrEventPublisher 发布,
 * EmrEventListener 监听后经 SseEmitterService 推送至目标用户/科室(或全体广播)。
 *
 * 路由: targetUserId 非空 -> 定向用户; 否则 targetDeptId 非空 -> 定向科室; 两者均空 -> 广播全部在线连接。
 * data 为业务负载(recordId/patientName/templateName/message 等), 时间戳由 ApplicationEvent.getTimestamp() 提供。
 */
public class EmrEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /** 事件类型 */
    private final EmrEventType eventType;

    /** 目标用户ID(登录用户ID); null = 非用户定向(科室定向或广播) */
    private final Long targetUserId;

    /** 目标科室ID; 与 targetUserId 二选一, 两者均空 = 广播给所有在线连接 */
    private final Long targetDeptId;

    /** 业务负载(可空, 空则传空 Map) */
    private final Map<String, Object> data;

    /** 定向用户(或广播: targetUserId=null) */
    public EmrEvent(Object source, EmrEventType eventType, Long targetUserId, Map<String, Object> data) {
        this(source, eventType, targetUserId, null, data);
    }

    /** 完整构造: 用户定向/科室定向/广播三态由 targetUserId/targetDeptId 组合决定 */
    public EmrEvent(Object source, EmrEventType eventType, Long targetUserId, Long targetDeptId, Map<String, Object> data) {
        super(source);
        if (eventType == null) {
            throw new IllegalArgumentException("eventType 不能为空");
        }
        this.eventType = eventType;
        this.targetUserId = targetUserId;
        this.targetDeptId = targetDeptId;
        this.data = data == null ? Collections.emptyMap() : data;
    }

    public EmrEventType getEventType() {
        return eventType;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public Long getTargetDeptId() {
        return targetDeptId;
    }

    public Map<String, Object> getData() {
        return data;
    }

    /** 事件名(即 SSE event 名, 枚举名) */
    public String getEventName() {
        return eventType.name();
    }

    /** 是否广播事件(既无用户定向也无科室定向) */
    public boolean isBroadcast() {
        return targetUserId == null && targetDeptId == null;
    }
}
