package com.yb.hi.controller.basedata;

import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.basedata.HisStaffService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 职工管理接口
 */
@RestController
@RequestMapping("/api/his/staff")
public class HisStaffController {

    private final HisStaffService service;

    public HisStaffController(HisStaffService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public R<List<HisStaff>> list(@RequestParam(required = false) Long deptId,
                                  @RequestParam(required = false) String staffType,
                                  @RequestParam(required = false) String keyword) {
        return R.ok(service.listByFilter(deptId, staffType, keyword));
    }

    @GetMapping("/{id}")
    public R<HisStaff> get(@PathVariable Long id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    public R<Void> create(@RequestBody HisStaff e) {
        service.save(e);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody HisStaff e) {
        service.updateById(e);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.removeById(id);
        return R.ok();
    }
}
