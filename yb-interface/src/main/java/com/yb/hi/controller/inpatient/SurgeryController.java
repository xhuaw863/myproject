package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.inpatient.SurgeryDTO;
import com.yb.hi.dto.inpatient.SurgeryScheduleDTO;
import com.yb.hi.dto.inpatient.InpOrderDTO;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.SurgeryApplyService;
import com.yb.hi.service.inpatient.SurgeryOrderService;
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
    private final SurgeryApplyService applyService;
    private final SurgeryOrderService surgeryOrderService;
    private final OrgAccessGuard guard;

    public SurgeryController(SurgeryService surgeryService, SurgeryApplyService applyService,
                             SurgeryOrderService surgeryOrderService, OrgAccessGuard guard) {
        this.surgeryService = surgeryService;
        this.applyService = applyService;
        this.surgeryOrderService = surgeryOrderService;
        this.guard = guard;
    }

    /** 手术列表(分页; orgId/deptId/startDate/endDate/statuses逗号多值如"2,7"/visitType 可选筛选) */
    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String statuses,
            @RequestParam(required = false) Integer visitType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(surgeryService.listSurgeries(guard.scopeOrgId(orgId), deptId,
                startDate, endDate, statuses, visitType, page, size));
    }

    /** 未安排手术池(已复核待安排 status=2 的申请单, 急诊/择期按时限排序) */
    @GetMapping("/unarranged-list")
    public R<List<Map<String, Object>>> unarrangedList() {
        return R.ok(surgeryService.unarrangedList());
    }

    /** 麻醉已安排列表(已排程/已报到且存在麻醉记录) */
    @GetMapping("/anesthesia-list")
    public R<List<Map<String, Object>>> anesthesiaList() {
        return R.ok(surgeryService.anesthesiaList());
    }

    /** 手术排程板(日期×手术间占用视图, date 默认今日) */
    @GetMapping("/room-board")
    public R<Map<String, Object>> roomBoard(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(surgeryService.roomBoard(guard.scopeOrgId(orgId), date));
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

    /** 三级及以上手术审批(approval_status 1->2通过/3驳回), 通过后方可开始手术 */
    @PutMapping("/{id}/approve")
    public R<HisSurgery> approve(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean approved) {
        return R.ok(surgeryService.approveSurgery(id, approved));
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

    /** 取消手术(status ->6, 仅申请中/已排程/已报到可取消; 来源申请回退待安排) */
    @PutMapping("/{id}/cancel")
    public R<HisSurgery> cancel(@PathVariable Long id) {
        return R.ok(surgeryService.cancel(id));
    }

    /* ==================== 手麻P0: 申请安排 / 登记报到 / 调配 ==================== */

    /** 从申请单安排手术: 申请(2)→建手术(status=2)+申请→4 */
    @PostMapping("/schedule-from-apply/{applyId}")
    public R<HisSurgery> scheduleFromApply(@PathVariable Long applyId, @RequestBody SurgeryScheduleDTO dto) {
        return R.ok(surgeryService.scheduleFromApply(applyId, dto));
    }

    /** 急诊直接安排(跳过申请复核, 自动建档已安排申请单) */
    @PostMapping("/urgent-schedule")
    public R<HisSurgery> urgentSchedule(@RequestBody SurgeryScheduleDTO dto) {
        return R.ok(surgeryService.urgentSchedule(dto));
    }

    /** 报到检索(申请单号/病历号/姓名定位待报到与已报到手术) */
    @GetMapping("/register-query")
    public R<List<Map<String, Object>>> registerQuery(@RequestParam String keyword) {
        return R.ok(surgeryService.registerQuery(keyword));
    }

    /** 报到登记: 已排程(2)→已报到(7), 记 register_time */
    @PutMapping("/{id}/check-in")
    public R<HisSurgery> checkIn(@PathVariable Long id) {
        return R.ok(surgeryService.checkIn(id));
    }

    /** 手术间调配: 换 room_no + 安排变动通知(type2) */
    @PutMapping("/{id}/transfer")
    public R<HisSurgery> transfer(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(surgeryService.transfer(id, body == null ? null : body.get("roomNo")));
    }

    /** 排程修改(时间/手术间/团队): 变动自动产生 type2 通知(前端红标) */
    @PutMapping("/{id}/reschedule")
    public R<HisSurgery> reschedule(@PathVariable Long id, @RequestBody SurgeryScheduleDTO dto) {
        return R.ok(surgeryService.reschedule(id, dto));
    }

    /** 手术室退回手术: 2/7→6, 来源申请回退待安排(4→2) */
    @PutMapping("/{id}/cancel-schedule")
    public R<HisSurgery> cancelSchedule(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return R.ok(surgeryService.cancelSchedule(id, body == null ? null : body.get("reason")));
    }

    /** 取消完成(补退费场景): 5→4 */
    @PutMapping("/{id}/cancel-complete")
    public R<HisSurgery> cancelComplete(@PathVariable Long id) {
        return R.ok(surgeryService.cancelComplete(id));
    }

    /** 手术未处理通知角标计数(待通知总数): 前端变动提醒红标 */
    @GetMapping("/notify/pending-count")
    public R<Integer> notifyPendingCount() {
        return R.ok((int) applyService.notifyPage(1, null, 1, 1).getTotal());
    }

    /* ==================== 手麻P1: 手术医嘱 ==================== */

    /** 按手术查询手术医嘱(术中/术后, 按阶段排序) */
    @GetMapping("/{id}/orders")
    public R<List<HisInpOrder>> surgeryOrders(@PathVariable Long id) {
        return R.ok(surgeryOrderService.listBySurgery(id));
    }

    /** 按申请单查询术前医嘱 */
    @GetMapping("/apply/{applyId}/orders")
    public R<List<HisInpOrder>> applyOrders(@PathVariable Long applyId) {
        return R.ok(surgeryOrderService.listByApply(applyId));
    }

    /** 开立手术医嘱(术中/术后, 挂手术 id) */
    @PostMapping("/{id}/order")
    public R<HisInpOrder> addSurgeryOrder(@PathVariable Long id, @RequestBody InpOrderDTO dto) {
        dto.setSurgeryId(id);
        return R.ok(surgeryOrderService.addOrder(dto));
    }

    /** 开立术前医嘱(挂申请单) */
    @PostMapping("/apply/{applyId}/order")
    public R<HisInpOrder> addApplyOrder(@PathVariable Long applyId, @RequestBody InpOrderDTO dto) {
        dto.setSurgeryApplyId(applyId);
        return R.ok(surgeryOrderService.addOrder(dto));
    }
}
