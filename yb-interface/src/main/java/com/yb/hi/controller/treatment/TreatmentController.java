package com.yb.hi.controller.treatment;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.treatment.HisTreatmentEquipment;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.treatment.TreatmentEquipmentService;
import com.yb.hi.service.treatment.TreatmentExecService;
import com.yb.hi.service.treatment.TreatmentPlanService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 治疗管理接口(治疗工作台/疗程管理/治疗记录/设备台账)。
 * 包含: 医嘱转计划 / 计划分页与详情 / 调整与终止 / 待执行工作台(签到-开始-完成-取消) /
 * 治疗记录查询 / 设备台账 CRUD。
 * 机构口径: 读走 guard.scopeOrgId(牵头可取入参, 非牵头强制本机构);
 * 写入机构由服务层 requireSameOrg 收敛(仅允许操作本机构数据)。
 */
@RestController
@RequestMapping("/api/treatment")
public class TreatmentController {

    private final TreatmentPlanService planService;
    private final TreatmentExecService execService;
    private final TreatmentEquipmentService equipService;
    private final OrgAccessGuard guard;

    public TreatmentController(TreatmentPlanService planService,
                               TreatmentExecService execService,
                               TreatmentEquipmentService equipService,
                               OrgAccessGuard guard) {
        this.planService = planService;
        this.execService = execService;
        this.equipService = equipService;
        this.guard = guard;
    }

    /* ================= 治疗计划 ================= */

    /** 计划分页: 患者/关键字(姓名或病历号)/状态筛选 */
    @GetMapping("/plans")
    public R<Page<Map<String, Object>>> plans(@RequestParam(required = false) Long orgId,
                                              @RequestParam(required = false) Long patientId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        return R.ok(planService.listPlans(resolveOrgId(orgId), patientId, keyword, status, page, size));
    }

    /** 计划详情: 计划(患者/医嘱/医师) + 执行单明细列表 */
    @GetMapping("/plan/{id}")
    public R<Map<String, Object>> planDetail(@PathVariable Long id) {
        return R.ok(planService.getPlanDetail(id));
    }

    /** 从治疗类医嘱单生成治疗计划(每个明细一条计划 + 预生成第1条执行单; 幂等可重复调用) */
    @PostMapping("/plan/from-order/{orderId}")
    public R<Map<String, Object>> planFromOrder(@PathVariable Long orderId) {
        return R.ok(planService.createPlanFromOrder(orderId));
    }

    /** 调整计划总次数: body {newTotal, reason}; 增大补建执行单, 调小至已完成则自动完成 */
    @PostMapping("/plan/{id}/adjust")
    public R<Map<String, Object>> planAdjust(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer newTotal = body == null ? null : toInt(body.get("newTotal"));
        String reason = body == null ? null : toStr(body.get("reason"));
        return R.ok(planService.adjustPlan(id, newTotal, reason));
    }

    /** 终止计划: body {reason}; 乐观锁 0->2, 同步取消在途执行单 */
    @PostMapping("/plan/{id}/terminate")
    public R<Map<String, Object>> planTerminate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String reason = body == null ? null : toStr(body.get("reason"));
        return R.ok(planService.terminatePlan(id, reason));
    }

    /* ================= 待执行工作台 / 执行流转 ================= */

    /** 待执行治疗单(工作台): 已收费(paid_flag=1)且计划执行中; 已签到按签到时间在前 */
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending(@RequestParam(required = false) Long orgId,
                                                @RequestParam(required = false) Long deptId) {
        return R.ok(execService.listPendingExecs(resolveOrgId(orgId), deptId));
    }

    /** 患者签到(排队): 幂等, 重复签到返回原时间 */
    @PostMapping("/exec/{id}/checkin")
    public R<Map<String, Object>> checkin(@PathVariable Long id) {
        return R.ok(execService.checkinPatient(id));
    }

    /** 开始治疗: body {therapistId, equipCode}; 乐观锁 0->1; therapistId 缺省取当前登录职工 */
    @PostMapping("/exec/{id}/start")
    public R<Map<String, Object>> start(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long therapistId = body == null ? null : toLong(body.get("therapistId"));
        String equipCode = body == null ? null : toStr(body.get("equipCode"));
        if (therapistId == null) {
            LoginUser lu = UserContext.get();
            therapistId = lu == null ? null : lu.getStaffId();
        }
        return R.ok(execService.startExec(id, therapistId, equipCode));
    }

    /** 完成治疗: body {durationMin, params, response}; 乐观锁 1->2, 联动疗程进度 */
    @PostMapping("/exec/{id}/finish")
    public R<Map<String, Object>> finish(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer durationMin = body == null ? null : toInt(body.get("durationMin"));
        String params = body == null ? null : toStr(body.get("params"));
        String response = body == null ? null : toStr(body.get("response"));
        return R.ok(execService.finishExec(id, durationMin, params, response));
    }

    /** 取消治疗单: body {reason}; 0/1 -> 3; 疗程未满自动补建下一次 */
    @PostMapping("/exec/{id}/cancel")
    public R<Map<String, Object>> cancel(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String reason = body == null ? null : toStr(body.get("reason"));
        return R.ok(execService.cancelExec(id, reason));
    }

    /** 治疗记录分页: 治疗师/类别/执行日期区间(日期缺省按创建日期) */
    @GetMapping("/exec-log")
    public R<Page<Map<String, Object>>> execLog(@RequestParam(required = false) Long orgId,
                                                @RequestParam(required = false) Long therapistId,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String startDate,
                                                @RequestParam(required = false) String endDate,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size) {
        return R.ok(execService.listExecLog(resolveOrgId(orgId), therapistId, category, startDate, endDate, page, size));
    }

    /* ================= 设备台账 ================= */

    /** 设备列表(科室/关键字筛选; 正常在前) */
    @GetMapping("/equipment")
    public R<List<Map<String, Object>>> equipmentList(@RequestParam(required = false) Long orgId,
                                                      @RequestParam(required = false) Long deptId,
                                                      @RequestParam(required = false) String keyword) {
        return R.ok(equipService.list(resolveOrgId(orgId), deptId, keyword));
    }

    /** 新增设备: 编码同机构唯一 */
    @PostMapping("/equipment")
    public R<HisTreatmentEquipment> equipmentCreate(@RequestBody HisTreatmentEquipment equipment) {
        Long orgId = resolveOrgId(equipment == null ? null : equipment.getOrgId());
        return R.ok(equipService.create(orgId, equipment));
    }

    /** 编辑设备 */
    @PutMapping("/equipment/{id}")
    public R<HisTreatmentEquipment> equipmentUpdate(@PathVariable Long id, @RequestBody HisTreatmentEquipment equipment) {
        return R.ok(equipService.update(id, equipment));
    }

    /** 删除设备(逻辑删; 存在待执行/执行中治疗单时拒绝) */
    @DeleteMapping("/equipment/{id}")
    public R<Void> equipmentDelete(@PathVariable Long id) {
        equipService.delete(id);
        return R.ok();
    }

    /* ================= 内部工具 ================= */

    /** 机构作用域: 牵头可取入参, 非牵头强制本机构; 入参为空回退当前登录机构 */
    private Long resolveOrgId(Long requested) {
        Long oid = guard.scopeOrgId(requested);
        if (oid != null) {
            return oid;
        }
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getOrgId();
    }

    private static Long toLong(Object v) {
        if (v == null || "".equals(v)) {
            return null;
        }
        return v instanceof Number ? ((Number) v).longValue() : Long.valueOf(v.toString().trim());
    }

    private static Integer toInt(Object v) {
        if (v == null || "".equals(v)) {
            return null;
        }
        return v instanceof Number ? ((Number) v).intValue() : Integer.valueOf(v.toString().trim());
    }

    private static String toStr(Object v) {
        return v == null ? null : v.toString();
    }
}
