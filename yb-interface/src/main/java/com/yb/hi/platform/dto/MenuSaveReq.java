package com.yb.hi.platform.dto;

import lombok.Data;

/**
 * 菜单新增/修改请求
 */
@Data
public class MenuSaveReq {
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
    /** 建设阶段占位 */
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
