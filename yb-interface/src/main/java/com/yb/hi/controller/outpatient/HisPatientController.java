package com.yb.hi.controller.outpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.outpatient.HisPatientService;
import org.springframework.web.bind.annotation.*;

/**
 * 患者档案(建档/读卡/查询)接口
 */
@RestController
@RequestMapping("/api/his/patient")
public class HisPatientController {

    private final HisPatientService service;

    public HisPatientController(HisPatientService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public R<IPage<HisPatient>> page(@RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "15") long size,
                                     @RequestParam(required = false) String keyword) {
        return R.ok(service.pageQuery(page, size, keyword));
    }

    @GetMapping("/{id}")
    public R<HisPatient> get(@PathVariable Long id) {
        return R.ok(service.getById(id));
    }

    /** 读卡/建档查重: 按身份证查询已有档案 */
    @GetMapping("/by-idcard")
    public R<HisPatient> getByIdCard(@RequestParam String idCard) {
        return R.ok(service.getByIdCard(idCard));
    }

    @PostMapping
    public R<HisPatient> create(@RequestBody HisPatient e) {
        return R.ok(service.createPatient(e));
    }

    @PutMapping
    public R<Void> update(@RequestBody HisPatient e) {
        service.updatePatient(e);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.removeById(id);
        return R.ok();
    }
}
