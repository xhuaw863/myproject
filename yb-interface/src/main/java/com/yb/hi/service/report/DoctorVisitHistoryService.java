package com.yb.hi.service.report;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisOrder;
import com.yb.hi.entity.doctor.HisPrescription;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.service.doctor.EmrStructureReader;
import com.yb.hi.service.doctor.HisAdmissionCertService;
import com.yb.hi.service.doctor.HisConsultRequestService;
import com.yb.hi.service.doctor.HisDiagnosisService;
import com.yb.hi.service.doctor.HisDiseaseReportService;
import com.yb.hi.service.doctor.HisMedicalCertService;
import com.yb.hi.service.doctor.HisMedicalRecordService;
import com.yb.hi.service.doctor.HisOrderService;
import com.yb.hi.service.doctor.HisPrescriptionService;
import com.yb.hi.service.doctor.HisVisitService;
import com.yb.hi.service.doctor.OutpWsBusinessService;
import com.yb.hi.service.emr.EmrDocumentService;
import com.yb.hi.service.inpatient.ConsultationFlowService;
import com.yb.hi.service.medtech.ExamReportService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 诊疗统计接诊明细的只读历史查询服务。
 * 以统计列表中的就诊为授权锚点，普通医生只能从本人接诊记录进入；进入后可查看同一患者、
 * 当前机构范围内的全部有效门诊历史。完整病历及单据明细按选中就诊懒加载。
 */
@Service
public class DoctorVisitHistoryService {

    private final HisVisitService visitService;
    private final HisDiagnosisService diagnosisService;
    private final HisMedicalRecordService medicalRecordService;
    private final HisPrescriptionService prescriptionService;
    private final HisOrderService orderService;
    private final HisDeptMapper deptMapper;
    private final EmrDocumentService emrDocumentService;
    private final ExamReportService examReportService;
    private final HisAdmissionCertService admissionCertService;
    private final HisMedicalCertService medicalCertService;
    private final HisConsultRequestService consultRequestService;
    private final ConsultationFlowService consultationFlowService;
    private final OutpWsBusinessService outpWsBusinessService;
    private final HisDiseaseReportService diseaseReportService;

    public DoctorVisitHistoryService(HisVisitService visitService,
                                     HisDiagnosisService diagnosisService,
                                     HisMedicalRecordService medicalRecordService,
                                     HisPrescriptionService prescriptionService,
                                     HisOrderService orderService,
                                     HisDeptMapper deptMapper,
                                     EmrDocumentService emrDocumentService,
                                     ExamReportService examReportService,
                                     HisAdmissionCertService admissionCertService,
                                     HisMedicalCertService medicalCertService,
                                     HisConsultRequestService consultRequestService,
                                     ConsultationFlowService consultationFlowService,
                                     OutpWsBusinessService outpWsBusinessService,
                                     HisDiseaseReportService diseaseReportService) {
        this.visitService = visitService;
        this.diagnosisService = diagnosisService;
        this.medicalRecordService = medicalRecordService;
        this.prescriptionService = prescriptionService;
        this.orderService = orderService;
        this.deptMapper = deptMapper;
        this.emrDocumentService = emrDocumentService;
        this.examReportService = examReportService;
        this.admissionCertService = admissionCertService;
        this.medicalCertService = medicalCertService;
        this.consultRequestService = consultRequestService;
        this.consultationFlowService = consultationFlowService;
        this.outpWsBusinessService = outpWsBusinessService;
        this.diseaseReportService = diseaseReportService;
    }

    /** 返回患者基本信息及权限范围内的完整门诊历史索引，不截断历史次数。 */
    public Map<String, Object> patientHistory(Long anchorVisitId, Long orgId, Long requiredStaffId) {
        AccessContext access = authorizeAnchor(anchorVisitId, orgId, requiredStaffId);
        HisVisit anchor = access.anchor;

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisVisit> query =
                Wrappers.<HisVisit>lambdaQuery()
                        .eq(HisVisit::getPatientId, anchor.getPatientId())
                        .ne(HisVisit::getVisitStatus, 4)
                        .orderByDesc(HisVisit::getWorkDate)
                        .orderByDesc(HisVisit::getVisitTime)
                        .orderByDesc(HisVisit::getId);
        if (access.allowedDeptIds != null) {
            query.in(HisVisit::getDeptId, access.allowedDeptIds);
        }
        List<HisVisit> visits = visitService.list(query);
        List<Long> visitIds = visits.stream().map(HisVisit::getId).collect(Collectors.toList());
        Map<Long, List<HisDiagnosis>> diagnosisByVisit = new LinkedHashMap<>();
        if (!visitIds.isEmpty()) {
            List<HisDiagnosis> diagnoses = diagnosisService.list(Wrappers.<HisDiagnosis>lambdaQuery()
                    .in(HisDiagnosis::getVisitId, visitIds)
                    .orderByAsc(HisDiagnosis::getDiagSrtNo)
                    .orderByAsc(HisDiagnosis::getId));
            for (HisDiagnosis diagnosis : diagnoses) {
                diagnosisByVisit.computeIfAbsent(diagnosis.getVisitId(), key -> new ArrayList<>()).add(diagnosis);
            }
        }

        List<Map<String, Object>> history = new ArrayList<>();
        for (HisVisit visit : visits) {
            Map<String, String> soap = EmrStructureReader.read(visit);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("visitId", visit.getId());
            item.put("workDate", visit.getWorkDate());
            item.put("visitTime", visit.getVisitTime());
            item.put("finishTime", visit.getFinishTime());
            item.put("visitStatus", visit.getVisitStatus());
            item.put("chargeStatus", visit.getChargeStatus());
            item.put("deptName", visit.getDeptName());
            item.put("doctorName", visit.getDrName());
            item.put("chiefComplaint", soap.get("chiefComplaint"));
            item.put("mainDiagnosis", mainDiagnosis(diagnosisByVisit.get(visit.getId()), soap.get("diagnosis")));
            history.add(item);
        }

        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("patientId", anchor.getPatientId());
        patient.put("patientNo", anchor.getPatientNo());
        patient.put("name", anchor.getPatientName());
        patient.put("gender", anchor.getGender());
        patient.put("age", anchor.getAge());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("anchorVisitId", anchor.getId());
        result.put("patient", patient);
        result.put("visits", history);
        return result;
    }

    /** 返回指定历史就诊的完整只读资料：病历、诊断、处方、医技、报告及诊疗过程中形成的医疗文书。 */
    public Map<String, Object> visitDetail(Long anchorVisitId, Long visitId, Long orgId, Long requiredStaffId) {
        AccessContext access = authorizeAnchor(anchorVisitId, orgId, requiredStaffId);
        HisVisit visit = requireVisit(visitId);
        if (!Objects.equals(access.anchor.getPatientId(), visit.getPatientId())) {
            throw new BizException(403, "只能查看当前患者的就诊历史");
        }
        requireOrgScope(visit, access.allowedDeptIds);
        if (visit.getEmrFormat() != null && visit.getEmrFormat() == 1 && visit.getContent() != null) {
            visit.setContent(emrDocumentService.loadDocument(2, visit.getId(), visit.getContent()));
        }

        List<Map<String, Object>> prescriptions = new ArrayList<>();
        for (HisPrescription prescription : prescriptionService.listByVisit(visitId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("prescription", prescription);
            row.put("items", prescriptionService.listItems(prescription.getId()));
            prescriptions.add(row);
        }
        List<HisOrder> visitOrders = orderService.listByVisit(visitId);
        List<Map<String, Object>> orders = new ArrayList<>();
        for (HisOrder order : visitOrders) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order", order);
            row.put("items", orderService.listItems(order.getId()));
            orders.add(row);
        }
        List<Long> orderIds = visitOrders.stream().map(HisOrder::getId)
                .filter(Objects::nonNull).collect(Collectors.toList());

        List<?> consultRequests = consultationFlowService.listByVisit(2, visitId);
        if (consultRequests == null || consultRequests.isEmpty()) {
            consultRequests = consultRequestService.listByVisit(visitId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visit", visit);
        result.put("record", medicalRecordService.getByVisit(visitId));
        result.put("soap", EmrStructureReader.read(visit));
        result.put("diagnoses", diagnosisService.listByVisit(visitId));
        result.put("prescriptions", prescriptions);
        result.put("orders", orders);
        result.put("reports", examReportService.listReportsByOrders(orderIds));
        result.put("admissionCerts", admissionCertService.listByVisit(visitId));
        result.put("medicalCerts", medicalCertService.listByVisit(visitId));
        result.put("consultRequests", consultRequests);
        result.put("consents", outpWsBusinessService.consentList(visitId));
        result.put("agents", outpWsBusinessService.agentList(visitId));
        result.put("referrals", outpWsBusinessService.referralList(visit.getPatientId()).stream()
                .filter(item -> Objects.equals(item.getVisitId(), visitId)).collect(Collectors.toList()));
        result.put("greenCredits", outpWsBusinessService.greenList(visit.getPatientId()).stream()
                .filter(item -> Objects.equals(item.getVisitId(), visitId)).collect(Collectors.toList()));
        result.put("dogbiteRegisters", outpWsBusinessService.dogbiteList(visitId));
        result.put("diseaseReports", diseaseReportService.listByVisit(visitId));
        return result;
    }

    private AccessContext authorizeAnchor(Long anchorVisitId, Long orgId, Long requiredStaffId) {
        HisVisit anchor = requireVisit(anchorVisitId);
        if (requiredStaffId != null && !Objects.equals(requiredStaffId, anchor.getStaffId())) {
            throw new BizException(403, "无权查看该接诊记录的患者历史");
        }
        List<Long> allowedDeptIds = null;
        if (orgId != null) {
            allowedDeptIds = deptMapper.selectList(Wrappers.<HisDept>lambdaQuery()
                            .eq(HisDept::getOrgId, orgId))
                    .stream().map(HisDept::getId).collect(Collectors.toList());
            requireOrgScope(anchor, allowedDeptIds);
        }
        return new AccessContext(anchor, allowedDeptIds);
    }

    private HisVisit requireVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        HisVisit visit = visitService.getOne(Wrappers.<HisVisit>lambdaQuery()
                .eq(HisVisit::getId, visitId)
                .ne(HisVisit::getVisitStatus, 4), false);
        if (visit == null) {
            throw new BizException(404, "就诊记录不存在或已取消");
        }
        return visit;
    }

    private static void requireOrgScope(HisVisit visit, List<Long> allowedDeptIds) {
        if (allowedDeptIds != null && (visit.getDeptId() == null || !allowedDeptIds.contains(visit.getDeptId()))) {
            throw new BizException(403, "无权查看其他机构的就诊历史");
        }
    }

    private static String mainDiagnosis(List<HisDiagnosis> diagnoses, String fallback) {
        List<HisDiagnosis> rows = diagnoses == null ? Collections.emptyList() : diagnoses;
        for (HisDiagnosis diagnosis : rows) {
            if ("1".equals(diagnosis.getMaindiagFlag()) && validDiagnosis(diagnosis)) {
                return diagnosis.getDiagName();
            }
        }
        for (HisDiagnosis diagnosis : rows) {
            if (validDiagnosis(diagnosis)) {
                return diagnosis.getDiagName();
            }
        }
        return fallback;
    }

    private static boolean validDiagnosis(HisDiagnosis diagnosis) {
        return diagnosis != null && !"0".equals(diagnosis.getValiFlag());
    }

    private static final class AccessContext {
        private final HisVisit anchor;
        private final List<Long> allowedDeptIds;

        private AccessContext(HisVisit anchor, List<Long> allowedDeptIds) {
            this.anchor = anchor;
            this.allowedDeptIds = allowedDeptIds;
        }
    }
}
