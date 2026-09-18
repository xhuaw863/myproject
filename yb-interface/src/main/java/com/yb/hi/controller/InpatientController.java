package com.yb.hi.controller;

import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.*;
import com.yb.hi.service.InpatientService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 住院业务接口
 */
@RestController
@RequestMapping("/api/inpatient")
public class InpatientController {

    private final InpatientService inpatientService;

    public InpatientController(InpatientService inpatientService) {
        this.inpatientService = inpatientService;
    }

    /** 【2401】入院办理 */
    @PostMapping("/admission")
    public YbResponse admission(@RequestBody AdmissionUploadReq req) {
        return inpatientService.admission(req.getMdtrtinfo(), req.getDiseinfo());
    }

    /** 【2301】住院费用明细上传 */
    @PostMapping("/fee-detail")
    public YbResponse uploadFeeDetail(@RequestBody List<FeeDetailReq> feeDetails) {
        return inpatientService.uploadFeeDetail(feeDetails);
    }

    /** 【2303】住院预结算 */
    @PostMapping("/pre-settlement")
    public YbResponse preSettlement(@RequestBody SettlementReq req) {
        return inpatientService.preSettlement(req);
    }

    /** 【2304】住院结算 */
    @PostMapping("/settlement")
    public YbResponse settlement(@RequestBody SettlementReq req) {
        return inpatientService.settlement(req);
    }

    /** 【2305】住院结算撤销 */
    @PostMapping("/settlement-cancel")
    public YbResponse cancelSettlement(@RequestBody SetlCancelReq req) {
        return inpatientService.cancelSettlement(req);
    }

    /** 【2402】出院办理 */
    @PostMapping("/discharge")
    public YbResponse discharge(@RequestBody DischargeUploadReq req) {
        return inpatientService.discharge(req.getDscginfo(), req.getDiseinfo());
    }
}
