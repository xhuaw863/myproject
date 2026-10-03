package com.yb.hi.framework.web;

import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.ExportGuard;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 导出防护拦截器: 凡 URL 含 "export" 的端点统一过 ExportGuard(每用户限流 + 全局并发闸)。
 *
 * 挂 URL 咽喉点而非逐 Controller 布防: 覆盖现有 27 个导出端点并天然约束未来新增导出接口;
 * 抛 BizException(429) 由 GlobalExceptionHandler 转 HTTP 200 + R JSON 信封(与 code=403 口径一致),
 * 前端 HIS.download 已识别 JSON 错误体弹出 msg, 无需下载损坏文件。
 *
 * 必须在 AuthInterceptor 之后注册(UserContext 已由鉴权写入)。
 */
@Component
public class ExportGuardInterceptor implements HandlerInterceptor {

    /** preHandle 成功占用并发位后打标, afterCompletion 依据标记精确释放(异常未占用则不释放)。 */
    private static final String ATTR_ACQUIRED = "yb.exportGuard.acquired";

    private final ExportGuard exportGuard;

    public ExportGuardInterceptor(ExportGuard exportGuard) {
        this.exportGuard = exportGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.toLowerCase().contains("export")) {
            return true;
        }
        exportGuard.enter(UserContext.username());
        request.setAttribute(ATTR_ACQUIRED, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (Boolean.TRUE.equals(request.getAttribute(ATTR_ACQUIRED))) {
            request.removeAttribute(ATTR_ACQUIRED);
            exportGuard.exit();
        }
    }
}
