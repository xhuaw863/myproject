package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.TenantRegisterReq;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.AuthService;
import com.yb.hi.platform.service.SysTenantService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户(医院)信息与医保配置接口。
 * /current 供医院管理员维护本院信息; /list /open /{id}/status 仅平台超级管理员可用(跨租户医院开通与管理)。
 */
@RestController
@RequestMapping("/api/sys/tenant")
public class SysTenantController {

    private final SysTenantService tenantService;
    private final AuthService authService;

    public SysTenantController(SysTenantService tenantService, AuthService authService) {
        this.tenantService = tenantService;
        this.authService = authService;
    }

    /* ============ 平台超级管理员: 跨租户医院开通与管理 ============ */

    /** 医院列表(排除平台运营方租户, 隐藏密钥明文) */
    @GetMapping("/list")
    public R<List<SysTenant>> list() {
        requireSuper();
        List<SysTenant> list = tenantService.listHospitals();
        for (SysTenant t : list) {
            t.setSm2PrivateKey(mask(t.getSm2PrivateKey()));
        }
        return R.ok(list);
    }

    /** 开通新医院: 建租户 + 医保配置 + 默认机构 + 初始管理员账号(即时可用) */
    @PostMapping("/open")
    public R<Long> open(@RequestBody TenantRegisterReq req) {
        requireSuper();
        return R.ok("医院开通成功", authService.openHospital(req));
    }

    /** 启用/停用医院 */
    @PostMapping("/{id}/status")
    public R<Void> status(@PathVariable Long id, @RequestParam Integer status) {
        requireSuper();
        tenantService.setStatus(id, status);
        return R.ok();
    }

    /** 仅平台超级管理员可操作跨租户医院管理 */
    private void requireSuper() {
        LoginUser u = UserContext.get();
        if (u == null || !Roles.SUPER_ADMIN.equals(u.getRole())) {
            throw new BizException(403, "仅平台超级管理员可操作");
        }
    }

    /** 当前医院信息(隐藏密钥明文) */
    @GetMapping("/current")
    public R<SysTenant> current() {
        SysTenant t = tenantService.getById(UserContext.get().getTenantId());
        if (t != null) {
            t.setSm2PrivateKey(mask(t.getSm2PrivateKey()));
        }
        return R.ok(t);
    }

    /** 更新当前医院的医保配置 */
    @PutMapping("/current")
    public R<Void> updateCurrent(@RequestBody SysTenant req) {
        Long tenantId = UserContext.get().getTenantId();
        SysTenant t = tenantService.getById(tenantId);
        if (t == null) {
            return R.fail("医院不存在");
        }
        // 仅允许更新配置类字段
        t.setFixmedinsCode(req.getFixmedinsCode());
        t.setFixmedinsName(req.getFixmedinsName());
        t.setMdtrtareaAdmvs(req.getMdtrtareaAdmvs());
        t.setInsuplcAdmdvs(req.getInsuplcAdmdvs());
        t.setApiUrl(req.getApiUrl());
        t.setFileDownloadUrl(req.getFileDownloadUrl());
        t.setRecerSysCode(req.getRecerSysCode());
        t.setInfver(req.getInfver());
        t.setOpterType(req.getOpterType());
        t.setOpter(req.getOpter());
        t.setOpterName(req.getOpterName());
        t.setSignNo(req.getSignNo());
        t.setEncType(req.getEncType());
        if (req.getSm2PrivateKey() != null && !req.getSm2PrivateKey().contains("*")) {
            t.setSm2PrivateKey(req.getSm2PrivateKey());
        }
        if (req.getSm2PublicKey() != null) {
            t.setSm2PublicKey(req.getSm2PublicKey());
        }
        if (req.getMockEnabled() != null) {
            t.setMockEnabled(req.getMockEnabled());
        }
        t.setContact(req.getContact());
        t.setPhone(req.getPhone());
        t.setAddress(req.getAddress());
        tenantService.updateById(t);
        return R.ok();
    }

    private String mask(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return "******";
    }
}
