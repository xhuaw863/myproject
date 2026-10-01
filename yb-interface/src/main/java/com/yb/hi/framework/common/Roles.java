package com.yb.hi.framework.common;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
    /** 治疗师 */
    public static final String THERAPIST = "THERAPIST";
    /** 医技人员 */
    public static final String TECHNICIAN = "TECHNICIAN";
    /** 病案录入组(病案统计科: 首页信息录入/明细维护) */
    public static final String MR_INPUT = "MR_INPUT";
    /** 病案编目组(病案统计科: 病案分配/编目定稿) */
    public static final String MR_CATALOG = "MR_CATALOG";
    /** 病案质控组(病案统计科: 质量审核/确认锁定) */
    public static final String MR_REVIEW = "MR_REVIEW";
    /** 平台超级管理员(跨租户) */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** 平台预置角色编码集: 判权按编码字符串(hasRole/requireLeadWrite/DeptScopeResolver), 租户自建同码角色即等价提权, 一律禁止租户自定义使用 */
    public static final Set<String> RESERVED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            SUPER_ADMIN, ADMIN, ORG_ADMIN, REGISTRAR, DOCTOR, PHARMACIST, CASHIER, NURSE, THERAPIST, TECHNICIAN,
            MR_INPUT, MR_CATALOG, MR_REVIEW)));

    /** 是否平台预置角色编码(大小写不敏感) */
    public static boolean isReserved(String roleCode) {
        return roleCode != null && RESERVED.contains(roleCode.trim().toUpperCase());
    }
}
