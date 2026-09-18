package com.yb.hi.framework.util;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import com.yb.hi.framework.tenant.LoginUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 令牌工具(基于 Hutool)
 */
@Component
public class JwtUtil {

    @Value("${his.jwt.secret:yb-his-default-secret-key-2026}")
    private String secret;

    @Value("${his.jwt.expire-minutes:720}")
    private long expireMinutes;

    private byte[] keyBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 签发令牌 */
    public String createToken(LoginUser user) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", user.getUserId());
        payload.put("tenantId", user.getTenantId());
        payload.put("username", user.getUsername());
        payload.put("realName", user.getRealName());
        payload.put("role", user.getRole());
        payload.put("staffId", user.getStaffId());
        payload.put("deptId", user.getDeptId());
        payload.put("tenantName", user.getTenantName());
        long now = System.currentTimeMillis();
        payload.put("iat", new Date(now));
        payload.put("exp", new Date(now + expireMinutes * 60 * 1000));
        return JWTUtil.createToken(payload, keyBytes());
    }

    /** 校验并解析令牌, 失败返回 null */
    public LoginUser parseToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            if (!JWTUtil.verify(token, keyBytes())) {
                return null;
            }
            JWT jwt = JWTUtil.parseToken(token);
            JWTValidator.of(jwt).validateDate(new Date());
            LoginUser user = new LoginUser();
            user.setUserId(toLong(jwt.getPayload("userId")));
            user.setTenantId(toLong(jwt.getPayload("tenantId")));
            user.setUsername(toStr(jwt.getPayload("username")));
            user.setRealName(toStr(jwt.getPayload("realName")));
            user.setRole(toStr(jwt.getPayload("role")));
            user.setStaffId(toLong(jwt.getPayload("staffId")));
            user.setDeptId(toLong(jwt.getPayload("deptId")));
            user.setTenantName(toStr(jwt.getPayload("tenantName")));
            return user;
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toStr(Object o) {
        return o == null ? null : o.toString();
    }
}
