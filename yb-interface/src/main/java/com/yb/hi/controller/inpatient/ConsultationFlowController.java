package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.inpatient.HisInpConsultation;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.ConsultationFlowService;
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
 * 会诊统一流程接口(P6-2, 住院/门诊通用): 申请→受理→完成/拒绝/取消 + 评价/超时预警/统计。
 * 与既有 /api/his/inp/consultation/(住院医生站在用)并存, 旧链路不改动。
 */
@RestController
@RequestMapping("/api/his/consultation")
public class ConsultationFlowController {

    private final ConsultationFlowService consultationFlowService;

    public ConsultationFlowController(ConsultationFlowService consultationFlowService) {
        this.consultationFlowService = consultationFlowService;
    }

    /** 创建会诊申请(body: HisInpConsultation; 响应时限按紧急程度自动计算) */
    @PostMapping("/apply")
    public R<HisInpConsultation> apply(@RequestBody HisInpConsultation dto) {
        return R.ok(consultationFlowService.apply(dto));
    }

    /** 受理会诊(1申请→2受理, 可选指定受邀专家 targetDoctorId) */
    @PutMapping("/{id}/accept")
    public R<HisInpConsultation> accept(@PathVariable Long id,
                                        @RequestParam(required = false) Long targetDoctorId) {
        return R.ok(consultationFlowService.accept(id, targetDoctorId));
    }

    /** 完成会诊(2受理→3完成; opinion 支持 param 或 body; 住院会诊自动生成会诊记录文书) */
    @PutMapping("/{id}/complete")
    public R<HisInpConsultation> complete(@PathVariable Long id,
                                          @RequestParam(required = false) String opinion,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(consultationFlowService.complete(id, firstText(opinion, body == null ? null : body.get("opinion"))));
    }

    /** 拒绝会诊(1申请或2受理→4拒绝; reason 支持 param 或 body) */
    @PutMapping("/{id}/reject")
    public R<HisInpConsultation> reject(@PathVariable Long id,
                                        @RequestParam(required = false) String reason,
                                        @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(consultationFlowService.reject(id, firstText(reason, body == null ? null : body.get("reason"))));
    }

    /** 取消会诊(1申请→5取消, 仅申请方且未受理) */
    @PutMapping("/{id}/cancel")
    public R<HisInpConsultation> cancel(@PathVariable Long id) {
        return R.ok(consultationFlowService.cancel(id));
    }

    /** 关联会诊医嘱(回写 order_id) */
    @PutMapping("/{id}/link-order")
    public R<Void> linkToOrder(@PathVariable Long id, @RequestParam Long orderId) {
        consultationFlowService.linkToOrder(id, orderId);
        return R.ok();
    }

    /** 双向评价(evaluatorType: applicant申请方/invitee受邀方; score 1-5) */
    @PutMapping("/{id}/evaluate")
    public R<Void> evaluate(@PathVariable Long id,
                            @RequestParam String evaluatorType,
                            @RequestParam Integer score,
                            @RequestParam(required = false) String note) {
        consultationFlowService.evaluate(id, evaluatorType, score, note);
        return R.ok();
    }

    /** 催促会诊(1申请或2受理: 向受邀科室重发SSE催促提醒, 不改状态/时限; P6-3 管理页预警催促入口) */
    @PutMapping("/{id}/urge")
    public R<Void> urge(@PathVariable Long id) {
        consultationFlowService.urge(id);
        return R.ok();
    }

    /** 手动触发超时检查(调度每分钟自动执行, 此入口供运维/联调) */
    @PostMapping("/check-timeout")
    public R<Void> checkTimeout() {
        consultationFlowService.checkTimeout();
        return R.ok();
    }

    /** 列表查询(多条件分页: 就诊类型/申请科室/受邀科室/分类/紧急程度/状态/时间区间) */
    @GetMapping("/list")
    public R<IPage<HisInpConsultation>> list(@RequestParam(required = false) Integer visitType,
                                             @RequestParam(required = false) Long applyDeptId,
                                             @RequestParam(required = false) Long targetDeptId,
                                             @RequestParam(required = false) String consultCategory,
                                             @RequestParam(required = false) Integer urgencyLevel,
                                             @RequestParam(required = false) Integer status,
                                             @RequestParam(required = false) String startDate,
                                             @RequestParam(required = false) String endDate,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(consultationFlowService.list(visitType, applyDeptId, targetDeptId, consultCategory,
                urgencyLevel, status, startDate, endDate, (int) Math.max(page, 1), (int) Math.min(Math.max(size, 1), 200)));
    }

    /** 按就诊查询(visitType=1住院兼容旧 inp_visit_id; 不传 visitType 时双列匹配) */
    @GetMapping("/by-visit")
    public R<List<HisInpConsultation>> listByVisit(@RequestParam(required = false) Integer visitType,
                                                   @RequestParam Long visitId) {
        return R.ok(consultationFlowService.listByVisit(visitType, visitId));
    }

    /** 运营统计(总数/分布/平均响应/超时率/科室医师Top10/急会诊达标率/评价均分) */
    @GetMapping("/statistics")
    public R<Map<String, Object>> statistics(@RequestParam(required = false) Long deptId,
                                             @RequestParam(required = false) String startDate,
                                             @RequestParam(required = false) String endDate) {
        return R.ok(consultationFlowService.statistics(deptId, startDate, endDate));
    }

    /** 会诊详情 */
    @GetMapping("/{id}")
    public R<HisInpConsultation> detail(@PathVariable Long id) {
        return R.ok(consultationFlowService.detail(id));
    }

    /** param 优先、body 兜底取文本值 */
    private static String firstText(String param, Object bodyValue) {
        if (param != null && !param.isEmpty()) {
            return param;
        }
        return bodyValue == null ? null : String.valueOf(bodyValue);
    }
}
