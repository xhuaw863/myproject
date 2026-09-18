package com.yb.hi.config;

import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.SysTenantService;
import org.springframework.stereotype.Component;

/**
 * 租户级医保配置解析器
 * 合并全局默认(application.yml 的 yb.*)与当前租户(sys_tenant)配置, 租户值优先。
 */
@Component
public class TenantYbConfigResolver {

    private final YbConfig global;
    private final SysTenantService tenantService;

    public TenantYbConfigResolver(YbConfig global, SysTenantService tenantService) {
        this.global = global;
        this.tenantService = tenantService;
    }

    /** 解析当前请求所属租户的医保配置 */
    public YbRuntimeConfig resolve() {
        YbRuntimeConfig c = new YbRuntimeConfig();
        // 1) 全局默认
        c.setApiUrl(global.getApiUrl());
        c.setFileDownloadUrl(global.getFileDownloadUrl());
        c.setFixmedinsCode(global.getFixmedinsCode());
        c.setFixmedinsName(global.getFixmedinsName());
        c.setMdtrtareaAdmvs(global.getMdtrtareaAdmvs());
        c.setRecerSysCode(global.getRecerSysCode());
        c.setInfver(global.getInfver());
        c.setOpterType(global.getOpterType());
        c.setOpter(global.getOpter());
        c.setOpterName(global.getOpterName());
        c.setSignNo(global.getSignNo());
        c.setSm2PrivateKey(global.getSm2PrivateKey());
        c.setSm2PublicKey(global.getSm2PublicKey());
        c.setEncType(global.getEncType());
        c.setMockEnabled(global.isMockEnabled());

        // 2) 租户覆盖
        Long tid = TenantContext.get();
        if (tid != null) {
            SysTenant t = tenantService.getById(tid);
            if (t != null) {
                c.setApiUrl(nvl(t.getApiUrl(), c.getApiUrl()));
                c.setFileDownloadUrl(nvl(t.getFileDownloadUrl(), c.getFileDownloadUrl()));
                c.setFixmedinsCode(nvl(t.getFixmedinsCode(), c.getFixmedinsCode()));
                c.setFixmedinsName(nvl(t.getFixmedinsName(), c.getFixmedinsName()));
                c.setMdtrtareaAdmvs(nvl(t.getMdtrtareaAdmvs(), c.getMdtrtareaAdmvs()));
                c.setRecerSysCode(nvl(t.getRecerSysCode(), c.getRecerSysCode()));
                c.setInfver(nvl(t.getInfver(), c.getInfver()));
                c.setOpterType(nvl(t.getOpterType(), c.getOpterType()));
                c.setOpter(nvl(t.getOpter(), c.getOpter()));
                c.setOpterName(nvl(t.getOpterName(), c.getOpterName()));
                c.setSignNo(nvl(t.getSignNo(), c.getSignNo()));
                c.setSm2PrivateKey(nvl(t.getSm2PrivateKey(), c.getSm2PrivateKey()));
                c.setSm2PublicKey(nvl(t.getSm2PublicKey(), c.getSm2PublicKey()));
                c.setEncType(nvl(t.getEncType(), c.getEncType()));
                if (t.getMockEnabled() != null) {
                    c.setMockEnabled(t.getMockEnabled() == 1);
                }
            }
        }
        return c;
    }

    private String nvl(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v;
    }
}
