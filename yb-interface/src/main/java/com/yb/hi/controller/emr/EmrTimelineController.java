package com.yb.hi.controller.emr;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrTimelineService;
import com.yb.hi.service.emr.EmrTimelineService.TimelineItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 患者360°时间轴接口: 门诊/住院就诊事件统一时间轴 + 就诊摘要 + 患者总览。
 *
 * 路由:
 *  - GET /api/his/emr/timeline/patient/{patientId}?startDate&endDate&types → 时间轴条目(倒序, 最近100条);
 *  - GET /api/his/emr/timeline/visit-summary/{visitId}?scope=1|2          → 就诊摘要(scope 1住院 2门诊);
 *  - GET /api/his/emr/timeline/patient-overview/{patientId}               → 患者总览(就诊量/最近就诊/有效诊断/在院态)。
 *
 * 只读聚合, 无写操作; 认证由 AuthInterceptor(/api/**)把守, 租户隔离由 MyBatis-Plus 租户插件自动注入。
 */
@RestController
@RequestMapping("/api/his/emr/timeline")
public class EmrTimelineController {

    private final EmrTimelineService timelineService;

    public EmrTimelineController(EmrTimelineService timelineService) {
        this.timelineService = timelineService;
    }

    /**
     * 患者全景时间轴。
     *
     * @param patientId 患者ID(his_patient.id)
     * @param startDate 起始日期 yyyy-MM-dd(含, 可空)
     * @param endDate   结束日期 yyyy-MM-dd(含, 可空)
     * @param types     事件类型过滤, 逗号分隔: outpatient,inpatient(空=全部)
     */
    @GetMapping("/patient/{patientId}")
    public R<List<TimelineItem>> timeline(@PathVariable Long patientId,
                                          @RequestParam(required = false) String startDate,
                                          @RequestParam(required = false) String endDate,
                                          @RequestParam(required = false) String types) {
        return R.ok(timelineService.getTimeline(patientId, startDate, endDate, types));
    }

    /**
     * 就诊摘要: 主诉/诊断/关键医嘱/重要提示。
     *
     * @param visitId 就诊ID(scope=2→his_visit.id; scope=1→his_inp_visit.id)
     * @param scope   1住院 2门诊(默认门诊)
     */
    @GetMapping("/visit-summary/{visitId}")
    public R<Map<String, Object>> visitSummary(@PathVariable Long visitId,
                                               @RequestParam(defaultValue = "2") int scope) {
        return R.ok(timelineService.getVisitSummary(visitId, scope));
    }

    /** 患者总览: 就诊总量(门诊+住院)/最近一次就诊/最近就诊的有效诊断/当前在院。 */
    @GetMapping("/patient-overview/{patientId}")
    public R<Map<String, Object>> patientOverview(@PathVariable Long patientId) {
        return R.ok(timelineService.getPatientOverview(patientId));
    }
}
