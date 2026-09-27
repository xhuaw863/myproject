package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户-角色关联(医共体一人多角色): 一个账号可同时拥有多个角色, 权限取并集。
 * sys_user.role/role_id 降级为"主角色"(仅显示与无关联行时的兜底), 判权走本表集合。
 * tenant_id 由多租户插件自动注入/过滤, 与 sys_user_org 同套路(不入 IGNORE_TABLES)。
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID(sys_user) */
    private Long userId;
    /** 角色ID(sys_role) */
    private Long roleId;
    /** 租户ID(多租户插件维护) */
    private Long tenantId;
}
