package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.RoleMenuReq;
import com.yb.hi.platform.dto.RoleSaveReq;
import com.yb.hi.platform.entity.SysRole;
import com.yb.hi.platform.service.SysRoleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色维护接口。读取开放(供用户管理下拉); 增删改与授权仅 ADMIN/SUPER_ADMIN。
 */
@RestController
@RequestMapping("/api/sys/role")
public class SysRoleController {

    private final SysRoleService roleService;

    public SysRoleController(SysRoleService roleService) {
        this.roleService = roleService;
    }

    /** 可见角色: 全局预置 + 本租户自定义 */
    @GetMapping("/list")
    public R<List<SysRole>> list() {
        return R.ok(roleService.listVisible());
    }

    @PostMapping
    public R<Long> create(@RequestBody RoleSaveReq req) {
        requireAdmin();
        return R.ok(roleService.create(req));
    }

    @PutMapping
    public R<Void> update(@RequestBody RoleSaveReq req) {
        requireAdmin();
        roleService.update(req);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        requireAdmin();
        roleService.delete(id);
        return R.ok();
    }

    /** 角色已授权菜单ID集合 */
    @GetMapping("/{id}/menus")
    public R<List<Long>> menus(@PathVariable Long id) {
        requireAdmin();
        return R.ok(roleService.getMenuIds(id));
    }

    /** 为角色分配菜单 */
    @PutMapping("/{id}/menus")
    public R<Void> assignMenus(@PathVariable Long id, @RequestBody RoleMenuReq req) {
        requireAdmin();
        roleService.assignMenus(id, req == null ? null : req.getMenuIds());
        return R.ok();
    }

    private void requireAdmin() {
        String role = UserContext.get() == null ? null : UserContext.get().getRole();
        if (!Roles.ADMIN.equals(role) && !Roles.SUPER_ADMIN.equals(role)) {
            throw new BizException(403, "无权维护角色");
        }
    }
}
