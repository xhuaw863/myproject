package com.yb.hi.controller.doctor;

import com.yb.hi.dto.doctor.PrescriptionReq;
import com.yb.hi.entity.doctor.HisPrescription;
import com.yb.hi.entity.doctor.HisPrescriptionItem;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisPrescriptionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 处方接口: 开方 / 查询就诊处方 / 查询明细
 */
@RestController
@RequestMapping("/api/his/prescription")
public class HisPrescriptionController {

    private final HisPrescriptionService service;

    public HisPrescriptionController(HisPrescriptionService service) {
        this.service = service;
    }

    /** 查询某次就诊的处方列表 */
    @GetMapping("/list")
    public R<List<HisPrescription>> list(@RequestParam Long visitId) {
        return R.ok(service.listByVisit(visitId));
    }

    /** 查询处方明细 */
    @GetMapping("/items")
    public R<List<HisPrescriptionItem>> items(@RequestParam Long prescriptionId) {
        return R.ok(service.listItems(prescriptionId));
    }

    /** 开处方 */
    @PostMapping("/create")
    public R<HisPrescription> create(@RequestBody PrescriptionReq req) {
        return R.ok(service.create(req));
    }
}
