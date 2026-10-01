package com.yb.hi.controller.doctor;

import com.yb.hi.entity.doctor.HisDiseaseReport;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.doctor.HisDiagFreqService;
import com.yb.hi.service.doctor.HisDiagTemplateLinkService;
import com.yb.hi.service.doctor.HisDiseaseReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 门诊医生站诊断进阶接口(OP-B): 诊断助手聚合 / 疾病报卡 / 诊断→医嘱处方模板关联。
 */
@RestController
@RequestMapping("/api/his")
public class OutpDiagnosisExtController {

    private final HisDiagFreqService diagFreqService;
    private final HisDiseaseReportService diseaseReportService;
    private final HisDiagTemplateLinkService diagTemplateLinkService;

    public OutpDiagnosisExtController(HisDiagFreqService diagFreqService,
                                      HisDiseaseReportService diseaseReportService,
                                      HisDiagTemplateLinkService diagTemplateLinkService) {
        this.diagFreqService = diagFreqService;
        this.diseaseReportService = diseaseReportService;
        this.diagTemplateLinkService = diagTemplateLinkService;
    }

    /* ---------- 诊断助手 ---------- */

    /** 诊断助手: 聚合患者历史诊断 / 本科室高频 / 个人常用三类候选 */
    @GetMapping("/diagnosis/assistant")
    public R<Map<String, Object>> assistant(@RequestParam(required = false) Long patientId,
                                             @RequestParam(required = false) Long deptId,
                                             @RequestParam(required = false) Long staffId,
                                             @RequestParam(defaultValue = "15") int limit) {
        LoginUser user = UserContext.get();
        if (deptId == null && user != null) {
            deptId = user.getDeptId();
        }
        if (staffId == null && user != null) {
            staffId = user.getStaffId();
        }
        return R.ok(diagFreqService.assistant(patientId, deptId, staffId, limit));
    }

    /* ---------- 疾病报卡 ---------- */

    @PostMapping("/disease-report")
    public R<HisDiseaseReport> createReport(@RequestBody HisDiseaseReport report) {
        return R.ok(diseaseReportService.create(report));
    }

    @GetMapping("/disease-report/list")
    public R<List<HisDiseaseReport>> listReport(@RequestParam Long visitId) {
        return R.ok(diseaseReportService.listByVisit(visitId));
    }

    /* ---------- 诊断→医嘱/处方模板关联 ---------- */

    @GetMapping("/diag-template-link")
    public R<List<Map<String, Object>>> diagTemplateLink(@RequestParam String diagCode,
                                                         @RequestParam(required = false) Long deptId) {
        LoginUser user = UserContext.get();
        if (deptId == null && user != null) {
            deptId = user.getDeptId();
        }
        return R.ok(diagTemplateLinkService.listByDiag(diagCode, deptId));
    }
}
