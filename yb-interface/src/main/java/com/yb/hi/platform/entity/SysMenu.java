package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 菜单/权限(全局真源, 无 tenant_id 列, 已入 IGNORE_TABLES)。
 * menu_key 即前端组件路由键(如 user-manage); comp 为 HIS.views 组件名。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父菜单ID(0=顶级) */
    private Long parentId;
    /** 菜单键(=前端组件路由键, 唯一) */
    private String menuKey;
    /** 菜单名称 */
    private String menuName;
    /** 类型: 1-目录 2-菜单 */
    private Integer menuType;
    /** 前端 HIS.views 组件名(目录为空) */
    private String comp;
    /** 建设阶段占位(无 comp 时显示"建设中") */
    private String phase;
    /** 图标 */
    private String icon;
    /** 排序号 */
    private Integer sortNo;
    /** 是否显示: 1-是 0-否 */
    private Integer visible;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
}
