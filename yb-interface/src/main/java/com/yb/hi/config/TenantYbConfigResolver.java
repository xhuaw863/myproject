package com.yb.hi.config;

import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.platform.service.SysTenantService;
import org.springframework.stereotype.Component;

/**
 * 医保配置解析器
 * 合并全局默认(application.yml 的 yb.*) + 租户(sys_tenant) + 机构(sys_org)三层配置,
 * 优先级递增: 全局 < 租户 < 机构。机构层按当前登录用户 orgId 解析,
 * 使医共体内各定点机构以各自的 fixmedins_code/接口参数上报。
 */
@Component
public class TenantYbConfigResolver {

    private final YbConfig global;
    private final SysTenantService tenantService;
    private final SysOrgMapper orgMapper;

    public TenantYbConfigResolver(YbConfig global, SysTenantService tenantService, SysOrgMapper orgMapper) {
        this.global = global;
        this.tenantService = tenantService;
        this.orgMapper = orgMapper;
    }

    /** 解析当前请求的医保配置(机构取当前登录用户 orgId) */
    public YbRuntimeConfig resolve() {
        LoginUser u = UserContext.get();
        return resolve(u == null ? null : u.getOrgId());
    }

    /** 解析指定机构的医保配置: 全局默认 → 租户覆盖 → 机构覆盖 */
    public YbRuntimeConfig resolve(Long orgId) {
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
                c.setInsuplcAdmdvs(nvl(t.getInsuplcAdmdvs(), c.getInsuplcAdmdvs()));
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

        // 3) 机构覆盖(最具体; 医共体内各定点机构独立接口参数)
        if (orgId != null) {
            SysOrg o = orgMapper.selectById(orgId);
            if (o != null) {
                c.setApiUrl(nvl(o.getApiUrl(), c.getApiUrl()));
                c.setFileDownloadUrl(nvl(o.getFileDownloadUrl(), c.getFileDownloadUrl()));
                c.setFixmedinsCode(nvl(o.getFixmedinsCode(), c.getFixmedinsCode()));
                c.setFixmedinsName(nvl(o.getFixmedinsName(), c.getFixmedinsName()));
                c.setMdtrtareaAdmvs(nvl(o.getMdtrtareaAdmvs(), c.getMdtrtareaAdmvs()));
                c.setInsuplcAdmdvs(nvl(o.getInsuplcAdmdvs(), c.getInsuplcAdmdvs()));
                c.setRecerSysCode(nvl(o.getRecerSysCode(), c.getRecerSysCode()));
                c.setInfver(nvl(o.getInfver(), c.getInfver()));
                c.setOpterType(nvl(o.getOpterType(), c.getOpterType()));
                c.setOpter(nvl(o.getOpter(), c.getOpter()));
                c.setOpterName(nvl(o.getOpterName(), c.getOpterName()));
                c.setSignNo(nvl(o.getSignNo(), c.getSignNo()));
                c.setSm2PrivateKey(nvl(o.getSm2PrivateKey(), c.getSm2PrivateKey()));
                c.setSm2PublicKey(nvl(o.getSm2PublicKey(), c.getSm2PublicKey()));
                c.setEncType(nvl(o.getEncType(), c.getEncType()));
                if (o.getMockEnabled() != null) {
                    c.setMockEnabled(o.getMockEnabled() == 1);
                }
            }
        }
        return c;
    }

    private String nvl(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v;
    }
}
