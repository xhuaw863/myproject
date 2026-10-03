package com.yb.hi.service.emr;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 病历事件发布器: 业务侧(质控/签名/锁定/模板)注入本类, 以领域事件解耦"业务发生"与"SSE 推送"。
 * 发布经 Spring 事件总线(ApplicationEventPublisher.publishEvent, 同线程同步派发),
 * 由 EmrEventListener 监听并路由到 SseEmitterService(EVENT 无监听器/无连接时静默丢弃, 不影响主流程)。
 */
@Service
public class EmrEventPublisher {

    private final ApplicationEventPublisher publisher;

    public EmrEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** 质控提醒: 通知病历相关医生整改(定向用户) */
    public void publishQcReminder(Long userId, String message, String recordId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message);
        data.put("recordId", recordId);
        publish(userId, EmrEventType.QC_REMINDER, data);
    }

    /** 待签名提醒: 通知对应环节医生签署(定向用户) */
    public void publishSignatureRequired(Long userId, String recordType, String patientName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordType", recordType);
        data.put("patientName", patientName);
        publish(userId, EmrEventType.SIGNATURE_REQUIRED, data);
    }

    /** 病历锁定通知: 告知相关医生病历已锁定不可编辑(定向用户) */
    public void publishRecordLocked(Long userId, String recordId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", recordId);
        publish(userId, EmrEventType.RECORD_LOCKED, data);
    }

    /** 模板更新通知: 模板修改/传播后告知使用医生刷新(定向科室, 该科室全部在线用户) */
    public void publishTemplateUpdated(Long deptId, String templateName) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateName", templateName);
        publishToDept(deptId, EmrEventType.TEMPLATE_UPDATED, data);
    }

    /**
     * 通用发布-定向用户: 其余事件类型(QC_PENALTY/RECORD_UNLOCKED/CONSULTATION_UPDATE)走此入口。
     *
     * @param targetUserId 目标用户; null = 广播全体在线连接
     */
    public void publish(Long targetUserId, EmrEventType eventType, Map<String, Object> data) {
        publisher.publishEvent(new EmrEvent(this, eventType, targetUserId, data));
    }

    /** 通用发布-定向科室(该科室全部在线用户) */
    public void publishToDept(Long targetDeptId, EmrEventType eventType, Map<String, Object> data) {
        publisher.publishEvent(new EmrEvent(this, eventType, null, targetDeptId, data));
    }
}
