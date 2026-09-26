package com.yb.hi.controller.basedata;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.basedata.HisSchedule;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.basedata.HisScheduleService;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 排班号源管理接口(机构级业务过程):
 * 读: 硬限定当前登录机构(currentOrgId, 牵头也不穿透到成员机构); 写: 本机构管理员(requireSelfOrgWrite)。
 * 提供分页列表 / CRUD(冲突+科室归属/开诊校验) / 批量停诊 / 按模板生成 / 复制周 / 周视图 / 号源统计。
 */
@RestController
@RequestMapping("/api/his/schedule")
public class HisScheduleController {

    private final HisScheduleService scheduleService;
    private final OrgAccessGuard guard;

    public HisScheduleController(HisScheduleService scheduleService, OrgAccessGuard guard) {
        this.scheduleService = scheduleService;
        this.guard = guard;
    }

    /** 排班分页列表(硬限定本机构; 科室/医师/日期区间/状态可选, 日期格式 yyyy-MM-dd) */
    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Long staffId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "50") long size) {
        return R.ok(scheduleService.listPage(guard.currentOrgId(), deptId, staffId, from, to, status, page, size));
    }

    /** 新增排班(含同医师同日同时段冲突校验; 排班科室需为本机构开诊门诊科室) */
    @PostMapping
    public R<HisSchedule> create(@RequestBody HisSchedule schedule) {
        guard.requireSelfOrgWrite();
        return R.ok(scheduleService.create(schedule, guard.currentOrgId()));
    }

    /** 编辑排班(totalNum 不得低于已挂号数, leftNum 由后端推导不允许直接编辑) */
    @PutMapping
    public R<HisSchedule> update(@RequestBody HisSchedule schedule) {
        guard.requireSelfOrgWrite();
        return R.ok(scheduleService.update(schedule));
    }

    /** 删除排班(仅无人挂号时可删) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        scheduleService.delete(id);
        return R.ok(null);
    }

    /** 加号: 总号源+1 且剩余号源+1(原子更新, 返回更新后的记录) */
    @PostMapping("/addSlot")
    public R<HisSchedule> addSlot(@RequestParam Long id) {
        guard.requireSelfOrgWrite();
        return R.ok(scheduleService.addSlot(id));
    }

    /** 批量停诊: {ids: [..], reason: "..."} -> 受影响行数 */
    @PostMapping("/batch-stop")
    public R<Integer> batchStop(@RequestBody Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        List<Long> ids = readIds(body.get("ids"));
        String reason = (String) body.get("reason");
        return R.ok(scheduleService.batchStop(ids, reason));
    }

    /** 按模板生成排班: {templateIds: [..], startDate, endDate} -> {created, skipped, total} */
    @PostMapping("/generate")
    public R<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        List<Long> templateIds = readIds(body.get("templateIds"));
        String startDate = (String) body.get("startDate");
        String endDate = (String) body.get("endDate");
        return R.ok(scheduleService.generateFromTemplate(templateIds, startDate, endDate));
    }

    /** 复制某周排班到目标周(仅本机构): {sourceWeekStart, targetWeekStart, deptId?} -> {created, skipped, total} */
    @PostMapping("/copy-week")
    public R<Map<String, Object>> copyWeek(@RequestBody Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        String sourceWeekStart = (String) body.get("sourceWeekStart");
        String targetWeekStart = (String) body.get("targetWeekStart");
        Long deptId = body.get("deptId") != null ? Long.valueOf(body.get("deptId").toString()) : null;
        return R.ok(scheduleService.copyWeek(sourceWeekStart, targetWeekStart, deptId));
    }

    /** 周视图矩阵(硬限定本机构; weekStart 为一周起始日期 yyyy-MM-dd) */
    @GetMapping("/week")
    public R<List<Map<String, Object>>> week(
            @RequestParam String weekStart,
            @RequestParam(required = false) Long deptId) {
        return R.ok(scheduleService.weekView(weekStart, deptId, guard.currentOrgId()));
    }

    /** 号源统计(按科室汇总, 硬限定本机构; date 空默认当天) */
    @GetMapping("/stats")
    public R<List<Map<String, Object>>> stats(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Long deptId) {
        return R.ok(scheduleService.stats(date, deptId, guard.currentOrgId()));
    }

    /** body 中的 id 数组转 Long 列表(空安全; 空列表由 Service 校验兜底) */
    private static List<Long> readIds(Object raw) {
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            return Collections.emptyList();
        }
        return ((List<?>) raw).stream()
                .map(o -> Long.valueOf(o.toString()))
                .collect(Collectors.toList());
    }
}
