package com.yb.hi.framework.util;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import com.yb.hi.framework.tenant.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT 令牌工具(基于 Hutool)
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${his.jwt.secret:yb-his-default-secret-key-2026}")
    private String secret;

    @Value("${his.jwt.expire-minutes:720}")
    private long expireMinutes;

    /** 弱密钥启动告警(B7): 默认/过短(<32字符)密钥可被持有源码者伪造登录令牌 */
    @PostConstruct
    public void warnWeakSecret() {
        if (secret == null || secret.length() < 32) {
            log.error("【安全】his.jwt.secret 使用内置默认密钥或强度不足(长度{}<32)。生产部署必须通过环境变量 HIS_JWT_SECRET 注入 ≥32字符独立强密钥, 否则任意持有源码者均可伪造登录令牌!", secret == null ? 0 : secret.length());
        }
    }

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
        payload.put("orgId", user.getOrgId());
        payload.put("roleId", user.getRoleId());
        // 多角色(医共体一人多角色): 逗号串存 claim, 解析时拆回列表
        if (user.getRoles() != null && !user.getRoles().isEmpty()) {
            payload.put("roles", String.join(",", user.getRoles()));
        }
        if (user.getRoleIds() != null && !user.getRoleIds().isEmpty()) {
            List<String> idStr = new ArrayList<>();
            for (Long id : user.getRoleIds()) {
                idStr.add(String.valueOf(id));
            }
            payload.put("roleIds", String.join(",", idStr));
        }
        payload.put("leadOrg", user.getLeadOrg());
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
            // S-1(2026-10-03 安全审计): 拒绝 alg=none / 空签名令牌重放 —— 须为标准三段且签名段非空, header.alg 必须 HS256
            // (Hutool JWTUtil.verify 对"none头+空签名"返回 true, 被截获的合法令牌去签名即可重放至过期)
            String[] seg = token.split("\\.", -1);
            if (seg.length != 3 || seg[0].isEmpty() || seg[1].isEmpty() || seg[2].isEmpty()) {
                return null;
            }
            String headerJson;
            try {
                headerJson = new String(java.util.Base64.getUrlDecoder().decode(seg[0]), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                return null;
            }
            if (headerJson.toUpperCase().indexOf("HS256") < 0) {
                return null;
            }
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
            user.setOrgId(toLong(jwt.getPayload("orgId")));
            user.setRoleId(toLong(jwt.getPayload("roleId")));
            user.setRoles(toList(toStr(jwt.getPayload("roles"))));
            user.setRoleIds(toLongList(toStr(jwt.getPayload("roleIds"))));
            user.setLeadOrg(toBool(jwt.getPayload("leadOrg")));
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

    /** 逗号串拆列表(空/无效返回 null, 触发 LoginUser.hasRole 主角色回落) */
    private List<String> toList(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.trim().isEmpty()) {
                out.add(p.trim());
            }
        }
        return out.isEmpty() ? null : out;
    }

    private List<Long> toLongList(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        List<Long> out = new ArrayList<>();
        for (String p : s.split(",")) {
            Long v = toLong(p.trim());
            if (v != null) {
                out.add(v);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private Boolean toBool(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        return Boolean.parseBoolean(o.toString());
    }
}
