package com.yb.hi.controller.inpatient;

import com.alibaba.fastjson2.JSON;
import com.yb.hi.dto.inpatient.NursingPlanDTO;
import com.yb.hi.dto.inpatient.NursingPlanInstanceDTO;
import com.yb.hi.entity.inpatient.HisNursingPlanInstance;
import com.yb.hi.entity.inpatient.HisNursingPlanTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingPlanService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 护理计划接口(护士站): 计划模板管理(按量表分值区间触发, trigger_score_range 支持
 * "min-max"/"min~max"/">=x"/">x"/"<=x"/"<x"/精确值) + 患者计划实例的创建/措施执行/评价/关闭。
 * 实例为模板快照(创建时复制诊断/目标/措施), 模板修改/删除不影响已生成实例。
 */
@RestController
@RequestMapping("/api/his/inp/nursing-plan")
public class NursingPlanController {

    private final NursingPlanService planService;

    public NursingPlanController(NursingPlanService planService) {
        this.planService = planService;
    }

    /* ================= 计划模板 ================= */

    /** 模板列表: 可按触发量表编码/科室筛选(含停用模板, 由前端按状态展示)。 */
    @GetMapping("/templates")
    public R<List<HisNursingPlanTemplate>> templates(
            @RequestParam(required = false) String scaleCode,
            @RequestParam(required = false) Long deptId) {
        return R.ok(planService.listTemplates(scaleCode, deptId));
    }

    /** 创建模板: 计划名称必填; 配置触发分值区间时须指定触发量表编码。 */
    @PostMapping("/template")
    public R<HisNursingPlanTemplate> createTemplate(@RequestBody NursingPlanDTO dto) {
        return R.ok(planService.createTemplate(dto));
    }

    /** 更新模板: 仅覆盖请求中提供的字段, 未提供的保持原值。 */
    @PutMapping("/template/{id}")
    public R<Void> updateTemplate(@PathVariable Long id, @RequestBody NursingPlanDTO dto) {
        planService.updateTemplate(id, dto);
        return R.ok();
    }

    /** 删除模板(逻辑删除, 已生成的计划实例不受影响)。 */
    @DeleteMapping("/template/{id}")
    public R<Void> removeTemplate(@PathVariable Long id) {
        planService.removeTemplate(id);
        return R.ok();
    }

    /* ================= 计划实例 ================= */

    /** 患者计划列表(按就诊): 可选状态筛选(1执行中 2已评价 3已关闭), 开始时间倒序。 */
    @GetMapping("/list")
    public R<List<HisNursingPlanInstance>> list(@RequestParam Long visitId,
                                                @RequestParam(required = false) Integer status) {
        return R.ok(planService.listByVisit(visitId, status));
    }

    /** 创建计划实例(status=1执行中): 可携带模板ID, 未提供的诊断/目标/措施回退取模板内容。 */
    @PostMapping("/")
    public R<HisNursingPlanInstance> create(@RequestBody NursingPlanInstanceDTO dto) {
        return R.ok(planService.createInstance(dto));
    }

    /** 记录措施执行: body 为单条措施 JSON 对象(缺 time 字段自动补记录时间), 追加到实际措施数组。 */
    @PostMapping("/{id}/intervention")
    public R<Void> intervention(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        planService.recordIntervention(id, JSON.toJSONString(body));
        return R.ok();
    }

    /** 评价计划(status 1→2): 记录评价时间与评价结果, 仅执行中的计划可评价。 */
    @PutMapping("/{id}/evaluate")
    public R<Void> evaluate(@PathVariable Long id, @RequestParam String result) {
        planService.evaluate(id, result);
        return R.ok();
    }

    /** 关闭计划(status→3): 执行中/已评价均可关闭。 */
    @PutMapping("/{id}/close")
    public R<Void> close(@PathVariable Long id) {
        planService.closePlan(id);
        return R.ok();
    }
}
