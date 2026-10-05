package com.yb.hi.service.report;

import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.doctor.HisGreenChannelCredit;
import com.yb.hi.entity.doctor.HisOrder;
import com.yb.hi.entity.doctor.HisPrescription;
import com.yb.hi.entity.doctor.HisReferral;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.basedata.HisDeptMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DoctorVisitHistoryServiceTest {

    private HisVisitService visitService;
    private HisDiagnosisService diagnosisService;
    private HisMedicalRecordService medicalRecordService;
    private HisPrescriptionService prescriptionService;
    private HisOrderService orderService;
    private HisDeptMapper deptMapper;
    private EmrDocumentService emrDocumentService;
    private ExamReportService examReportService;
    private HisAdmissionCertService admissionCertService;
    private HisMedicalCertService medicalCertService;
    private HisConsultRequestService consultRequestService;
    private ConsultationFlowService consultationFlowService;
    private OutpWsBusinessService outpWsBusinessService;
    private HisDiseaseReportService diseaseReportService;
    private DoctorVisitHistoryService service;

    @BeforeEach
    void setUp() {
        visitService = mock(HisVisitService.class);
        diagnosisService = mock(HisDiagnosisService.class);
        medicalRecordService = mock(HisMedicalRecordService.class);
        prescriptionService = mock(HisPrescriptionService.class);
        orderService = mock(HisOrderService.class);
        deptMapper = mock(HisDeptMapper.class);
        emrDocumentService = mock(EmrDocumentService.class);
        examReportService = mock(ExamReportService.class);
        admissionCertService = mock(HisAdmissionCertService.class);
        medicalCertService = mock(HisMedicalCertService.class);
        consultRequestService = mock(HisConsultRequestService.class);
        consultationFlowService = mock(ConsultationFlowService.class);
        outpWsBusinessService = mock(OutpWsBusinessService.class);
        diseaseReportService = mock(HisDiseaseReportService.class);
        service = new DoctorVisitHistoryService(visitService, diagnosisService, medicalRecordService,
                prescriptionService, orderService, deptMapper, emrDocumentService, examReportService,
                admissionCertService, medicalCertService, consultRequestService, consultationFlowService,
                outpWsBusinessService, diseaseReportService);
    }

    @Test
    void ordinaryDoctorCannotUseAnotherDoctorsVisitAsAnchor() {
        when(visitService.getOne(any(), anyBoolean())).thenReturn(visit(1L, 10L, 20L, 301L));

        BizException error = assertThrows(BizException.class,
                () -> service.patientHistory(1L, null, 999L));

        assertEquals(403, error.getCode());
        verifyNoInteractions(deptMapper, diagnosisService, prescriptionService, orderService,
                medicalRecordService, emrDocumentService, examReportService, admissionCertService,
                medicalCertService, consultRequestService, consultationFlowService,
                outpWsBusinessService, diseaseReportService);
    }

    @Test
    void detailCannotSwitchFromAnchorToAnotherPatient() {
        HisVisit anchor = visit(1L, 10L, 20L, 301L);
        HisVisit anotherPatient = visit(2L, 11L, 20L, 302L);
        when(visitService.getOne(any(), anyBoolean())).thenReturn(anchor, anotherPatient);

        BizException error = assertThrows(BizException.class,
                () -> service.visitDetail(1L, 2L, null, 301L));

        assertEquals(403, error.getCode());
        assertTrue(error.getMessage().contains("当前患者"));
        verifyNoInteractions(prescriptionService, orderService, medicalRecordService, emrDocumentService);
    }

    @Test
    void detailRejectsVisitOutsideRequestedOrganization() {
        HisVisit anchor = visit(1L, 10L, 20L, 301L);
        HisVisit otherOrgVisit = visit(2L, 10L, 99L, 302L);
        HisDept allowedDept = new HisDept();
        allowedDept.setId(20L);
        allowedDept.setOrgId(7L);
        when(deptMapper.selectList(any())).thenReturn(Collections.singletonList(allowedDept));
        when(visitService.getOne(any(), anyBoolean())).thenReturn(anchor, otherOrgVisit);

        BizException error = assertThrows(BizException.class,
                () -> service.visitDetail(1L, 2L, 7L, null));

        assertEquals(403, error.getCode());
        assertTrue(error.getMessage().contains("其他机构"));
        verifyNoInteractions(prescriptionService, orderService, medicalRecordService, emrDocumentService);
    }

    @Test
    void cancelledOrMissingAnchorIsRejected() {
        when(visitService.getOne(any(), anyBoolean())).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service.patientHistory(1L, null, 301L));

        assertEquals(404, error.getCode());
        assertTrue(error.getMessage().contains("不存在或已取消"));
    }

    @Test
    void tiptapRecordIsDecryptedAndDocumentItemsAreAggregated() {
        HisVisit anchor = visit(1L, 10L, 20L, 301L);
        HisVisit detail = visit(2L, 10L, 20L, 302L);
        detail.setEmrFormat(1);
        detail.setContent("encrypted");
        HisPrescription prescription = new HisPrescription();
        prescription.setId(41L);
        HisOrder order = new HisOrder();
        order.setId(51L);
        when(visitService.getOne(any(), anyBoolean())).thenReturn(anchor, detail);
        when(emrDocumentService.loadDocument(2, 2L, "encrypted")).thenReturn("{\"type\":\"doc\"}");
        when(prescriptionService.listByVisit(2L)).thenReturn(Collections.singletonList(prescription));
        when(prescriptionService.listItems(41L)).thenReturn(Collections.emptyList());
        when(orderService.listByVisit(2L)).thenReturn(Collections.singletonList(order));
        when(orderService.listItems(51L)).thenReturn(Collections.emptyList());
        when(diagnosisService.listByVisit(2L)).thenReturn(Collections.emptyList());

        Map<String, Object> result = service.visitDetail(1L, 2L, null, 301L);

        assertEquals("{\"type\":\"doc\"}", ((HisVisit) result.get("visit")).getContent());
        assertEquals(1, ((java.util.List<?>) result.get("prescriptions")).size());
        assertEquals(1, ((java.util.List<?>) result.get("orders")).size());
        verify(emrDocumentService).loadDocument(2, 2L, "encrypted");
        verify(prescriptionService).listItems(41L);
        verify(orderService).listItems(51L);
    }

    @Test
    void plainRecordDoesNotInvokeDocumentDecryption() {
        HisVisit anchor = visit(1L, 10L, 20L, 301L);
        HisVisit detail = visit(2L, 10L, 20L, 302L);
        detail.setEmrFormat(0);
        detail.setContent("plain");
        when(visitService.getOne(any(), anyBoolean())).thenReturn(anchor, detail);
        when(prescriptionService.listByVisit(2L)).thenReturn(Collections.emptyList());
        when(orderService.listByVisit(2L)).thenReturn(Collections.emptyList());
        when(diagnosisService.listByVisit(2L)).thenReturn(Collections.emptyList());

        service.visitDetail(1L, 2L, null, 301L);

        verify(emrDocumentService, never()).loadDocument(anyInt(), anyLong(), anyString());
    }

    @Test
    void reportsAndDocumentsAreLimitedToSelectedVisit() {
        HisVisit anchor = visit(1L, 10L, 20L, 301L);
        HisVisit detail = visit(2L, 10L, 20L, 302L);
        HisOrder order = new HisOrder();
        order.setId(51L);
        HisReferral selectedReferral = new HisReferral();
        selectedReferral.setVisitId(2L);
        HisReferral otherReferral = new HisReferral();
        otherReferral.setVisitId(99L);
        HisGreenChannelCredit selectedCredit = new HisGreenChannelCredit();
        selectedCredit.setVisitId(2L);
        HisGreenChannelCredit otherCredit = new HisGreenChannelCredit();
        otherCredit.setVisitId(99L);
        when(visitService.getOne(any(), anyBoolean())).thenReturn(anchor, detail);
        when(prescriptionService.listByVisit(2L)).thenReturn(Collections.emptyList());
        when(orderService.listByVisit(2L)).thenReturn(Collections.singletonList(order));
        when(orderService.listItems(51L)).thenReturn(Collections.emptyList());
        when(examReportService.listReportsByOrders(Collections.singletonList(51L)))
                .thenReturn(Collections.singletonList(Collections.singletonMap("reportNo", "BG001")));
        when(consultationFlowService.listByVisit(2, 2L)).thenReturn(Collections.emptyList());
        when(consultRequestService.listByVisit(2L)).thenReturn(Collections.singletonList(null));
        when(outpWsBusinessService.referralList(10L)).thenReturn(Arrays.asList(selectedReferral, otherReferral));
        when(outpWsBusinessService.greenList(10L)).thenReturn(Arrays.asList(selectedCredit, otherCredit));

        Map<String, Object> result = service.visitDetail(1L, 2L, null, 301L);

        assertEquals(1, ((List<?>) result.get("reports")).size());
        assertEquals(1, ((List<?>) result.get("consultRequests")).size());
        assertEquals(1, ((List<?>) result.get("referrals")).size());
        assertEquals(1, ((List<?>) result.get("greenCredits")).size());
        verify(examReportService).listReportsByOrders(Collections.singletonList(51L));
        verify(consultRequestService).listByVisit(2L);
    }

    private static HisVisit visit(Long id, Long patientId, Long deptId, Long staffId) {
        HisVisit visit = new HisVisit();
        visit.setId(id);
        visit.setPatientId(patientId);
        visit.setPatientNo("P" + patientId);
        visit.setPatientName("患者" + patientId);
        visit.setDeptId(deptId);
        visit.setStaffId(staffId);
        visit.setVisitStatus(3);
        return visit;
    }
}
