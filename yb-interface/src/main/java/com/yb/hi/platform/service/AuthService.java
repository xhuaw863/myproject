package com.yb.hi.platform.service;

import cn.hutool.crypto.digest.BCrypt;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.util.JwtUtil;
import com.yb.hi.platform.dto.LoginReq;
import com.yb.hi.platform.dto.LoginResp;
import com.yb.hi.platform.dto.OrgSaveReq;
import com.yb.hi.platform.dto.TenantRegisterReq;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.entity.SysRole;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.entity.SysUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 鉴权服务: 登录 / 医院注册
 */
@Slf4j
@Service
public class AuthService {

    private final SysTenantService tenantService;
    private final SysUserService userService;
    private final SysRoleService roleService;
    private final SysOrgService orgService;
    private final JwtUtil jwtUtil;

    public AuthService(SysTenantService tenantService, SysUserService userService,
                       SysRoleService roleService, SysOrgService orgService, JwtUtil jwtUtil) {
        this.tenantService = tenantService;
        this.userService = userService;
        this.roleService = roleService;
        this.orgService = orgService;
        this.jwtUtil = jwtUtil;
    }

    /** 登录 */
    public LoginResp login(LoginReq req) {
        if (req == null || isBlank(req.getTenantCode()) || isBlank(req.getUsername()) || isBlank(req.getPassword())) {
            throw new BizException(400, "医院码/账号/密码不能为空");
        }
        SysTenant tenant = tenantService.getByCode(req.getTenantCode().trim());
        if (tenant == null) {
            throw new BizException("医院不存在或登录码错误");
        }
        if (tenant.getStatus() != null && tenant.getStatus() == 0) {
            throw new BizException("该医院已停用");
        }
        Long prev = TenantContext.get();
        try {
            TenantContext.set(tenant.getId());
            SysUser user = userService.getByUsername(req.getUsername().trim());
            if (user == null || !BCrypt.checkpw(req.getPassword(), user.getPassword())) {
                throw new BizException("账号或密码错误");
            }
            if (user.getStatus() != null && user.getStatus() == 0) {
                throw new BizException("账号已停用");
            }
            // 解析权威角色: 优先 user.roleId, 为空则按 role 字符串回退匹配
            SysRole role = null;
            Long roleId = user.getRoleId();
            if (roleId != null) {
                role = roleService.getById(roleId);
            }
            if (role == null) {
                role = roleService.findByCode(user.getRole());
                roleId = role == null ? null : role.getId();
            }
            String roleName = role != null ? role.getRoleName() : user.getRole();
            Long orgId = user.getOrgId();
            String orgName = null;
            if (orgId != null) {
                SysOrg org = orgService.getById(orgId);
                orgName = org == null ? null : org.getOrgName();
            }

            LoginUser lu = new LoginUser();
            lu.setUserId(user.getId());
            lu.setTenantId(tenant.getId());
            lu.setUsername(user.getUsername());
            lu.setRealName(user.getRealName());
            lu.setRole(user.getRole());
            lu.setStaffId(user.getStaffId());
            lu.setDeptId(user.getDeptId());
            lu.setOrgId(orgId);
            lu.setRoleId(roleId);
            lu.setTenantName(tenant.getTenantName());

            LoginResp resp = new LoginResp();
            resp.setToken(jwtUtil.createToken(lu));
            resp.setUserId(user.getId());
            resp.setTenantId(tenant.getId());
            resp.setTenantCode(tenant.getTenantCode());
            resp.setTenantName(tenant.getTenantName());
            resp.setUsername(user.getUsername());
            resp.setRealName(user.getRealName());
            resp.setRole(user.getRole());
            resp.setStaffId(user.getStaffId());
            resp.setDeptId(user.getDeptId());
            resp.setOrgId(orgId);
            resp.setRoleId(roleId);
            resp.setOrgName(orgName);
            resp.setRoleName(roleName);
            log.info("登录成功: tenant={}, user={}", tenant.getTenantCode(), user.getUsername());
            return resp;
        } finally {
            TenantContext.set(prev);
        }
    }

    /**
     * 开通医院(租户): 建租户 + 医保配置 + 默认县级机构 + 初始管理员账号, 全程一个事务。
     * 由平台超级管理员在后台调用(非用户自助注册); 开通后即时可用, 无需重启。
     * 之后由该医院管理员登录自行维护科室/职工并分配用户权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long openHospital(TenantRegisterReq req) {
        if (req == null || isBlank(req.getTenantCode()) || isBlank(req.getTenantName())) {
            throw new BizException(400, "医院登录码与名称不能为空");
        }
        if (isBlank(req.getAdminUsername()) || isBlank(req.getAdminPassword())) {
            throw new BizException(400, "初始管理员账号与密码不能为空");
        }
        if (SysTenantService.PLATFORM_TENANT_CODE.equalsIgnoreCase(req.getTenantCode().trim())) {
            throw new BizException(400, "该登录码为平台保留, 请更换");
        }
        SysTenant tenant = new SysTenant();
        tenant.setTenantCode(req.getTenantCode().trim());
        tenant.setTenantName(req.getTenantName().trim());
        tenant.setContact(req.getContact());
        tenant.setPhone(req.getPhone());
        tenant.setAddress(req.getAddress());
        tenant.setFixmedinsCode(req.getFixmedinsCode());
        tenant.setFixmedinsName(isBlank(req.getFixmedinsName()) ? req.getTenantName() : req.getFixmedinsName());
        tenant.setMdtrtareaAdmvs(req.getMdtrtareaAdmvs());
        tenant.setInsuplcAdmdvs(req.getInsuplcAdmdvs());
        tenant.setApiUrl(req.getApiUrl());
        tenant.setRecerSysCode("HIS");
        tenant.setInfver("V1.0");
        tenant.setOpterType("2");
        tenant.setMockEnabled(req.getMockEnabled() == null ? 1 : req.getMockEnabled());
        tenant.setStatus(1);
        tenantService.insert(tenant);

        Long tenantId = tenant.getId();
        Long prev = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            // 默认县级(牵头)机构: 让新医院开通即有机构归属, 无需等待重启迁移
            OrgSaveReq org = new OrgSaveReq();
            org.setOrgCode(tenant.getTenantCode());
            org.setOrgName(tenant.getTenantName());
            org.setOrgLevel(1);
            org.setParentId(0L);
            org.setOrgType("A100");
            org.setFixmedinsCode(tenant.getFixmedinsCode());
            org.setFixmedinsName(tenant.getFixmedinsName());
            org.setAdmvsCode(tenant.getMdtrtareaAdmvs());
            org.setLeader(tenant.getContact());
            org.setPhone(tenant.getPhone());
            org.setAddress(tenant.getAddress());
            org.setSortNo(0);
            org.setStatus(1);
            Long orgId = orgService.create(org);

            // 初始管理员账号(绑定默认机构, 授全局 ADMIN 角色)
            SysRole adminRole = roleService.findByCode(Roles.ADMIN);
            Long adminRoleId = adminRole == null ? null : adminRole.getId();
            userService.createUser(tenantId, req.getAdminUsername().trim(), req.getAdminPassword(),
                    isBlank(req.getAdminName()) ? "管理员" : req.getAdminName(), Roles.ADMIN, null, null,
                    orgId, adminRoleId, req.getPhone(), null);
        } finally {
            TenantContext.set(prev);
        }
        log.info("医院开通成功: id={}, code={}, 管理员={}", tenantId, tenant.getTenantCode(), req.getAdminUsername());
        return tenantId;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
