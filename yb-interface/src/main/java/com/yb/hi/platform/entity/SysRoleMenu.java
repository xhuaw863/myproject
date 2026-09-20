package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 角色-菜单关联(纯连接表, 已入 IGNORE_TABLES)。按 role_id 管理, 重分配时物理删除重建。
 */
@Data
@TableName("sys_role_menu")
public class SysRoleMenu implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色ID */
    private Long roleId;
    /** 菜单ID */
    private Long menuId;
    /** 租户ID(冗余, 随角色) */
    private Long tenantId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public SysRoleMenu() {
    }

    public SysRoleMenu(Long roleId, Long menuId, Long tenantId) {
        this.roleId = roleId;
        this.menuId = menuId;
        this.tenantId = tenantId;
    }
}
