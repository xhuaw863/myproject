package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.InpLongOrderChargeSweeper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.InpOrderExecService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 住院医嘱审核与执行接口(护士站): 待审核列表 / 批量审核 / 驳回 / 执行计划 / 批量执行 / 标记未执行。
 * 状态机: order_status 1新开 -(audit)->2已审核 -(exec)->执行; reject 1->6作废;
 * exec_status 1待执行 ->2已执行 / 3未执行(乐观更新, 并发冲突返回 409)。
 */
@RestController
@RequestMapping("/api/his/inp/order-exec")
public class InpOrderExecController {

    private final InpOrderExecService execService;
    private final InpLongOrderChargeSweeper longOrderChargeSweeper;
    private final OrgAccessGuard guard;

    public InpOrderExecController(InpOrderExecService execService,
                                  InpLongOrderChargeSweeper longOrderChargeSweeper, OrgAccessGuard guard) {
        this.execService = execService;
        this.longOrderChargeSweeper = longOrderChargeSweeper;
        this.guard = guard;
    }

    /** 待审核医嘱列表(order_status=1): JOIN 患者/床位/开嘱医生, 开立时间正序, 分页。 */
    @GetMapping("/pending")
    public R<IPage<Map<String, Object>>> pending(
            @RequestParam Long wardId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(execService.listPendingAudit(wardId, page, size));
    }

    /**
     * 批量审核医嘱: order_status 1->2 记录审核护士与时间;
     * 长期医嘱(order_type=1)联动按频次生成当天执行计划。
     * body: [orderId, ...]
     */
    @PostMapping("/audit")
    public R<Map<String, Object>> audit(@RequestBody List<Long> orderIds) {
        return R.ok(execService.batchAudit(orderIds, InpNurseController.currentNurseId()));
    }

    /** 驳回医嘱: order_status 1->6 作废(仅新开态可驳, reason 记日志并随响应返回)。 */
    @PostMapping("/reject")
    public R<Map<String, Object>> reject(@RequestParam Long orderId,
                                         @RequestParam String reason) {
        return R.ok(execService.rejectOrder(orderId, reason, InpNurseController.currentNurseId()));
    }

    /**
     * 执行计划列表(病区 + 日期): 查 his_inp_order_exec 按 plan_time 排序,
     * JOIN 医嘱/患者/床位/医生; date 缺省当天。
     */
    @GetMapping("/plan")
    public R<IPage<Map<String, Object>>> plan(
            @RequestParam Long wardId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(execService.listExecPlan(wardId, date, page, size));
    }

    /** 批量执行: exec_status 1->2 记录执行护士与时间。body: [execId, ...] */
    @PostMapping("/execute")
    public R<Map<String, Object>> execute(@RequestBody List<Long> execIds) {
        return R.ok(execService.batchExecute(execIds, InpNurseController.currentNurseId()));
    }

    /** 标记未执行: exec_status 1->3, remark 必填(未执行原因)。 */
    @PostMapping("/cancel-exec")
    public R<Map<String, Object>> cancelExec(@RequestParam Long execId,
                                             @RequestParam String remark) {
        return R.ok(execService.cancelExec(execId, remark, InpNurseController.currentNurseId()));
    }

    /**
     * S-4(2026-10-03 安全审计): 长期医嘱逐日记账手动补记。
     * 触发定时任务同款回填逻辑, 为活动长期医嘱(order_type=1, 单价非空, 频次非prn)
     * 在 [开始日..min(停嘱日,记账日)] 内逐日补生成缺失费用明细(按 order_id+charge_date 幂等)。
     * 仅牵头机构管理员可操作(requireLeadWrite); 补记范围按登录租户收敛, 禁止跨租户。
     * date 缺省当天。
     */
    @PostMapping("/post-daily-charge")
    public R<Map<String, Object>> postDailyCharge(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        guard.requireLeadWrite();
        LoginUser lu = UserContext.get();
        if (lu == null || lu.getTenantId() == null) {
            throw new BizException(401, "未登录或缺少租户上下文, 无法补记长期医嘱费用");
        }
        LocalDate day = date == null ? LocalDate.now() : date;
        return R.ok(longOrderChargeSweeper.postForDate(day, lu.getTenantId()));
    }
}
