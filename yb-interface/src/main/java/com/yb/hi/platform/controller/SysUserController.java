package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.UserSaveReq;
import com.yb.hi.platform.entity.SysRole;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SysRoleService;
import com.yb.hi.platform.service.SysUserOrgService;
import com.yb.hi.platform.service.SysUserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户管理接口(医院管理员)
 * 读: 非牵头机构强制本院(scopeOrgId); 写: 仅牵头机构管理员(requireLeadWrite)。
 */
@RestController
@RequestMapping("/api/sys/user")
public class SysUserController {

    private final SysUserService userService;
    private final SysRoleService roleService;
    private final OrgAccessGuard guard;
    private final SysUserOrgService userOrgService;

    public SysUserController(SysUserService userService, SysRoleService roleService, OrgAccessGuard guard,
                             SysUserOrgService userOrgService) {
        this.userService = userService;
        this.roleService = roleService;
        this.guard = guard;
        this.userOrgService = userOrgService;
    }

    @GetMapping("/list")
    public R<List<SysUser>> list(@RequestParam(required = false) Long orgId) {
        List<SysUser> users = userService.list(guard.scopeOrgId(orgId));
        users.forEach(u -> u.setLoginOrgIds(userOrgService.allowedOrgIds(u.getId(), u.getOrgId())));
        return R.ok(users);
    }

    @PostMapping
    public R<Void> create(@RequestBody UserSaveReq req) {
        guard.requireLeadWrite();
        if (req.getUsername() == null || req.getPassword() == null) {
            throw new BizException(400, "账号与密码不能为空");
        }
        String roleCode = resolveRoleCode(req);
        SysUser created = userService.createUser(UserContext.get().getTenantId(), req.getUsername(), req.getPassword(),
                req.getRealName(), roleCode,
                req.getStaffId(), req.getDeptId(), req.getOrgId(), req.getRoleId(), req.getPhone(),
                req.getDeptScope());
        userOrgService.setLoginOrgs(created.getId(), created.getOrgId(), req.getLoginOrgIds());
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody UserSaveReq req) {
        guard.requireLeadWrite();
        SysUser u = new SysUser();
        u.setId(req.getId());
        u.setRealName(req.getRealName());
        u.setRole(resolveRoleCode(req));
        u.setStaffId(req.getStaffId());
        u.setDeptId(req.getDeptId());
        u.setOrgId(req.getOrgId());
        u.setRoleId(req.getRoleId());
        u.setPhone(req.getPhone());
        u.setDeptScope(req.getDeptScope());
        u.setStatus(req.getStatus());
        userService.update(u);
        if (req.getLoginOrgIds() != null) {
            userOrgService.setLoginOrgs(req.getId(), req.getOrgId(), req.getLoginOrgIds());
        }
        return R.ok();
    }

    /** 优先按 roleId 取权威角色编码; 无 roleId 时回退 role 字符串(默认 DOCTOR) */
    private String resolveRoleCode(UserSaveReq req) {
        if (req.getRoleId() != null) {
            SysRole r = roleService.getById(req.getRoleId());
            if (r != null) {
                return r.getRoleCode();
            }
        }
        return req.getRole() == null ? "DOCTOR" : req.getRole();
    }

    /** 重置密码: 改 POST body 传新密码, 避免明文密码落入 access/应用日志(#4); 后端兜长度底线 */
    @PostMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        guard.requireLeadWrite();
        String password = body == null ? null : body.get("password");
        if (password == null || password.length() < 6) {
            throw new BizException(400, "密码至少6位");
        }
        userService.resetPassword(id, password);
        return R.ok();
    }

    @PostMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        guard.requireLeadWrite();
        userService.updateStatus(id, status);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        guard.requireLeadWrite();
        /* 禁删当前登录账号: 防牵头管理员误删自己导致无人可维护用户(#3) */
        if (id != null && id.equals(UserContext.userId())) {
            throw new BizException(400, "不能删除自己的账号, 请先由其他管理员交接");
        }
        userService.delete(id);
        return R.ok();
    }
}
