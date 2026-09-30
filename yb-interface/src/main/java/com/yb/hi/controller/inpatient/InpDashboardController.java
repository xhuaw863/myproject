package com.yb.hi.controller.inpatient;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 住院业务看板接口: 医生站首页 / 护士站首页 / 患者列表统计卡。
 * 数据隔离: 租户在数据层强制, 病区归属校验在 Service 内完成(ward.org_id 对齐当前登录机构);
 * deptId 缺省时回落当前登录医生归属科室。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/his/inp/dashboard")
public class InpDashboardController {

    private final InpDashboardService dashboardService;

    /** 医生站看板: 待办统计/患者统计/近7日趋势/今日手术/时限预警。deptId 可选, 缺省回落登录医生科室。 */
    @GetMapping("/doctor")
    public R<Map<String, Object>> doctor(@RequestParam(required = false) Long deptId) {
        return R.ok(dashboardService.getDoctorDashboard(deptId));
    }

    /** 护士站看板: 待办统计/患者统计/危重患者清单/护理评估预警。wardId 可选, 前端应传当前病区。 */
    @GetMapping("/nurse")
    public R<Map<String, Object>> nurse(@RequestParam(required = false) Long wardId) {
        return R.ok(dashboardService.getNurseDashboard(wardId));
    }

    /** 患者列表统计卡: 在院/今日入出院/危重/欠费/预交金预警。wardId/deptId 可选过滤。 */
    @GetMapping("/patient-stats")
    public R<Map<String, Object>> patientStats(@RequestParam(required = false) Long wardId,
                                               @RequestParam(required = false) Long deptId) {
        return R.ok(dashboardService.getPatientListStats(wardId, deptId));
    }
}
