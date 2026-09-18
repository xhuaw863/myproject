package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.mapper.SysTenantMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 租户(医院)服务
 * sys_tenant 为全局表, 查询不受租户插件过滤
 */
@Service
public class SysTenantService {

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
