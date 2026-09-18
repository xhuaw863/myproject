package com.yb.hi.controller.basedata;

import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.basedata.HisDeptService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 科室管理接口
 */
@RestController
@RequestMapping("/api/his/dept")
public class HisDeptController {

    private final HisDeptService service;

    public HisDeptController(HisDeptService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public R<List<HisDept>> list() {
        return R.ok(service.listAll());
    }

    @GetMapping("/enabled")
    public R<List<HisDept>> enabled() {
        return R.ok(service.listEnabled());
    }

    @PostMapping
    public R<Void> create(@RequestBody HisDept e) {
        service.save(e);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody HisDept e) {
        service.updateById(e);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.removeById(id);
        return R.ok();
    }
}
