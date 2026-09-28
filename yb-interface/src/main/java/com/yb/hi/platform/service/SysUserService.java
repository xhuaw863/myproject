package com.yb.hi.platform.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 服务端分页查询用户(避免一次拉全量): orgIds 非空按机构集合过滤(空=全部);
     * keyword 命中 账号/姓名/电话 任一; status 精确; roleCode 命中"主角色列 或 关联表该角色用户集"(兼容多角色)。
     * 返回前 password 置空; 调用方(控制器)负责非牵头机构 orgIds 收敛与 loginOrgIds/roleIds 富化。
     */
    public IPage<SysUser> pageQuery(Collection<Long> orgIds, String keyword, String roleCode, Long filterRoleId,
                                    Integer status, long page, long size) {
        QueryWrapper<SysUser> q = new QueryWrapper<SysUser>();
        if (orgIds != null && !orgIds.isEmpty()) {
            q.in("org_id", orgIds);
        }
        String kw = keyword == null ? null : keyword.trim();
        if (kw != null && !kw.isEmpty()) {
            final String like = kw;
            q.and(w -> w.like("username", like).or().like("real_name", like).or().like("phone", like));
        }
        if (status != null) {
            q.eq("status", status);
        }
        if (roleCode != null && !roleCode.isEmpty()) {
            final String rc = roleCode;
            if (filterRoleId != null) {
                // filterRoleId 为 DB 主键(Long), 拼接子查询安全; 跨租户 user_id 由外层 sys_user 租户过滤兵底
                final String sub = "SELECT user_id FROM sys_user_role WHERE role_id = " + filterRoleId;
                q.and(w -> w.eq("role", rc).or().inSql(true, "id", sub));
            } else {
                q.eq("role", rc);
            }
        }
        q.orderByAsc("id");
        IPage<SysUser> p = sysUserMapper.selectPage(new Page<SysUser>(page, size), q);
        p.getRecords().forEach(u -> u.setPassword(null));
        return p;
    }

    /** 按机构统计启用账号数(左树角标数据源): restrictOrgIds 非空限定机构集合; status=1 分组计数 */
    public Map<Long, Integer> countEnabledByOrg(Collection<Long> restrictOrgIds) {
        QueryWrapper<SysUser> q = new QueryWrapper<SysUser>()
                .select("org_id AS orgId", "COUNT(*) AS cnt")
                .eq("status", 1);
        if (restrictOrgIds != null && !restrictOrgIds.isEmpty()) {
            q.in("org_id", restrictOrgIds);
        }
        q.groupBy("org_id");
        Map<Long, Integer> m = new LinkedHashMap<>();
        for (Map<String, Object> row : sysUserMapper.selectMaps(q)) {
            Object oid = row.get("orgId");
            Object c = row.get("cnt");
            if (oid != null) {
                m.put(((Number) oid).longValue(), c == null ? 0 : ((Number) c).intValue());
            }
        }
        return m;
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
