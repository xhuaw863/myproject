package com.yb.hi.framework.common;

/**
 * 角色常量
 */
public final class Roles {

    private Roles() {
    }

    /** 系统管理员(医共体牵头机构) */
    public static final String ADMIN = "ADMIN";
    /** 机构系统管理员(非牵头医疗机构, 无系统管理菜单) */
    public static final String ORG_ADMIN = "ORG_ADMIN";
    /** 挂号员 */
    public static final String REGISTRAR = "REGISTRAR";
    /** 医生 */
    public static final String DOCTOR = "DOCTOR";
    /** 药师 */
    public static final String PHARMACIST = "PHARMACIST";
    /** 收费员 */
    public static final String CASHIER = "CASHIER";
    /** 护士 */
    public static final String NURSE = "NURSE";
    /** 平台超级管理员(跨租户) */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
}
