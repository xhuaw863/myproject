package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.UserSaveReq;
import com.yb.hi.platform.entity.SysRole;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.service.SysRoleService;
import com.yb.hi.platform.service.SysUserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理接口(医院管理员)
 */
@RestController
@RequestMapping("/api/sys/user")
public class SysUserController {

    private final SysUserService userService;
    private final SysRoleService roleService;

    public SysUserController(SysUserService userService, SysRoleService roleService) {
        this.userService = userService;
        this.roleService = roleService;
    }

    @GetMapping("/list")
    public R<List<SysUser>> list() {
        return R.ok(userService.list());
    }

    @PostMapping
    public R<Void> create(@RequestBody UserSaveReq req) {
        if (req.getUsername() == null || req.getPassword() == null) {
            throw new BizException(400, "账号与密码不能为空");
        }
        String roleCode = resolveRoleCode(req);
        userService.createUser(UserContext.get().getTenantId(), req.getUsername(), req.getPassword(),
                req.getRealName(), roleCode,
                req.getStaffId(), req.getDeptId(), req.getOrgId(), req.getRoleId(), req.getPhone(),
                req.getDeptScope());
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody UserSaveReq req) {
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

    @PostMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable Long id, @RequestParam String password) {
        userService.resetPassword(id, password);
        return R.ok();
    }

    @PostMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        userService.updateStatus(id, status);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.ok();
    }
}
