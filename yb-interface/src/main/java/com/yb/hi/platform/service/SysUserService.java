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

    /**
     * 一员工一账号软校验: staff_id 非空时, 断言该职工未被其他有效账号绑定。
     * 采用软校验而非 DB 唯一索引: sys_user 为逻辑删除, 墓碑行仍占 staff_id,
     * MySQL 无过滤唯一索引会误拦"删除旧账号后重新绑定同一职工"的正当场景。
     */
    public void assertStaffNotBound(Long staffId, Long selfUserId) {
        if (staffId == null) {
            return;
        }
        // 在当前租户上下文内查(由 AuthInterceptor/createUser 预先 set), 租户插件自动隔离
        SysUser exist = sysUserMapper.selectOne(new QueryWrapper<SysUser>()
                .eq("staff_id", staffId).ne(selfUserId != null, "id", selfUserId)
                .last("LIMIT 1"));
        if (exist != null) {
            throw new BizException(400, "该职工已绑定账号 " + exist.getUsername() + ", 一个职工仅可对应一个账号");
        }
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
            assertStaffNotBound(staffId, null);
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
