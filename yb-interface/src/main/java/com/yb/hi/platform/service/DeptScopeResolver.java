package com.yb.hi.platform.service;

import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 科室数据权限解析器(全系统唯一取数口径):
 * 授权科室范围 = staff_employment(任职事实, 按会话机构过滤)
 *              ∪ staff_dept_grant(临调授权, 按会话机构+今日生效)
 *              ∪ 存量兜底(sys_user.dept_id ∪ dept_scope, 仅限归属机构登录时; 无员工绑定的账号以其为主口径)
 * - 管理角色(ADMIN/ORG_ADMIN/SUPER_ADMIN)与牵头机构用户不受科室限制(isUnrestricted);
 * - 非归属机构(多点执业切换到分院)且该机构无任职/授权 → 返回空集(fail-closed, 防跨机构授权泄漏);
 * - 归属机构的存量兜底保证迁移期"零失权"; 待存量数据治理后可移除。
 * 口径按 userId 现查库(不入 JWT), 使管理员调整任职/授权即时生效。
 */
@Slf4j
@Component
public class DeptScopeResolver {

    private final StaffEmploymentService employmentService;
    private final SysUserMapper sysUserMapper;
    private final OrgAccessGuard guard;

    public DeptScopeResolver(StaffEmploymentService employmentService, SysUserMapper sysUserMapper,
                             OrgAccessGuard guard) {
        this.employmentService = employmentService;
        this.sysUserMapper = sysUserMapper;
        this.guard = guard;
    }

    /** 当前用户是否不受科室授权限制(管理员角色或牵头机构用户; 未登录视为受限) */
    public boolean isUnrestricted() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return false;
        }
        if (lu.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN, Roles.SUPER_ADMIN)) {
            return true;
        }
        return guard.isLead(lu);
    }

    /** 当前登录用户在本会话机构下的授权科室集合 */
    public Set<Long> currentAuthDeptIds() {
        LoginUser lu = UserContext.get();
        if (lu == null || lu.getUserId() == null) {
            return new HashSet<>();
        }
        SysUser u = sysUserMapper.selectById(lu.getUserId());
        if (u == null) {
            return new HashSet<>();
        }
        Long sessionOrg = lu.getOrgId() != null ? lu.getOrgId() : u.getOrgId();
        boolean atHomeOrg = sessionOrg != null && sessionOrg.equals(u.getOrgId());
        Set<Long> set = new HashSet<>();
        if (lu.getStaffId() != null && sessionOrg != null) {
            set.addAll(employmentService.employmentDeptIds(lu.getStaffId(), sessionOrg));
            set.addAll(employmentService.grantDeptIds(lu.getStaffId(), sessionOrg, LocalDate.now()));
        }
        // 存量兜底: 归属机构内沿用旧口径(sys_user.dept_id ∪ dept_scope), 保证迁移期零失权;
        // 未绑员工的账号(纯管理/测试类)无任职数据, 不限机构沿用旧口径
        if (atHomeOrg || lu.getStaffId() == null) {
            if (u.getDeptId() != null) {
                set.add(u.getDeptId());
            }
            if (StringUtils.hasText(u.getDeptScope())) {
                for (String s : u.getDeptScope().split(",")) {
                    String t = s.trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    try {
                        set.add(Long.parseLong(t));
                    } catch (NumberFormatException ignore) {
                        // 非法片段跳过
                    }
                }
            }
        }
        return set;
    }
}
