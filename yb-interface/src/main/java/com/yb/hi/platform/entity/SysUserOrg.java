package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户可登录机构关联(多点执业): 一个账号可授权登录本医共体内多个机构。
 * 归属机构(sys_user.org_id)为默认可登录机构, 逻辑上始终允许, 亦在此显式落一行。
 * tenant_id 由多租户插件自动注入/过滤, 保证仅本医共体范围。
 */
@Data
@TableName("sys_user_org")
public class SysUserOrg {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID(sys_user) */
    private Long userId;
    /** 可登录机构ID(sys_org) */
    private Long orgId;
    /** 租户ID(多租户插件维护) */
    private Long tenantId;
}
