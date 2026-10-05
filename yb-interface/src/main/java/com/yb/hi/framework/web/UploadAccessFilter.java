package com.yb.hi.framework.web;

import com.alibaba.fastjson2.JSON;
import com.yb.hi.common.UploadUrlSigner;
import com.yb.hi.framework.common.R;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 受保护上传文件访问闸: 签名图片、企业资质、归档 PDF 不得匿名直读,
 * 只能通过带签名 token 的 URL 访问; 头像等通用图片仍按原静态映射公开。
 */
@Component
public class UploadAccessFilter extends OncePerRequestFilter {

    private static final String[] PROTECTED_PREFIXES = {
            "/uploads/signatures/",
            "/uploads/supplier-doc/",
            "/uploads/emr-archive/"
    };

    private final UploadUrlSigner signer;

    public UploadAccessFilter(UploadUrlSigner signer) {
        this.signer = signer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }
        for (String prefix : PROTECTED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!signer.verify(uri, request.getParameter("token"))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(JSON.toJSONString(R.fail(401, "文件访问凭证无效或已过期")));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
