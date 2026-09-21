package com.yb.hi.controller.outpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.entity.outpatient.HisPatientChangeLog;
import com.yb.hi.entity.outpatient.HisPatientInsu;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.outpatient.HisPatientInsuService;
import com.yb.hi.service.outpatient.HisPatientService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 患者档案(建档/读卡/查询)接口
 */
@RestController
@RequestMapping("/api/his/patient")
public class HisPatientController {

    private final HisPatientService service;
    private final HisPatientInsuService insuService;

    public HisPatientController(HisPatientService service, HisPatientInsuService insuService) {
        this.service = service;
        this.insuService = insuService;
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

    /** 查询患者档案修改记录(字段级留痕, 时间倒序) */
    @GetMapping("/{id}/change-logs")
    public R<List<HisPatientChangeLog>> changeLogs(@PathVariable Long id) {
        return R.ok(service.listChangeLogs(id));
    }

    /** 查询患者医保参保信息记录(读卡完整记录, 一人可多条) */
    @GetMapping("/{id}/insu")
    public R<List<HisPatientInsu>> insuList(@PathVariable Long id) {
        return R.ok(insuService.listByPatient(id));
    }

    /** 读卡同步: 模拟 1101 人员信息获取返回完整参保信息列表并覆盖保存 */
    @PostMapping("/{id}/insu/read-sync")
    public R<List<HisPatientInsu>> insuReadSync(@PathVariable Long id) {
        return R.ok(insuService.syncFromReadCard(id));
    }

    /** 删除一条参保信息记录 */
    @DeleteMapping("/insu/{insuId}")
    public R<Void> insuDelete(@PathVariable Long insuId) {
        insuService.removeById(insuId);
        return R.ok();
    }
}
