package com.yb.hi.controller.emr;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.emr.SseEmitterService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 病历实时通知 SSE 接口: 客户端建立长连接后, 接收质控提醒/待签名/病历锁定/模板更新等推送。
 *
 * 鉴权: 走 AuthInterceptor 统一 JWT 校验(Authorization: Bearer 或 token 头), 控制器内由 UserContext 取当前用户;
 * 注: 浏览器原生 EventSource 不支持自定义请求头, 前端需用 fetch/ReadableStream 方式携带鉴权头消费本端点。
 *
 * 传输要求: produces=text/event-stream; 服务端未开启响应压缩(application.yml 无 server.compression 配置),
 * 分块流式输出不受压缩缓冲影响。
 */
@RestController
@RequestMapping("/api/his/emr/sse")
public class SseController {

    private final SseEmitterService sseEmitterService;

    public SseController(SseEmitterService sseEmitterService) {
        this.sseEmitterService = sseEmitterService;
    }

    /**
     * 建立 SSE 连接: GET /api/his/emr/sse/connect
     * 返回 SseEmitter 由 Spring MVC 接管异步写出(5 分钟超时, 前端到期自动重连; 同用户新连接替换旧连接)。
     */
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect() {
        LoginUser cur = UserContext.get();
        if (cur == null || cur.getUserId() == null) {
            // 兜底: 正常路径已被 AuthInterceptor 拦截
            throw new BizException(401, "未登录, 无法建立SSE连接");
        }
        // 登记科室用于 sendToDept 科室定向推送(模板更新等)
        return sseEmitterService.connect(cur.getUserId(), cur.getDeptId());
    }

    /** 连接统计(管理端排障): GET /api/his/emr/sse/status, 返回当前连接数与在线用户ID */
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        LoginUser cur = UserContext.get();
        if (cur == null || !cur.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN, Roles.SUPER_ADMIN)) {
            throw new BizException(403, "仅管理员可查看SSE连接统计");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectionCount", sseEmitterService.getConnectionCount());
        out.put("connectedUserIds", sseEmitterService.connectedUserIds());
        return R.ok(out);
    }
}
