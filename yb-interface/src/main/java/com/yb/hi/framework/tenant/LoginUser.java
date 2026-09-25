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
    /** 归属机构ID */
    private Long orgId;
    /** 权威角色ID(sys_role) */
    private Long roleId;
    /** 是否牵头机构(org_level=1): 牵头可维护全医共体基础数据, 非牵头只读 */
    private Boolean leadOrg;
    /** 医院名称 */
    private String tenantName;
}
