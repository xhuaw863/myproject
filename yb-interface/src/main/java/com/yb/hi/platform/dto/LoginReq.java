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
}
