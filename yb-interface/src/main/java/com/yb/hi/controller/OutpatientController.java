package com.yb.hi.controller;

import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.*;
import com.yb.hi.service.OutpatientService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门诊业务接口
 */
@RestController
@RequestMapping("/api/outpatient")
public class OutpatientController {

    private final OutpatientService outpatientService;

    public OutpatientController(OutpatientService outpatientService) {
        this.outpatientService = outpatientService;
    }

    /** 【2201】门诊挂号 */
    @PostMapping("/register")
    public YbResponse register(@RequestBody OutpatientRegisterReq req) {
        return outpatientService.register(req);
    }

    /** 【2202】门诊挂号撤销 */
    @PostMapping("/register-cancel")
    public YbResponse cancelRegister(@RequestBody OutpatientRegisterCancelReq req) {
        return outpatientService.cancelRegister(req);
    }

    /** 【2203】门诊就诊信息上传 */
    @PostMapping("/visit-info")
    public YbResponse uploadVisitInfo(@RequestBody VisitInfoUploadReq req) {
        return outpatientService.uploadVisitInfo(req.getMdtrtinfo(), req.getDiseinfo());
    }

    /** 【2204】门诊费用明细上传 */
    @PostMapping("/fee-detail")
    public YbResponse uploadFeeDetail(@RequestBody List<FeeDetailReq> feeDetails) {
        return outpatientService.uploadFeeDetail(feeDetails);
    }

    /** 【2206】门诊预结算 */
    @PostMapping("/pre-settlement")
    public YbResponse preSettlement(@RequestBody SettlementReq req) {
        return outpatientService.preSettlement(req);
    }

    /** 【2207】门诊结算 */
    @PostMapping("/settlement")
    public YbResponse settlement(@RequestBody SettlementReq req) {
        return outpatientService.settlement(req);
    }

    /** 【2208】门诊结算撤销 */
    @PostMapping("/settlement-cancel")
    public YbResponse cancelSettlement(@RequestBody SetlCancelReq req) {
        return outpatientService.cancelSettlement(req);
    }
}
