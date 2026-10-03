package com.yb.hi.platform.controller;

import com.alibaba.excel.EasyExcel;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.ExportGuard;
import com.yb.hi.platform.dto.MenuNode;
import com.yb.hi.platform.dto.MenuSaveReq;
import com.yb.hi.platform.service.SysMenuService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

    /** 导出菜单列表(xlsx): 与页面查询同口径(关键字/类型/状态), 树摊平导出全部匹配行; 仅 ADMIN */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer menuType,
                       @RequestParam(required = false) Integer status,
                       HttpServletResponse resp) throws IOException {
        requireAdmin();
        Map<String, Object> data = menuService.exportRows(keyword, menuType, status);
        String fname = "菜单列表_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        ExportGuard.checkRows((java.util.Collection<?>) data.get("rows"), "菜单");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("菜单")
                .doWrite((List<List<Object>>) data.get("rows"));
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
        LoginUser u = UserContext.get();
        if (u == null || !u.hasAnyRole(Roles.ADMIN, Roles.SUPER_ADMIN)) {
            throw new BizException(403, "无权维护菜单");
        }
    }
}
