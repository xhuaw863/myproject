package com.yb.hi.controller.basedata;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.basedata.AreaCode;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.basedata.AreaCodeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 行政区划管理接口(area_code_2021, 国家统计局2021版 5级区划)。
 * 全局共享参考数据, 提供分页检索、级联下钻、祖先路径、级别统计与增删改。
 */
@RestController
@RequestMapping("/api/his/area")
public class AreaCodeController {

    private final AreaCodeService service;

    public AreaCodeController(AreaCodeService service) {
        this.service = service;
    }

    /** 分页检索: keyword(名称/代码模糊) + level(1-5) + pcode(下钻父级) */
    @GetMapping("/page")
    public R<IPage<AreaCode>> page(@RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "20") long size,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) Integer level,
                                   @RequestParam(required = false) Long pcode) {
        return R.ok(service.pageQuery(page, size, keyword, level, pcode));
    }

    /** 祖先路径(根→当前节点), 供面包屑展示 */
    @GetMapping("/path")
    public R<List<AreaCode>> path(@RequestParam Long code) {
        return R.ok(service.path(code));
    }

    /** 各级别数量统计 */
    @GetMapping("/stats")
    public R<List<Map<String, Object>>> stats() {
        return R.ok(service.levelStats());
    }

    /** 新增(仅平台超管: 行政区划为基础字典) */
    @PostMapping
    public R<Void> create(@RequestBody AreaCode e) {
        requireSuper();
        service.createArea(e);
        return R.ok();
    }

    /** 修改(代码为主键不可改; 仅平台超管) */
    @PutMapping
    public R<Void> update(@RequestBody AreaCode e) {
        requireSuper();
        service.updateArea(e);
        return R.ok();
    }

    /** 删除(存在下级时禁止; 仅平台超管) */
    @DeleteMapping("/{code}")
    public R<Void> delete(@PathVariable Long code) {
        requireSuper();
        service.deleteArea(code);
        return R.ok();
    }

    /** 仅平台超级管理员可维护行政区划(基础字典); 读取接口(page/path/stats)对所有登录用户开放, 供地址级联/机构区划下拉 */
    private void requireSuper() {
        LoginUser u = UserContext.get();
        if (u == null || !Roles.SUPER_ADMIN.equals(u.getRole())) {
            throw new BizException(403, "行政区划为基础字典, 仅平台超级管理员可维护");
        }
    }
}
