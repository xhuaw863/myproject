package com.yb.hi.controller.doctor;

import com.yb.hi.dto.doctor.ConsultReq;
import com.yb.hi.entity.doctor.HisConsultRequest;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisConsultRequestService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会诊接口: 发起会诊 / 查询就诊会诊申请
 */
@RestController
@RequestMapping("/api/his/consult")
public class HisConsultRequestController {

    private final HisConsultRequestService service;

    public HisConsultRequestController(HisConsultRequestService service) {
        this.service = service;
    }

    /** 发起会诊申请 */
    @PostMapping("/create")
    public R<HisConsultRequest> create(@RequestBody ConsultReq req) {
        return R.ok(service.create(req));
    }

    /** 查询某次就诊的会诊申请列表 */
    @GetMapping("/list")
    public R<List<HisConsultRequest>> list(@RequestParam Long visitId) {
        return R.ok(service.listByVisit(visitId));
    }
}
