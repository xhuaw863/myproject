package com.yb.hi.framework.tenant;

import lombok.Data;

/**
 * 登录用户信息(存于 UserContext 与 JWT 载荷)
 */
@Data
public class LoginUser {

    /** 用户ID */
    private Long userId;
    /** 租户ID */
    private Long tenantId;
    /** 登录账号 */
    private String username;
    /** 姓名 */
    private String realName;
    /** 角色: ADMIN/REGISTRAR/DOCTOR/PHARMACIST/CASHIER/NURSE */
    private String role;
    /** 关联职工ID */
    private Long staffId;
    /** 关联科室ID */
    private Long deptId;
    /** 医院名称 */
    private String tenantName;
}
