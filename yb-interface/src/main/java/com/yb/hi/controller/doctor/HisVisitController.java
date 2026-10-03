package com.yb.hi.controller.doctor;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.doctor.VisitDraftReq;
import com.yb.hi.dto.doctor.VisitFinishReq;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisDiagnosisService;
import com.yb.hi.service.doctor.HisMedicalRecordService;
import com.yb.hi.service.doctor.HisOrderService;
import com.yb.hi.service.doctor.HisPrescriptionService;
import com.yb.hi.service.doctor.HisVisitService;
import com.yb.hi.service.doctor.EmrStructureReader;
import com.yb.hi.service.emr.EmrDocumentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
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
    // P3 Tiptap 双轨: detail 回显前解密 his_visit.content(emrFormat=1)
    private final EmrDocumentService emrDocumentService;

    public HisVisitController(HisVisitService visitService, HisDiagnosisService diagnosisService,
                              HisMedicalRecordService medicalRecordService, HisPrescriptionService prescriptionService,
                              HisOrderService orderService, EmrDocumentService emrDocumentService) {
        this.visitService = visitService;
        this.diagnosisService = diagnosisService;
        this.medicalRecordService = medicalRecordService;
        this.prescriptionService = prescriptionService;
        this.orderService = orderService;
        this.emrDocumentService = emrDocumentService;
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
        HisVisit visit = visitService.getById(id);
        // P3 Tiptap 双轨: emrFormat=1 时回显前解密 content(历史明文/解密失败原样透传, 不阻断详情)
        if (visit != null && visit.getEmrFormat() != null && visit.getEmrFormat() == 1
                && visit.getContent() != null) {
            visit.setContent(emrDocumentService.loadDocument(2, visit.getId(), visit.getContent()));
        }
        data.put("visit", visit);
        data.put("record", medicalRecordService.getByVisit(id));
        // 方案 B 收敛(B2): 供打印/回显消费的 SOAP 视图, 有 structure 则派生, 否则回退旧 SOAP 列
        data.put("soap", EmrStructureReader.read(visit));
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

    /** 保存病历草稿: 回写病历/医保扩展字段, 不改变就诊状态 */
    @PostMapping("/save-draft")
    public R<Void> saveDraft(@RequestBody VisitDraftReq req) {
        visitService.saveDraft(req);
        return R.ok();
    }

    /** 患者历史就诊(已完成, 含主诊断), 默认最近5次 */
    @GetMapping("/history")
    public R<List<?>> history(@RequestParam Long patientId, @RequestParam(defaultValue = "5") int limit) {
        return R.ok(visitService.listHistory(patientId, limit));
    }

    /** 就诊费用汇总: 处方/检查治疗单笔数与金额 */
    @GetMapping("/fee-summary")
    public R<Map<String, Object>> feeSummary(@RequestParam Long visitId) {
        return R.ok(visitService.getFeeSummary(visitId));
    }

    /** 患者医保参保信息(可多条) */
    @GetMapping("/insu-info")
    public R<List<?>> insuInfo(@RequestParam Long patientId) {
        return R.ok(visitService.getInsuInfo(patientId));
    }
}
