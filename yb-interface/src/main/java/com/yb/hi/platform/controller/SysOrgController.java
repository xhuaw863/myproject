package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.OrgNode;
import com.yb.hi.platform.dto.OrgSaveReq;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.service.SysOrgService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 医共体机构维护接口(县/乡/村三级树)。
 * 读取对所有已登录用户开放(供归属机构下拉); 增删改仅 ADMIN/SUPER_ADMIN。
 */
@RestController
@RequestMapping("/api/sys/org")
public class SysOrgController {

    private final SysOrgService orgService;

    public SysOrgController(SysOrgService orgService) {
        this.orgService = orgService;
    }

    @GetMapping("/tree")
    public R<List<OrgNode>> tree() {
        return R.ok(orgService.tree());
    }

    @GetMapping("/list")
    public R<List<SysOrg>> list(@RequestParam(required = false) Long parentId) {
        return R.ok(parentId == null ? orgService.listAll() : orgService.listByParent(parentId));
    }

    @PostMapping
    public R<Long> create(@RequestBody OrgSaveReq req) {
        requireAdmin();
        return R.ok(orgService.create(req));
    }

    @PutMapping
    public R<Void> update(@RequestBody OrgSaveReq req) {
        requireAdmin();
        orgService.update(req);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        requireAdmin();
        orgService.delete(id);
        return R.ok();
    }

    private void requireAdmin() {
        String role = UserContext.get() == null ? null : UserContext.get().getRole();
        if (!Roles.ADMIN.equals(role) && !Roles.SUPER_ADMIN.equals(role)) {
            throw new BizException(403, "无权维护机构信息");
        }
    }
}
