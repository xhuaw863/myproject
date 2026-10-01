package com.yb.hi.controller;

import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.StdDictImportService;
import com.yb.hi.service.StdDictMaintainService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 供货商(企业)字典维护接口: std_supplier 虽为全局 L0 标准字典, 但作为医共体基础数据
 * 由牵头机构系统管理员维护(与"医共体字典"三目录同档守卫), 平台超级管理员亦可维护。
 * 复用通用标准字典维护服务(列白名单 + PreparedStatement), 字典键固定为 supplier。
 *  端点(相对 /api/supplier-dict):
 *   GET  /page          分页列表(keyword 支持名称/编码/拼音)
 *   GET  /row/{id}      单行完整数据
 *   POST /              新增(body=列名->值)
 *   PUT  /{id}          修改
 *   DELETE /{id}        删除
 *   GET  /import        从医保各目录去重全量重写(幂等)
 * 只读浏览(供目录企业远程下拉)仍走通用 /api/std-dict/query/supplier(所有登录用户可读)。
 */
@RestController
@RequestMapping("/api/supplier-dict")
public class SupplierDictController {

    private static final String KEY = "supplier";

    private final StdDictMaintainService maintainService;
    private final StdDictImportService importService;
    private final OrgAccessGuard guard;

    public SupplierDictController(StdDictMaintainService maintainService,
                                  StdDictImportService importService,
                                  OrgAccessGuard guard) {
        this.maintainService = maintainService;
        this.importService = importService;
        this.guard = guard;
    }

    @GetMapping("/columns")
    public R<List<Map<String, Object>>> columns() {
        requireCanMaintain();
        return R.ok(maintainService.columns(KEY));
    }

    @GetMapping("/page")
    public R<Map<String, Object>> page(@RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        requireCanMaintain();
        return R.ok(maintainService.page(KEY, keyword, page, size));
    }

    @GetMapping("/row/{id}")
    public R<Map<String, Object>> row(@PathVariable long id) {
        requireCanMaintain();
        return R.ok(maintainService.row(KEY, id));
    }

    @PostMapping
    public R<Long> insert(@RequestBody Map<String, Object> data) {
        requireCanMaintain();
        return R.ok("新增成功", maintainService.insert(KEY, data));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable long id, @RequestBody Map<String, Object> data) {
        requireCanMaintain();
        maintainService.update(KEY, id, data);
        return R.ok("修改成功", null);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        requireCanMaintain();
        maintainService.delete(KEY, id);
        return R.ok("删除成功", null);
    }

    @GetMapping("/import")
    public R<Map<String, Object>> importSupplier() {
        requireCanMaintain();
        return R.ok(importService.importOne(KEY));
    }

    /** 平台超级管理员或牵头机构系统管理员可维护; 其余只读(403)。 */
    private void requireCanMaintain() {
        LoginUser u = UserContext.get();
        if (u != null && u.hasRole(Roles.SUPER_ADMIN)) {
            return;
        }
        guard.requireLeadOrg("仅牵头机构系统管理员或平台超级管理员可维护供货商字典");
    }
}
