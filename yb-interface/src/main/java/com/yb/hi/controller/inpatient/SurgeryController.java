package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.inpatient.SurgeryDTO;
import com.yb.hi.dto.inpatient.SurgeryScheduleDTO;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.SurgeryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 手术管理接口: 手术列表/详情 / 申请 / 编辑 / 排程 / 状态流转(开始/结束/完成/取消) / 今日排程 / 手术间列表。
 * 机构隔离: 读走 scopeOrgId(牵头可跨机构汇总, 非牵头锁定本机构), 写以 currentOrgId 归属。
 */
@RestController
@RequestMapping("/api/his/surgery")
public class SurgeryController {

    private final SurgeryService surgeryService;
    private final OrgAccessGuard guard;

    public SurgeryController(SurgeryService surgeryService, OrgAccessGuard guard) {
        this.surgeryService = surgeryService;
        this.guard = guard;
    }

    /** 手术列表(分页; orgId/deptId/startDate/endDate/status 可选筛选, JOIN患者显示姓名/住院号) */
    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(surgeryService.listSurgeries(guard.scopeOrgId(orgId), deptId,
                startDate, endDate, status, page, size));
    }

    /** 今日手术排程表(schedule_date=today, 按手术间分组, 含患者/主刀医师信息) */
    @GetMapping("/schedule/today")
    public R<Map<String, Object>> todaySchedule(@RequestParam(required = false) Long orgId) {
        return R.ok(surgeryService.todaySchedule(guard.scopeOrgId(orgId)));
    }

    /** 手术间列表(预置, 后续可配置化) */
    @GetMapping("/room/list")
    public R<List<String>> roomList() {
        return R.ok(surgeryService.roomList());
    }

    /** 手术详情(含患者信息、手术团队人员信息) */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(surgeryService.getDetail(id));
    }

    /** 手术申请(创建记录, status=1申请中) */
    @PostMapping
    public R<HisSurgery> create(@RequestBody SurgeryDTO dto) {
        return R.ok(surgeryService.create(dto, guard.currentOrgId()));
    }

    /** 编辑手术信息(仅申请中/已排程可编辑) */
    @PutMapping("/{id}")
    public R<HisSurgery> update(@PathVariable Long id, @RequestBody SurgeryDTO dto) {
        return R.ok(surgeryService.update(id, dto));
    }

    /** 手术排程(指定日期/时间段/手术间, status 1->2) */
    @PutMapping("/{id}/schedule")
    public R<HisSurgery> schedule(@PathVariable Long id, @RequestBody SurgeryScheduleDTO dto) {
        return R.ok(surgeryService.schedule(id, dto));
    }

    /** 开始手术(status 2->3, 记录 start_time) */
    @PutMapping("/{id}/start")
    public R<HisSurgery> start(@PathVariable Long id) {
        return R.ok(surgeryService.start(id));
    }

    /** 结束手术(status 3->4, 记录 end_time) */
    @PutMapping("/{id}/end")
    public R<HisSurgery> end(@PathVariable Long id) {
        return R.ok(surgeryService.end(id));
    }

    /** 完成手术(status 4->5) */
    @PutMapping("/{id}/complete")
    public R<HisSurgery> complete(@PathVariable Long id) {
        return R.ok(surgeryService.complete(id));
    }

    /** 取消手术(status ->6, 仅申请中/已排程可取消) */
    @PutMapping("/{id}/cancel")
    public R<HisSurgery> cancel(@PathVariable Long id) {
        return R.ok(surgeryService.cancel(id));
    }
}
