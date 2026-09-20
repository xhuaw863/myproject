package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.LoginReq;
import com.yb.hi.platform.dto.LoginResp;
import com.yb.hi.platform.dto.MenuNode;
import com.yb.hi.platform.service.AuthService;
import com.yb.hi.platform.service.SysRoleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 鉴权接口: 登录 / 当前用户 / 动态菜单
 * (医院开通已改为平台超级管理员后台操作, 见 SysTenantController; 不再提供公开自助注册)
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SysRoleService roleService;

    public AuthController(AuthService authService, SysRoleService roleService) {
        this.authService = authService;
        this.roleService = roleService;
    }

    /** 登录(公开) */
    @PostMapping("/login")
    public R<LoginResp> login(@RequestBody LoginReq req) {
        return R.ok(authService.login(req));
    }

    /** 当前登录用户(需鉴权) */
    @GetMapping("/me")
    public R<LoginUser> me() {
        return R.ok(UserContext.get());
    }

    /** 当前用户可见菜单树(按角色动态下发) */
    @GetMapping("/menus")
    public R<List<MenuNode>> menus() {
        return R.ok(roleService.resolveMenuTree(UserContext.get()));
    }
}
