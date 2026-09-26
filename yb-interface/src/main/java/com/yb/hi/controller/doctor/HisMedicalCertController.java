package com.yb.hi.controller.doctor;

import com.yb.hi.dto.doctor.MedicalCertReq;
import com.yb.hi.entity.doctor.HisMedicalCert;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisMedicalCertService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 诊断证明接口: 开具证明 / 查询就诊诊断证明
 */
@RestController
@RequestMapping("/api/his/medical-cert")
public class HisMedicalCertController {

    private final HisMedicalCertService service;

    public HisMedicalCertController(HisMedicalCertService service) {
        this.service = service;
    }

    /** 开具诊断证明 */
    @PostMapping("/create")
    public R<HisMedicalCert> create(@RequestBody MedicalCertReq req) {
        return R.ok(service.create(req));
    }

    /** 查询某次就诊的诊断证明列表 */
    @GetMapping("/list")
    public R<List<HisMedicalCert>> list(@RequestParam Long visitId) {
        return R.ok(service.listByVisit(visitId));
    }
}
