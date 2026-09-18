package com.yb.hi.platform.service;

import cn.hutool.crypto.digest.BCrypt;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.util.JwtUtil;
import com.yb.hi.platform.dto.LoginReq;
import com.yb.hi.platform.dto.LoginResp;
import com.yb.hi.platform.dto.TenantRegisterReq;
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
    private final JwtUtil jwtUtil;

    public AuthService(SysTenantService tenantService, SysUserService userService, JwtUtil jwtUtil) {
        this.tenantService = tenantService;
        this.userService = userService;
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
            LoginUser lu = new LoginUser();
            lu.setUserId(user.getId());
            lu.setTenantId(tenant.getId());
            lu.setUsername(user.getUsername());
            lu.setRealName(user.getRealName());
            lu.setRole(user.getRole());
            lu.setStaffId(user.getStaffId());
            lu.setDeptId(user.getDeptId());
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
            log.info("登录成功: tenant={}, user={}", tenant.getTenantCode(), user.getUsername());
            return resp;
        } finally {
            TenantContext.set(prev);
        }
    }

    /** 医院(租户)注册 + 创建管理员账号 */
    @Transactional(rollbackFor = Exception.class)
    public Long register(TenantRegisterReq req) {
        if (req == null || isBlank(req.getTenantCode()) || isBlank(req.getTenantName())) {
            throw new BizException(400, "医院登录码与名称不能为空");
        }
        if (isBlank(req.getAdminUsername()) || isBlank(req.getAdminPassword())) {
            throw new BizException(400, "管理员账号与密码不能为空");
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

        userService.createUser(tenant.getId(), req.getAdminUsername().trim(), req.getAdminPassword(),
                isBlank(req.getAdminName()) ? "管理员" : req.getAdminName(), Roles.ADMIN, null, null, req.getPhone());
        log.info("医院注册成功: id={}, code={}", tenant.getId(), tenant.getTenantCode());
        return tenant.getId();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
