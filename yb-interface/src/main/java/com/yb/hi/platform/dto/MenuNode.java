package com.yb.hi.platform.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点(下发前端动态渲染)
 */
@Data
public class MenuNode {
    private Long id;
    private Long parentId;
    /** 菜单键(=前端组件路由键) */
    private String menuKey;
    /** 菜单名称 */
    private String menuName;
    /** 类型: 1-目录 2-菜单 */
    private Integer menuType;
    /** 前端 HIS.views 组件名 */
    private String comp;
    /** 建设阶段占位 */
    private String phase;
    /** 图标 */
    private String icon;
    private Integer sortNo;
    /** 是否显示: 1-显示 0-隐藏 */
    private Integer visible;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
    private List<MenuNode> children = new ArrayList<>();
}
