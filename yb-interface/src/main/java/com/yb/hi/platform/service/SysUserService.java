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

    public List<SysUser> list() {
        List<SysUser> users = sysUserMapper.selectList(new QueryWrapper<SysUser>().orderByAsc("id"));
        users.forEach(u -> u.setPassword(null));
        return users;
    }

    /**
     * 在指定租户下创建用户
     */
    public SysUser createUser(Long tenantId, String username, String rawPassword, String realName,
                              String role, Long staffId, Long deptId, String phone) {
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
            user.setPhone(phone);
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
