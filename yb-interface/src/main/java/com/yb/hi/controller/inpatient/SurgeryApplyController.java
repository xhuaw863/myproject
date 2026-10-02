package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.inpatient.SurgeryApplyDTO;
import com.yb.hi.entity.inpatient.HisSurgeryApply;
import com.yb.hi.entity.inpatient.HisSurgeryNotify;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.SurgeryApplyService;
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
 * 手术申请管理接口(规范2.2.2.3.7.7/14/22): 申请单 CRUD + 复核/退回/作废/重提 + 通知管理(批量发送/回复登记)。
 * 机构隔离在 Service 内统一走 OrgAccessGuard, Controller 仅做参数透传。
 */
@RestController
@RequestMapping("/api/his/surgery-apply")
public class SurgeryApplyController {

    private final SurgeryApplyService applyService;

    public SurgeryApplyController(SurgeryApplyService applyService) {
        this.applyService = applyService;
    }

    /** 申请单分页(visitType/status/deptId/申请日期区间/关键字: 单号/患者/手术名/病历号) */
    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(
            @RequestParam(required = false) Integer visitType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(applyService.page(visitType, status, deptId, startDate, endDate, keyword, page, size));
    }

    /** 申请单详情 */
    @GetMapping("/{id}")
    public R<HisSurgeryApply> detail(@PathVariable Long id) {
        return R.ok(applyService.detail(id));
    }

    /** 门诊/日间就诊检索(申请对话框按姓名/门诊号选患者) */
    @GetMapping("/visit-search")
    public R<List<Map<String, Object>>> visitSearch(@RequestParam(required = false) String keyword) {
        return R.ok(applyService.visitSearch(keyword));
    }

    /** 新建申请单(患者快照 + 主刀权限校验 + 自动预约通知) */
    @PostMapping
    public R<HisSurgeryApply> add(@RequestBody SurgeryApplyDTO dto) {
        return R.ok(applyService.add(dto));
    }

    /** 病区复核通过: 1待复核 -> 2已复核待安排 */
    @PutMapping("/{id}/reconfirm")
    public R<HisSurgeryApply> reconfirm(@PathVariable Long id) {
        return R.ok(applyService.reconfirm(id));
    }

    /** 复核退回: 1 -> 3(退回原因必填) */
    @PutMapping("/{id}/reject")
    public R<HisSurgeryApply> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(applyService.reject(id, body == null ? null : body.get("reason")));
    }

    /** 作废: 1/2/3 -> 6(原因必填, 已安排手术须先退回安排) */
    @PutMapping("/{id}/void")
    public R<HisSurgeryApply> voidApply(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(applyService.voidApply(id, body == null ? null : body.get("reason")));
    }

    /** 退回后重新提交: 3 -> 1(可修改手术/主刀/期望时间) */
    @PutMapping("/{id}/resubmit")
    public R<HisSurgeryApply> resubmit(@PathVariable Long id, @RequestBody(required = false) SurgeryApplyDTO dto) {
        return R.ok(applyService.resubmit(id, dto));
    }

    /* ==================== 通知管理(规范2.2.2.3.7.6) ==================== */

    /** 通知分页(status: 1待通知 2已通知 3已回复; notifyType: 1预约 2变动 3术前提醒; channel: 1短信 2电话 3诊间 4自助机 5APP 6公众号) */
    @GetMapping("/notify/list")
    public R<IPage<Map<String, Object>>> notifyList(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer notifyType,
            @RequestParam(required = false) Integer channel,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(applyService.notifyPage(status, notifyType, channel, page, size));
    }

    /** 批量发送通知(渠道经网关下发: 电话/诊间仅留痕, 其余走 Noop/Http 网关) */
    @PutMapping("/notify/send")
    public R<Integer> notifySend(@RequestBody List<Long> ids) {
        return R.ok(applyService.notifySend(ids));
    }

    /** 单条重发(失败或需再触达): 走网关后 retry_count+1 并置已通知 */
    @PostMapping("/notify/{id}/resend")
    public R<HisSurgeryNotify> notifyResend(@PathVariable Long id) {
        return R.ok(applyService.notifyResend(id));
    }

    /** 送达回查(P4a): 取网关回执调通道查最终送达, 回填 send_status/delivered_time/error_msg */
    @PostMapping("/notify/{id}/query-delivery")
    public R<HisSurgeryNotify> notifyQueryDelivery(@PathVariable Long id) {
        return R.ok(applyService.queryDelivery(id));
    }

    /** 术前提醒批量生成(手动触发, 幂等去重): 对已安排且临近排期手术产 notify_type=3 记录, 返回新增条数 */
    @PostMapping("/notify/pre-op")
    public R<Integer> notifyPreOp(@RequestParam(defaultValue = "24") int beforeHours) {
        return R.ok(applyService.generatePreOpReminders(beforeHours));
    }

    /** 电话通知/患者回复登记: 置已回复并记录回复内容(channel 可改2电话) */
    @PutMapping("/notify/{id}/reply")
    public R<HisSurgeryNotify> notifyReply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String reply = body == null ? null : (String) body.get("replyContent");
        Integer channel = body == null || body.get("channel") == null
                ? null : Integer.valueOf(String.valueOf(body.get("channel")));
        return R.ok(applyService.notifyReply(id, reply, channel));
    }
}
