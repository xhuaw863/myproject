package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色: 全局预置(tenant_id=NULL) + 租户自定义(tenant_id=具体租户)。
 * 已入 IGNORE_TABLES(不走租户插件), tenantId 显式映射, 由服务层显式按租户过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID(NULL=全局预置角色) */
    private Long tenantId;
    /** 角色编码 */
    private String roleCode;
    /** 角色名称 */
    private String roleName;
    /** 类型: 1-全局预置 2-租户自定义 */
    private Integer roleType;
    /** 是否拥有全部菜单: 1-是(ADMIN/SUPER_ADMIN) */
    private Integer allMenus;
    /** 备注 */
    private String remark;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
}
