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
    /** 归属机构ID */
    private Long orgId;
    /** 权威角色ID */
    private Long roleId;
    /** 归属机构名称(前端显示) */
    private String orgName;
    /** 角色名称(支持租户自定义角色名, 前端显示) */
    private String roleName;
}
