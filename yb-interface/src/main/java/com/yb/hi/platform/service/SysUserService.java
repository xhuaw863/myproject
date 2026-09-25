package com.yb.hi.platform.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户服务(按租户隔离)
 */
@Service
public class SysUserService {

    private final SysUserMapper sysUserMapper;

    public SysUserService(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    public SysUser getByUsername(String username) {
        return sysUserMapper.selectOne(new QueryWrapper<SysUser>()
                .eq("username", username).last("LIMIT 1"));
    }

    /** 可见用户列表; orgId 非空时仅返回该机构用户(非牵头机构由控制器强制本院) */
    public List<SysUser> list(Long orgId) {
        QueryWrapper<SysUser> q = new QueryWrapper<SysUser>()
                .eq(orgId != null, "org_id", orgId)
                .orderByAsc("id");
        List<SysUser> users = sysUserMapper.selectList(q);
        users.forEach(u -> u.setPassword(null));
        return users;
    }

    /**
     * 在指定租户下创建用户
     */
    public SysUser createUser(Long tenantId, String username, String rawPassword, String realName,
                              String role, Long staffId, Long deptId, Long orgId, Long roleId, String phone,
                              String deptScope) {
        Long prev = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            if (getByUsername(username) != null) {
                throw new BizException("账号已存在: " + username);
            }
            SysUser user = new SysUser();
            user.setUsername(username);
            user.setPassword(BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
            user.setRealName(realName);
            user.setRole(role);
            user.setStaffId(staffId);
            user.setDeptId(deptId);
            user.setOrgId(orgId);
            user.setRoleId(roleId);
            user.setPhone(phone);
            user.setDeptScope(deptScope);
            user.setStatus(1);
            sysUserMapper.insert(user);
            user.setPassword(null);
            return user;
        } finally {
            TenantContext.set(prev);
        }
    }

    public void resetPassword(Long userId, String rawPassword) {
        SysUser u = new SysUser();
        u.setId(userId);
        u.setPassword(BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
        sysUserMapper.updateById(u);
    }

    public void updateStatus(Long userId, Integer status) {
        SysUser u = new SysUser();
        u.setId(userId);
        u.setStatus(status);
        sysUserMapper.updateById(u);
    }

    public void update(SysUser user) {
        user.setPassword(null);
        sysUserMapper.updateById(user);
    }

    public void delete(Long userId) {
        sysUserMapper.deleteById(userId);
    }
}
