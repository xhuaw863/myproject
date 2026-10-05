package com.yb.hi.controller.report;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.report.DoctorVisitHistoryService;
import com.yb.hi.service.report.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportControllerDoctorScopeTest {

    private final ReportService reportService = mock(ReportService.class);
    private final DoctorVisitHistoryService visitHistoryService = mock(DoctorVisitHistoryService.class);
    private final OrgAccessGuard guard = mock(OrgAccessGuard.class);
    private final ReportController controller = new ReportController(reportService, visitHistoryService, guard);

    @AfterEach
    void clearContext() {
        UserContext.clear();
        TenantContext.clear();
    }

    @Test
    void ordinaryDoctorIsAlwaysLockedToOwnStaff() {
        LoginUser user = new LoginUser();
        user.setRole(Roles.DOCTOR);
        user.setStaffId(101L);
        user.setDeptId(12L);
        UserContext.set(user);
        when(reportService.doctorWorklogSummary(any(), any(), any(), anyLong(), any()))
                .thenReturn(Collections.emptyList());

        controller.doctorWorklog(null, "2026-10-01", "2026-10-05", 999L, 88L);

        verify(reportService).doctorWorklogSummary(0L, "2026-10-01", "2026-10-05", 101L, null);
    }

    @Test
    void administratorMayUseRequestedScope() {
        LoginUser user = new LoginUser();
        user.setRole(Roles.ADMIN);
        UserContext.set(user);
        when(reportService.doctorWorklogSummary(any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(Collections.emptyList());

        controller.doctorWorklog(null, "2026-10-01", "2026-10-05", 202L, 22L);

        verify(reportService).doctorWorklogSummary(0L, "2026-10-01", "2026-10-05", 202L, 22L);
    }

    @Test
    void ordinaryAccountWithoutStaffBindingIsRejected() {
        LoginUser user = new LoginUser();
        user.setRole(Roles.NURSE);
        UserContext.set(user);

        assertThrows(BizException.class,
                () -> controller.doctorWorklog(null, null, null, 999L, 88L));
    }

    @Test
    void historyEndpointsKeepOrdinaryDoctorLockedToOwnStaff() {
        LoginUser user = new LoginUser();
        user.setRole(Roles.DOCTOR);
        user.setStaffId(101L);
        UserContext.set(user);
        when(visitHistoryService.patientHistory(anyLong(), anyLong(), anyLong()))
                .thenReturn(Collections.emptyMap());
        when(visitHistoryService.visitDetail(anyLong(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Collections.emptyMap());

        controller.doctorWorklogPatientHistory(null, 701L);
        controller.doctorWorklogVisitDetail(null, 701L, 702L);

        verify(visitHistoryService).patientHistory(701L, 0L, 101L);
        verify(visitHistoryService).visitDetail(701L, 702L, 0L, 101L);
    }

    @Test
    void administratorHistoryEndpointUsesRequestedOrganizationWithoutStaffRestriction() {
        LoginUser user = new LoginUser();
        user.setRole(Roles.ADMIN);
        UserContext.set(user);
        when(guard.scopeOrgId(8L)).thenReturn(8L);
        when(visitHistoryService.patientHistory(anyLong(), anyLong(), any()))
                .thenReturn(Collections.emptyMap());

        controller.doctorWorklogPatientHistory(8L, 701L);

        verify(visitHistoryService).patientHistory(701L, 8L, null);
    }

    @Test
    void analyticsRejectsRangesLongerThan366DaysBeforeQueryingDatabase() {
        TenantContext.set(1L);
        ReportService service = new ReportService(mock(JdbcTemplate.class));

        assertThrows(BizException.class,
                () -> service.doctorWorklogAnalytics(null, "2025-01-01", "2026-01-02", 101L, null));
    }
}
