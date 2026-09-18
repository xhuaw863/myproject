package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.SysTenantService;
import org.springframework.web.bind.annotation.*;

/**
 * 租户(医院)信息与医保配置接口
 * 医院管理员维护本院信息与医保接口参数
 */
@RestController
@RequestMapping("/api/sys/tenant")
public class SysTenantController {

    private final SysTenantService tenantService;

    public SysTenantController(SysTenantService tenantService) {
        this.tenantService = tenantService;
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
