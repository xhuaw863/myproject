package com.yb.hi.controller.emr;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrTimelinessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 病历时效质控接口(病历P5a-2): 单病历时效检查/超时病历清单/临近超时预警/病区时效概况。
 *
 * 鉴权走 AuthInterceptor 统一 JWT; 租户隔离由 MyBatis-Plus 插件(TenantContext)承担,
 * JdbcTemplate 联查 SQL 内显式携带 tenant_id 条件。时限规则种子与定时扫描由
 * EmrTimelinessService 启动播种(@Order(9))与 autoNotify(每30分钟)自动执行。
 */
@RestController
@RequestMapping("/api/his/emr/timeliness")
public class EmrTimelinessController {

    private final EmrTimelinessService timelinessService;

    public EmrTimelinessController(EmrTimelinessService timelinessService) {
        this.timelinessService = timelinessService;
    }

    /**
     * 单病历时效检查: GET /api/his/emr/timeliness/check/{recordId}
     * 返回 {status: overdue/warning/normal, deadlineTime, remainingMinutes, rules: [匹配规则明细]}。
     */
    @GetMapping("/check/{recordId}")
    public R<Map<String, Object>> check(@PathVariable Long recordId) {
        return R.ok(timelinessService.checkTimeliness(recordId));
    }

    /**
     * 超时病历列表: GET /api/his/emr/timeliness/overdue-list?wardId=&deptId=&startDate=&endDate=
     * 在院未签名且已过书写截止的病历, 截止时间升序(超时最久在前)。
     */
    @GetMapping("/overdue-list")
    public R<List<Map<String, Object>>> overdueList(@RequestParam(required = false) Long wardId,
                                                    @RequestParam(required = false) Long deptId,
                                                    @RequestParam(required = false) String startDate,
                                                    @RequestParam(required = false) String endDate) {
        return R.ok(timelinessService.overdueList(wardId, deptId, startDate, endDate));
    }

    /**
     * 临近超时预警列表: GET /api/his/emr/timeliness/near-deadline?wardId=&withinHours=
     * withinHours 默认 2(小时), 上限 72。
     */
    @GetMapping("/near-deadline")
    public R<List<Map<String, Object>>> nearDeadline(@RequestParam(required = false) Long wardId,
                                                     @RequestParam(defaultValue = "2") int withinHours) {
        return R.ok(timelinessService.nearDeadlineList(wardId, withinHours));
    }

    /**
     * 病区时效概况: GET /api/his/emr/timeliness/ward-summary?wardId=&date=
     * 返回 {total, overdueCount, warningCount, normalCount, items: [超时优先排序的待完成清单]}。
     */
    @GetMapping("/ward-summary")
    public R<Map<String, Object>> wardSummary(@RequestParam(required = false) Long wardId,
                                              @RequestParam(required = false) String date) {
        return R.ok(timelinessService.wardSummary(wardId, date));
    }
}
