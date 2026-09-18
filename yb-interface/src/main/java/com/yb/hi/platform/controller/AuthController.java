package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.LoginReq;
import com.yb.hi.platform.dto.LoginResp;
import com.yb.hi.platform.dto.TenantRegisterReq;
import com.yb.hi.platform.service.AuthService;
import com.yb.hi.platform.service.SysTenantService;
import org.springframework.web.bind.annotation.*;

/**
 * 鉴权接口: 登录 / 医院注册 / 当前用户
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SysTenantService tenantService;

    public AuthController(AuthService authService, SysTenantService tenantService) {
        this.authService = authService;
        this.tenantService = tenantService;
    }

    /** 登录(公开) */
    @PostMapping("/login")
    public R<LoginResp> login(@RequestBody LoginReq req) {
        return R.ok(authService.login(req));
    }

    /** 医院注册(公开) */
    @PostMapping("/register")
    public R<Long> register(@RequestBody TenantRegisterReq req) {
        return R.ok("注册成功", authService.register(req));
    }

    /** 医院登录码是否已存在(公开) */
    @GetMapping("/tenant-exists")
    public R<Boolean> tenantExists(@RequestParam String code) {
        return R.ok(tenantService.codeExists(code));
    }

    /** 当前登录用户(需鉴权) */
    @GetMapping("/me")
    public R<LoginUser> me() {
        return R.ok(UserContext.get());
    }
}
