package com.yb.hi.platform.controller;

import com.alibaba.excel.EasyExcel;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.OrgNode;
import com.yb.hi.platform.dto.OrgSaveReq;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.service.SysOrgService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

    /** 导出机构列表(xlsx): 与页面查询同口径(关键字/级别/状态), 层级树摊平导出全部匹配行; 仅 ADMIN */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer orgLevel,
                       @RequestParam(required = false) Integer status,
                       HttpServletResponse resp) throws IOException {
        requireAdmin();
        Map<String, Object> data = orgService.exportRows(keyword, orgLevel, status);
        String fname = "机构列表_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("机构")
                .doWrite((List<List<Object>>) data.get("rows"));
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

    /** 当前登录机构的医保接口配置(本机构自有值 + 租户继承默认), 供"医院信息"页回显 */
    @GetMapping("/yb-config")
    public R<Map<String, Object>> ybConfig() {
        return R.ok(orgService.currentYbConfig());
    }

    /** 维护当前登录机构的医保接口配置(仅本机构, 不动医共体默认) */
    @PutMapping("/yb-config")
    public R<Void> updateYbConfig(@RequestBody OrgSaveReq req) {
        requireOrgAdmin();
        orgService.updateCurrentYbConfig(req);
        return R.ok();
    }

    /** 仅机构管理员(牵头 ADMIN / 非牵头 ORG_ADMIN / 超管)可维护本机构医保配置 */
    private void requireOrgAdmin() {
        String role = UserContext.get() == null ? null : UserContext.get().getRole();
        if (!Roles.ADMIN.equals(role) && !Roles.ORG_ADMIN.equals(role) && !Roles.SUPER_ADMIN.equals(role)) {
            throw new BizException(403, "仅机构管理员可维护本机构医保配置");
        }
    }

    private void requireAdmin() {
        String role = UserContext.get() == null ? null : UserContext.get().getRole();
        if (!Roles.ADMIN.equals(role) && !Roles.SUPER_ADMIN.equals(role)) {
            throw new BizException(403, "无权维护机构信息");
        }
    }
}
