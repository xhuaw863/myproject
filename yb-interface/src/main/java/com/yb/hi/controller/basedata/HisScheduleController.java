package com.yb.hi.controller.basedata;

import com.yb.hi.entity.basedata.HisSchedule;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.basedata.HisScheduleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 排班号源管理接口
 */
@RestController
@RequestMapping("/api/his/schedule")
public class HisScheduleController {

    private final HisScheduleService service;

    public HisScheduleController(HisScheduleService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public R<List<HisSchedule>> list(@RequestParam(required = false) Long deptId,
                                     @RequestParam(required = false) Long staffId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return R.ok(service.listByFilter(deptId, staffId, from, to));
    }

    @PostMapping
    public R<Void> create(@RequestBody HisSchedule e) {
        service.save(e);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody HisSchedule e) {
        service.updateById(e);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.removeById(id);
        return R.ok();
    }
}
