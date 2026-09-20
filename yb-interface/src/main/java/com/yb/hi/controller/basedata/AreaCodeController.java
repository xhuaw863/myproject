package com.yb.hi.controller.basedata;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.basedata.AreaCode;
import com.yb.hi.framework.common.R;
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

    /** 新增 */
    @PostMapping
    public R<Void> create(@RequestBody AreaCode e) {
        service.createArea(e);
        return R.ok();
    }

    /** 修改(代码为主键不可改) */
    @PutMapping
    public R<Void> update(@RequestBody AreaCode e) {
        service.updateArea(e);
        return R.ok();
    }

    /** 删除(存在下级时禁止) */
    @DeleteMapping("/{code}")
    public R<Void> delete(@PathVariable Long code) {
        service.deleteArea(code);
        return R.ok();
    }
}
