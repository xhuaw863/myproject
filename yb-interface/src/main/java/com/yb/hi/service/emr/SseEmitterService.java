package com.yb.hi.service.emr;

import com.yb.hi.framework.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 实时通知服务(病历模块): 维护 userId -> SseEmitter 在线连接表, 提供定向推送/全体广播/心跳保活。
 *
 * 设计要点:
 * - 每用户最多 1 条连接: 同用户再次 connect 时旧连接被替换并 complete(前端断线重连/多标签页自动收敛);
 * - 科室路由: connect 时登记 userId->deptId(deptId 非空时), sendToDept 按精确科室匹配向该科室全部在线用户推送;
 * - 连接生命周期: 超时(5分钟, SseEmitter 自带 timeout 参数) / 客户端断开(IOException) / 服务端主动 disconnect,
 *   三条路径都经 onCompletion/onTimeout/onError 回调或发送失败清理, 用 remove(key, value) 原子比较避免误删新连接;
 * - 线程安全: ResponseBodyEmitter/SSE 底层响应流不允许并发写, 所有 send/comment/complete 均在 synchronized(emitter) 内执行;
 * - 心跳: 每 30s 向所有连接发送注释行(comment, 前端无感知, 不触发 onmessage), 防止网关/浏览器空闲断连并探活清理死连接;
 * - 发送失败静默: IOException 视为"客户端已断开"(最常见), 仅 debug 日志 + 清理注册表, 不向业务抛出。
 *
 * 注: SSE 事件名(event name)与 EmrEventType 枚举名一致, data 由 EmrEventListener 序列化为 JSON 字符串原样下发。
 */
@Slf4j
@Service
public class SseEmitterService {

    /** 连接超时(毫秒): 5 分钟, 到期前端自动重连 */
    private static final long TIMEOUT_MS = 300_000L;

    /** 心跳注释内容 */
    private static final String HEARTBEAT_COMMENT = "heartbeat";

    /** 在线连接: userId -> emitter(单用户单连接, 新连接替换旧连接) */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** 在线用户科室映射: userId -> deptId(sendToDept 科室路由用; 随连接建立/清理同步维护) */
    private final Map<Long, Long> userDeptIds = new ConcurrentHashMap<>();

    /**
     * 建立 SSE 连接(无科室信息): 创建 5 分钟超时的 SseEmitter 并注册清理回调。
     */
    public SseEmitter connect(Long userId) {
        return connect(userId, null);
    }

    /**
     * 建立 SSE 连接: 创建 5 分钟超时的 SseEmitter 并注册清理回调。
     * 同用户已有连接时, 旧连接被 complete 替换(最多保留 1 条); deptId 登记用于 sendToDept 科室路由。
     */
    public SseEmitter connect(Long userId, Long deptId) {
        if (userId == null) {
            throw new BizException(401, "未登录, 无法建立SSE连接");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        SseEmitter old = emitters.put(userId, emitter);
        if (deptId != null) {
            userDeptIds.put(userId, deptId);
        } else {
            userDeptIds.remove(userId);
        }
        if (old != null) {
            // 同用户重连: 替换旧连接(旧连接的清理回调因 remove(key, value) 值不匹配而自动失效)
            complete(old);
            log.info("SSE旧连接被新连接替换: userId={}", userId);
        }
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            remove(userId, emitter);
            complete(emitter);
            log.info("SSE连接超时关闭: userId={}, 当前连接数={}", userId, emitters.size());
        });
        emitter.onError(e -> remove(userId, emitter));
        // 首帧注释: 立即冲刷响应头让前端 EventSource 进入 OPEN 状态, 并确认连接可用
        sendComment(emitter, "connected");
        log.info("SSE连接建立: userId={}, 当前连接数={}", userId, emitters.size());
        return emitter;
    }

    /** 主动断开某用户连接(存在则移除并 complete) */
    public void disconnect(Long userId) {
        if (userId == null) {
            return;
        }
        SseEmitter emitter = emitters.remove(userId);
        userDeptIds.remove(userId);
        if (emitter != null) {
            complete(emitter);
            log.info("SSE连接主动断开: userId={}, 当前连接数={}", userId, emitters.size());
        }
    }

    /**
     * 定向推送: 向指定用户发送 SSE 事件。
     *
     * @param eventType SSE 事件名(约定为 EmrEventType 枚举名)
     * @param data      已序列化的 JSON 字符串或可被 Spring 消息转换器序列化的对象
     * @return true=已写入响应; false=用户无在线连接或连接已断开(静默清理)
     */
    public boolean sendToUser(Long userId, String eventType, Object data) {
        if (userId == null) {
            return false;
        }
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            return false;
        }
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(eventType).data(data));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            // IOException: 客户端已断开(页面关闭/网络中断); IllegalStateException: 连接已被完成
            log.debug("SSE定向发送失败(客户端可能已断开): userId={}, event={}, 原因={}", userId, eventType, e.getMessage());
            remove(userId, emitter);
            complete(emitter);
            return false;
        }
    }

    /**
     * 全体广播: 向所有在线连接发送同一事件。
     *
     * @return 实际送达的连接数
     */
    public int broadcast(String eventType, Object data) {
        int sent = 0;
        for (Long userId : new ArrayList<>(emitters.keySet())) {
            if (sendToUser(userId, eventType, data)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * 科室定向推送: 向指定科室的全部在线用户发送事件(精确 deptId 匹配, 不穿透下级科室)。
     *
     * @return 实际送达的连接数
     */
    public int sendToDept(Long deptId, String eventType, Object data) {
        if (deptId == null) {
            return 0;
        }
        int sent = 0;
        for (Map.Entry<Long, Long> entry : new ArrayList<>(userDeptIds.entrySet())) {
            if (deptId.equals(entry.getValue()) && sendToUser(entry.getKey(), eventType, data)) {
                sent++;
            }
        }
        return sent;
    }

    /** 当前活跃连接数 */
    public int getConnectionCount() {
        return emitters.size();
    }

    /** 当前在线用户ID快照(排障用, 管理端接口) */
    public List<Long> connectedUserIds() {
        return new ArrayList<>(emitters.keySet());
    }

    /**
     * 心跳保活: 每 30s 向所有连接发送注释行(comment)。
     * SSE 注释行不产生 onmessage 事件, 前端无感知; 发送失败即判定死连接并清理。
     */
    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, SseEmitter> entry : new ArrayList<>(emitters.entrySet())) {
            try {
                synchronized (entry.getValue()) {
                    entry.getValue().send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE心跳发送失败, 清理死连接: userId={}, 原因={}", entry.getKey(), e.getMessage());
                remove(entry.getKey(), entry.getValue());
                complete(entry.getValue());
            }
        }
    }

    /** 原子移除注册表: 仅当仍是当前连接时才连带清理科室映射(防止旧连接回调误删新连接) */
    private void remove(Long userId, SseEmitter emitter) {
        if (emitters.remove(userId, emitter)) {
            userDeptIds.remove(userId);
        }
    }

    /** 发送注释行(失败静默, 由心跳/发送失败路径统一清理) */
    private void sendComment(SseEmitter emitter, String comment) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().comment(comment));
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE注释发送失败(客户端可能已断开): {}", e.getMessage());
        }
    }

    /** 安全 complete(已超时/已完成/已断开的连接再 complete 可能抛异常, 一律忽略) */
    private void complete(SseEmitter emitter) {
        try {
            synchronized (emitter) {
                emitter.complete();
            }
        } catch (Exception ignore) {
            // 幂等完成: 异常无业务含义
        }
    }
}
