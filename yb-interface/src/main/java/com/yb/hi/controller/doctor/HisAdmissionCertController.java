package com.yb.hi.controller.doctor;

import com.yb.hi.dto.doctor.AdmissionCertReq;
import com.yb.hi.entity.doctor.HisAdmissionCert;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisAdmissionCertService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 住院证接口: 开具 / 查询就诊住院证 / 按患者查待入院有效证 / 作废 / 打印数据
 */
@RestController
@RequestMapping("/api/his/admission-cert")
public class HisAdmissionCertController {

    private final HisAdmissionCertService service;

    public HisAdmissionCertController(HisAdmissionCertService service) {
        this.service = service;
    }

    /** 开具住院证 */
    @PostMapping("/create")
    public R<HisAdmissionCert> create(@RequestBody AdmissionCertReq req) {
        return R.ok(service.create(req));
    }

    /** 查询某次就诊的住院证列表 */
    @GetMapping("/list")
    public R<List<HisAdmissionCert>> list(@RequestParam Long visitId) {
        return R.ok(service.listByVisit(visitId));
    }

    /** 按患者查待入院有效证(住院登记页持证入院选证用) */
    @GetMapping("/pending")
    public R<List<HisAdmissionCert>> pending(@RequestParam Long patientId) {
        return R.ok(service.listPendingByPatient(patientId));
    }

    /** 作废住院证 */
    @PostMapping("/cancel")
    public R<HisAdmissionCert> cancel(@RequestParam Long id) {
        return R.ok(service.cancel(id));
    }

    /** 住院证打印数据(证+患者+医院名) */
    @GetMapping("/print-data")
    public R<Map<String, Object>> printData(@RequestParam Long id) {
        return R.ok(service.printData(id));
    }
}
