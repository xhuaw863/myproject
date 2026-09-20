package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.mapper.SysTenantMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 租户(医院)服务
 * sys_tenant 为全局表, 查询不受租户插件过滤
 */
@Service
public class SysTenantService {

    /** 平台运营方租户登录码(超级管理员所属, 非真实医院, 不出现在医院列表) */
    public static final String PLATFORM_TENANT_CODE = "PLATFORM";

    private final SysTenantMapper sysTenantMapper;

    public SysTenantService(SysTenantMapper sysTenantMapper) {
        this.sysTenantMapper = sysTenantMapper;
    }

    public SysTenant getByCode(String tenantCode) {
        return sysTenantMapper.selectOne(new QueryWrapper<SysTenant>()
                .eq("tenant_code", tenantCode).last("LIMIT 1"));
    }

    public SysTenant getById(Long id) {
        return sysTenantMapper.selectById(id);
    }

    public List<SysTenant> listAll() {
        return sysTenantMapper.selectList(new QueryWrapper<SysTenant>().orderByAsc("id"));
    }

    /** 医院列表(供超级管理员管理): 排除平台运营方租户 */
    public List<SysTenant> listHospitals() {
        List<SysTenant> out = new ArrayList<>();
        for (SysTenant t : listAll()) {
            if (!PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                out.add(t);
            }
        }
        return out;
    }

    public boolean isPlatform(Long tenantId) {
        SysTenant t = getById(tenantId);
        return t != null && PLATFORM_TENANT_CODE.equals(t.getTenantCode());
    }

    /** 启用/停用医院 */
    public void setStatus(Long id, Integer status) {
        SysTenant t = getById(id);
        if (t == null) {
            throw new BizException("医院不存在");
        }
        if (PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
            throw new BizException(400, "平台运营方租户不可停用");
        }
        SysTenant u = new SysTenant();
        u.setId(id);
        u.setStatus(status);
        sysTenantMapper.updateById(u);
    }

    public boolean codeExists(String tenantCode) {
        return sysTenantMapper.selectCount(new QueryWrapper<SysTenant>()
                .eq("tenant_code", tenantCode)) > 0;
    }

    public void insert(SysTenant tenant) {
        if (codeExists(tenant.getTenantCode())) {
            throw new BizException("医院登录码已存在: " + tenant.getTenantCode());
        }
        sysTenantMapper.insert(tenant);
    }

    public void updateById(SysTenant tenant) {
        sysTenantMapper.updateById(tenant);
    }
}
