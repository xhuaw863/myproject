package com.yb.hi.platform.service;

import cn.hutool.crypto.digest.BCrypt;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.framework.util.JwtUtil;
import com.yb.hi.platform.dto.LoginReq;
import com.yb.hi.platform.dto.LoginResp;
import com.yb.hi.platform.dto.OrgOption;
import com.yb.hi.platform.dto.OrgSaveReq;
import com.yb.hi.platform.dto.TenantRegisterReq;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.entity.SysRole;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.entity.SysUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
    private final SysUserOrgService userOrgService;
    private final SysUserRoleService userRoleService;

    public AuthService(SysTenantService tenantService, SysUserService userService,
                       SysRoleService roleService, SysOrgService orgService, JwtUtil jwtUtil,
                       SysUserOrgService userOrgService, SysUserRoleService userRoleService) {
        this.tenantService = tenantService;
        this.userService = userService;
        this.roleService = roleService;
        this.orgService = orgService;
        this.jwtUtil = jwtUtil;
        this.userOrgService = userOrgService;
        this.userRoleService = userRoleService;
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
            // 多点执业: 归属机构为默认可登录机构; 解析本医共体内可登录机构集
            Long homeOrgId = user.getOrgId();
            List<SysOrg> allowed = userOrgService.resolveAllowedOrgs(user.getId(), homeOrgId);
            Long sessionOrgId = homeOrgId;
            if (req.getOrgId() != null) {
                if (!containsOrg(allowed, req.getOrgId())) {
                    throw new BizException(403, "无权登录该机构");
                }
                sessionOrgId = req.getOrgId();
            }
            LoginResp resp = buildLoginResp(user, tenant, sessionOrgId, homeOrgId, allowed);
            log.info("登录成功: tenant={}, user={}, org={}", tenant.getTenantCode(), user.getUsername(), sessionOrgId);
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
            // 默认县级牵头机构: 让新医院开通即有机构归属, 无需等待重启迁移
            OrgSaveReq org = new OrgSaveReq();
            org.setOrgCode(tenant.getTenantCode());
            org.setOrgName(tenant.getTenantName());
            org.setOrgLevel(1);
            org.setIsLead(1);
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

    /**
     * 切换当前登录账号的活动机构(多点执业): 校验目标机构在可登录范围内, 重新签发令牌。
     * 令牌身份取自当前 UserContext(拦截器已按 JWT 设置 TenantContext)。
     */
    public LoginResp switchOrg(Long orgId) {
        LoginUser cur = UserContext.get();
        if (cur == null) {
            throw new BizException(401, "未登录");
        }
        if (orgId == null) {
            throw new BizException(400, "目标机构不能为空");
        }
        Long prev = TenantContext.get();
        try {
            TenantContext.set(cur.getTenantId());
            SysTenant tenant = tenantService.getById(cur.getTenantId());
            SysUser user = userService.getByUsername(cur.getUsername());
            if (tenant == null || user == null) {
                throw new BizException("用户或医院不存在");
            }
            Long homeOrgId = user.getOrgId();
            List<SysOrg> allowed = userOrgService.resolveAllowedOrgs(user.getId(), homeOrgId);
            if (!containsOrg(allowed, orgId)) {
                throw new BizException(403, "无权登录该机构");
            }
            LoginResp resp = buildLoginResp(user, tenant, orgId, homeOrgId, allowed);
            log.info("切换机构: user={}, org={}", user.getUsername(), orgId);
            return resp;
        } finally {
            TenantContext.set(prev);
        }
    }

    /** 组装登录响应(含令牌): 登录与切换机构共用 */
    private LoginResp buildLoginResp(SysUser user, SysTenant tenant, Long sessionOrgId, Long homeOrgId, List<SysOrg> allowed) {
        SysRole role = resolveRole(user);
        Long roleId = role == null ? null : role.getId();
        // 多角色(医共体一人多角色): 关联表集合优先, 主角色置首用于显示; 无关联行时回落单角色
        List<SysRole> roles = resolveRoles(user);
        String roleName = "";
        List<String> roleCodes = new ArrayList<>();
        List<Long> roleIds = new ArrayList<>();
        for (SysRole r : roles) {
            roleName = roleName.isEmpty() ? r.getRoleName() : roleName + " / " + r.getRoleName();
            if (r.getRoleCode() != null && !roleCodes.contains(r.getRoleCode())) {
                roleCodes.add(r.getRoleCode());
            }
            if (r.getId() != null && !roleIds.contains(r.getId())) {
                roleIds.add(r.getId());
            }
        }
        if (roleName.isEmpty()) {
            roleName = role != null ? role.getRoleName() : user.getRole();
        }
        SysOrg sessionOrg = sessionOrgId == null ? null : orgService.getById(sessionOrgId);
        String orgName = sessionOrg == null ? null : sessionOrg.getOrgName();
        boolean leadOrg = sessionOrg != null && sessionOrg.getIsLead() != null && sessionOrg.getIsLead() == 1;

        LoginUser lu = new LoginUser();
        lu.setUserId(user.getId());
        lu.setTenantId(tenant.getId());
        lu.setUsername(user.getUsername());
        lu.setRealName(user.getRealName());
        lu.setRole(user.getRole());
        lu.setStaffId(user.getStaffId());
        lu.setDeptId(user.getDeptId());
        lu.setOrgId(sessionOrgId);
        lu.setRoleId(roleId);
        lu.setRoles(roleCodes);
        lu.setRoleIds(roleIds);
        lu.setLeadOrg(leadOrg);
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
        resp.setOrgId(sessionOrgId);
        resp.setRoleId(roleId);
        resp.setOrgName(orgName);
        resp.setLeadOrg(leadOrg);
        resp.setRoleName(roleName);
        resp.setRoles(roleCodes);
        resp.setHomeOrgId(homeOrgId);
        resp.setAllowedOrgs(toOptions(allowed, homeOrgId));
        return resp;
    }

    /** 解析主角色(兼容字段): 优先 user.roleId, 为空则按 role 字符串回退匹配 */
    private SysRole resolveRole(SysUser user) {
        SysRole role = null;
        if (user.getRoleId() != null) {
            role = roleService.getById(user.getRoleId());
        }
        if (role == null) {
            role = roleService.findByCode(user.getRole());
        }
        return role;
    }

    /**
     * 解析全部角色(一人多角色): sys_user_role 关联集合优先, 主角色置首保显示顺序;
     * 无关联行时回落 {@link #resolveRole} 单角色(存量未回填/异常数据兜底)。
     */
    private List<SysRole> resolveRoles(SysUser user) {
        List<SysRole> out = new ArrayList<>();
        SysRole primary = resolveRole(user);
        if (primary != null) {
            out.add(primary);
        }
        for (Long rid : userRoleService.listRoleIds(user.getId())) {
            if (rid == null || (primary != null && rid.equals(primary.getId()))) {
                continue;
            }
            SysRole r = roleService.getById(rid);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private boolean containsOrg(List<SysOrg> orgs, Long orgId) {
        if (orgs == null || orgId == null) {
            return false;
        }
        for (SysOrg o : orgs) {
            if (orgId.equals(o.getId())) {
                return true;
            }
        }
        return false;
    }

    private List<OrgOption> toOptions(List<SysOrg> orgs, Long homeOrgId) {
        List<OrgOption> list = new ArrayList<>();
        if (orgs == null) {
            return list;
        }
        for (SysOrg o : orgs) {
            OrgOption op = new OrgOption();
            op.setOrgId(o.getId());
            op.setOrgName(o.getOrgName());
            op.setOrgLevel(o.getOrgLevel());
            op.setHome(homeOrgId != null && homeOrgId.equals(o.getId()));
            list.add(op);
        }
        return list;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
