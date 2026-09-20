package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.MenuNode;
import com.yb.hi.platform.dto.MenuSaveReq;
import com.yb.hi.platform.service.SysMenuService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 菜单维护接口(全局真源)。读取开放(供角色授权树); 增删改仅 ADMIN/SUPER_ADMIN。
 */
@RestController
@RequestMapping("/api/sys/menu")
public class SysMenuController {

    private final SysMenuService menuService;

    public SysMenuController(SysMenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/tree")
    public R<List<MenuNode>> tree() {
        return R.ok(menuService.tree());
    }

    @PostMapping
    public R<Long> create(@RequestBody MenuSaveReq req) {
        requireAdmin();
        return R.ok(menuService.create(req));
    }

    @PutMapping
    public R<Void> update(@RequestBody MenuSaveReq req) {
        requireAdmin();
        menuService.update(req);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        requireAdmin();
        menuService.delete(id);
        return R.ok();
    }

    private void requireAdmin() {
        String role = UserContext.get() == null ? null : UserContext.get().getRole();
        if (!Roles.ADMIN.equals(role) && !Roles.SUPER_ADMIN.equals(role)) {
            throw new BizException(403, "无权维护菜单");
        }
    }
}
