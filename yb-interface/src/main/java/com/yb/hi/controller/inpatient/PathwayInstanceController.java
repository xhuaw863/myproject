package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.PathwayStartDTO;
import com.yb.hi.entity.inpatient.HisPathwayExec;
import com.yb.hi.entity.inpatient.HisPathwayInstance;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.PathwayInstanceService;
import com.yb.hi.service.inpatient.PathwayStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 患者临床路径实例接口: 入径启动 / 当前活跃路径 / 执行详情 / 天数推进 / 暂停恢复 / 退出完成 /
 * 执行当天任务(转医嘱) / 跳过 / 变异登记 / 统计。
 * 机构隔离与状态机校验在 Service 层(见 PathwayInstanceService / PathwayStatsService)。
 */
@RestController
@RequestMapping("/api/his/pathway/instance")
public class PathwayInstanceController {

    private final PathwayInstanceService instanceService;
    private final PathwayStatsService statsService;
    private final OrgAccessGuard guard;

    public PathwayInstanceController(PathwayInstanceService instanceService, PathwayStatsService statsService,
                                     OrgAccessGuard guard) {
        this.instanceService = instanceService;
        this.statsService = statsService;
        this.guard = guard;
    }

    /** 为患者启动路径(校验在院、无活跃路径, 预生成逐日执行记录) */
    @PostMapping("/start")
    public R<HisPathwayInstance> start(@RequestBody PathwayStartDTO dto) {
        return R.ok(instanceService.startPathway(dto, guard.currentOrgId()));
    }

    /** 当前患者的活跃路径实例(进行中/暂停, 含模板名称与进度信息; 未入径返回 null) */
    @GetMapping("/current/{visitId}")
    public R<Map<String, Object>> current(@PathVariable Long visitId) {
        return R.ok(instanceService.getCurrentInstance(visitId));
    }

    /** 路径执行详情(每天节点+任务+执行状态完整树形结构) */
    @GetMapping("/{id}/detail")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(instanceService.getInstanceDetail(id));
    }

    /** 推进到下一天(current_day+1) */
    @PutMapping("/{id}/advance")
    public R<HisPathwayInstance> advance(@PathVariable Long id) {
        return R.ok(instanceService.advanceDay(id));
    }

    /** 暂停路径(1进行中 → 4暂停) */
    @PutMapping("/{id}/pause")
    public R<HisPathwayInstance> pause(@PathVariable Long id) {
        return R.ok(instanceService.pause(id));
    }

    /** 恢复路径(4暂停 → 1进行中) */
    @PutMapping("/{id}/resume")
    public R<HisPathwayInstance> resume(@PathVariable Long id) {
        return R.ok(instanceService.resume(id));
    }

    /** 退出路径(status→3, 记录 exit_reason; type 为退出原因分类码 cv_code:pathway_exit_reason, 名称字典回填) */
    @PutMapping("/{id}/exit")
    public R<HisPathwayInstance> exit(@PathVariable Long id,
                                      @RequestParam(required = false) String reason,
                                      @RequestParam(required = false) String type) {
        return R.ok(instanceService.exit(id, reason, type));
    }

    /** 完成路径(status→2, 记录 end_date) */
    @PutMapping("/{id}/complete")
    public R<HisPathwayInstance> complete(@PathVariable Long id) {
        return R.ok(instanceService.complete(id));
    }

    /** 执行当天任务(核心): 必做且待执行的任务批量转住院医嘱并回链 */
    @PostMapping("/{id}/exec-day")
    public R<Map<String, Object>> execDay(@PathVariable Long id) {
        return R.ok(instanceService.executeDayTasks(id));
    }

    /** 跳过任务(exec_status→3) */
    @PutMapping("/exec/{execId}/skip")
    public R<HisPathwayExec> skip(@PathVariable Long execId) {
        return R.ok(instanceService.skipExec(execId));
    }

    /** 标记变异(exec_status→4, 记录 variance_reason; type 为变异原因分类码 cv_code:pathway_var_reason, 名称字典回填) */
    @PutMapping("/exec/{execId}/variance")
    public R<HisPathwayExec> variance(@PathVariable Long execId,
                                      @RequestParam(required = false) String reason,
                                      @RequestParam(required = false) String type) {
        return R.ok(instanceService.markVariance(execId, reason, type));
    }

    /** 路径统计(orgId 可选, 机构隔离) */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) Long orgId) {
        return R.ok(statsService.getStats(guard.scopeOrgId(orgId)));
    }
}
