package com.yb.hi.platform.dto;

import lombok.Data;

/**
 * 登录请求
 */
@Data
public class LoginReq {
    /** 医院登录码 */
    private String tenantCode;
    /** 账号 */
    private String username;
    /** 密码 */
    private String password;
    /** 目标登录机构ID(多点执业可选); 为空则默认进入归属机构 */
    private Long orgId;
}
