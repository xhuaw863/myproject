package com.yb.hi.platform.dto;

import lombok.Data;

import java.util.List;

/**
 * 角色-菜单分配请求
 */
@Data
public class RoleMenuReq {
    /** 菜单ID集合(为空表示清空该角色菜单授权) */
    private List<Long> menuIds;
}
