package com.yb.hi.platform.dto;

import lombok.Data;

/**
 * 登录响应
 */
@Data
public class LoginResp {
    private String token;
    private Long userId;
    private Long tenantId;
    private String tenantCode;
    private String tenantName;
    private String username;
    private String realName;
    private String role;
    private Long staffId;
    private Long deptId;
}
