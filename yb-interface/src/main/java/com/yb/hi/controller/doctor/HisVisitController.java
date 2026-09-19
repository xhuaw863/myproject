package com.yb.hi.controller.doctor;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.doctor.VisitFinishReq;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisDiagnosisService;
import com.yb.hi.service.doctor.HisMedicalRecordService;
import com.yb.hi.service.doctor.HisOrderService;
import com.yb.hi.service.doctor.HisPrescriptionService;
import com.yb.hi.service.doctor.HisVisitService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 医生站就诊接口: 候诊队列 / 就诊详情 / 接诊 / 完成接诊(病历+诊断+2203)
 */
@RestController
@RequestMapping("/api/his/visit")
public class HisVisitController {

    private final HisVisitService visitService;
    private final HisDiagnosisService diagnosisService;
    private final HisMedicalRecordService medicalRecordService;
    private final HisPrescriptionService prescriptionService;
    private final HisOrderService orderService;

    public HisVisitController(HisVisitService visitService, HisDiagnosisService diagnosisService,
                              HisMedicalRecordService medicalRecordService, HisPrescriptionService prescriptionService,
                              HisOrderService orderService) {
        this.visitService = visitService;
        this.diagnosisService = diagnosisService;
        this.medicalRecordService = medicalRecordService;
        this.prescriptionService = prescriptionService;
        this.orderService = orderService;
    }

    /** 候诊/就诊队列 */
    @GetMapping("/queue")
    public R<IPage<HisVisit>> queue(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
                                    @RequestParam(required = false) Integer visitStatus,
                                    @RequestParam(required = false) Long deptId,
                                    @RequestParam(required = false) Long staffId,
                                    @RequestParam(required = false) String keyword) {
        return R.ok(visitService.queuePage(page, size, workDate, visitStatus, deptId, staffId, keyword));
    }

    /** 就诊详情(主表+病历+诊断+处方+检查单) */
    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam Long id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("visit", visitService.getById(id));
        data.put("record", medicalRecordService.getByVisit(id));
        data.put("diagnoses", diagnosisService.listByVisit(id));
        data.put("prescriptions", prescriptionService.listByVisit(id));
        data.put("orders", orderService.listByVisit(id));
        return R.ok(data);
    }

    /** 接诊: 候诊->接诊中 */
    @PostMapping("/start")
    public R<HisVisit> start(@RequestParam Long id) {
        return R.ok(visitService.startVisit(id));
    }

    /** 完成接诊: 保存病历+诊断, 就诊中->已完成(待收费), 上传2203 */
    @PostMapping("/finish")
    public R<HisVisit> finish(@RequestBody VisitFinishReq req) {
        return R.ok(visitService.finishVisit(req));
    }
}
