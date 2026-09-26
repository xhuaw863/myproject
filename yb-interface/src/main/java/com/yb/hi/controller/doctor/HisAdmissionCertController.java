package com.yb.hi.controller.doctor;

import com.yb.hi.dto.doctor.AdmissionCertReq;
import com.yb.hi.entity.doctor.HisAdmissionCert;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisAdmissionCertService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 住院证接口: 开具 / 查询就诊住院证 / 作废
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

    /** 作废住院证 */
    @PostMapping("/cancel")
    public R<HisAdmissionCert> cancel(@RequestParam Long id) {
        return R.ok(service.cancel(id));
    }
}
