package com.yb.hi.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.StdDictMaintainService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 标准字典维护接口(逐值 CRUD) —— 仅平台超级管理员可用。
 * 标准字典分散在多张 std_* 表, 各表列结构不同; 本接口按注册表元数据做通用增删改查:
 *  - GET  /{key}/columns     列元数据(动态表单)
 *  - GET  /{key}/page        分页列表(含 id)
 *  - GET  /{key}/row/{id}    单行完整数据
 *  - POST /{key}             新增(body=列名->值)
 *  - PUT  /{key}/{id}        修改
 *  - DELETE /{key}/{id}      删除
 * 浏览(只读)请走 StdDictQueryController(/api/std-dict/query)。
 */
@RestController
@RequestMapping("/api/std-dict/maintain")
public class StdDictMaintainController {

    private final StdDictMaintainService maintainService;

    public StdDictMaintainController(StdDictMaintainService maintainService) {
        this.maintainService = maintainService;
    }

    @GetMapping("/{key}/columns")
    public R<List<Map<String, Object>>> columns(@PathVariable String key) {
        requireSuper();
        return R.ok(maintainService.columns(key));
    }

    @GetMapping("/{key}/page")
    public R<Map<String, Object>> page(@PathVariable String key,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        requireSuper();
        return R.ok(maintainService.page(key, keyword, page, size));
    }

    @GetMapping("/{key}/row/{id}")
    public R<Map<String, Object>> row(@PathVariable String key, @PathVariable long id) {
        requireSuper();
        return R.ok(maintainService.row(key, id));
    }

    @PostMapping("/{key}")
    public R<Long> insert(@PathVariable String key, @RequestBody Map<String, Object> data) {
        requireSuper();
        return R.ok("新增成功", maintainService.insert(key, data));
    }

    @PutMapping("/{key}/{id}")
    public R<Void> update(@PathVariable String key, @PathVariable long id, @RequestBody Map<String, Object> data) {
        requireSuper();
        maintainService.update(key, id, data);
        return R.ok("修改成功", null);
    }

    @DeleteMapping("/{key}/{id}")
    public R<Void> delete(@PathVariable String key, @PathVariable long id) {
        requireSuper();
        maintainService.delete(key, id);
        return R.ok("删除成功", null);
    }

    /** 仅平台超级管理员可维护标准字典 */
    private void requireSuper() {
        LoginUser u = UserContext.get();
        if (u == null || !Roles.SUPER_ADMIN.equals(u.getRole())) {
            throw new BizException(403, "标准字典仅平台超级管理员可维护, 其它用户只能浏览");
        }
    }
}
