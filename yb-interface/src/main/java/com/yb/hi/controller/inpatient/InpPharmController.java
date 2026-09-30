package com.yb.hi.controller.inpatient;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.inpatient.InpPharmService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 住院药师审核接口(T35): 待审队列 / 签名批量通过 / 驳回留原因 / 工作台统计 / 驳回历史。
 * 机构隔离与药师身份校验在 Service 层(见 InpPharmService); 药师审核签名由前端
 * HIS.SignaturePad(actionType=pharm_audit)完成后才发起本接口调用。
 */
@RestController
@RequestMapping("/api/his/inp/pharm")
public class InpPharmController {

    private final InpPharmService pharmService;

    public InpPharmController(InpPharmService pharmService) {
        this.pharmService = pharmService;
    }

    /**
     * 待审医嘱队列(pharm_audit_status=1): JOIN 患者/床位/药品目录, 开立时间正序, 分页;
     * deptId(就诊科室)与 keyword(药品名/医嘱内容)可选过滤。
     */
    @GetMapping("/queue")
    public R<Map<String, Object>> queue(@RequestParam(required = false) Long deptId,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return R.ok(pharmService.getReviewQueue(deptId, keyword, page, size));
    }

    /**
     * 批量通过: body { orderIds: ["1","2"] }(雪花ID以字符串承载回传, parseId 兼容数字); 药师ID取当前登录职工。
     * 前置: 前端已完成电子签名(HIS.SignaturePad, actionType=pharm_audit)。
     */
    @PostMapping("/audit")
    @SuppressWarnings("unchecked")
    public R<Integer> audit(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("orderIds");
        if (!(raw instanceof List)) {
            throw new BizException(400, "请选择要审核的医嘱");
        }
        List<Long> orderIds = new ArrayList<>();
        for (Object o : (List<Object>) raw) {
            Long id = parseId(o);
            if (id != null) {
                orderIds.add(id);
            }
        }
        return R.ok(pharmService.batchAudit(orderIds, currentPharmacistId()));
    }

    /** 驳回: body { orderId: "1", reason: "剂量不合理" }; 驳回原因落 pharm_reject_reason。 */
    @PostMapping("/reject")
    public R<Void> reject(@RequestBody Map<String, Object> body) {
        Long orderId = body == null ? null : parseId(body.get("orderId"));
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        Object reason = body.get("reason");
        pharmService.rejectOrder(orderId, currentPharmacistId(),
                reason == null ? null : String.valueOf(reason));
        return R.ok(null);
    }

    /** 工作台统计: { pending: 待审数, auditedToday: 今日已审, rejectedToday: 今日驳回 }。 */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(pharmService.getStats());
    }

    /** 驳回历史(按就诊): 该就诊全部被驳回的药品医嘱, 时间倒序。 */
    @GetMapping("/reject-history")
    public R<List<Map<String, Object>>> rejectHistory(@RequestParam Long visitId) {
        return R.ok(pharmService.getRejectHistory(visitId));
    }

    /** 当前登录药师(his_staff.id 优先, 未关联职工时回退用户ID)。 */
    static Long currentPharmacistId() {
        LoginUser u = UserContext.get();
        return u == null ? null : (u.getStaffId() != null ? u.getStaffId() : u.getUserId());
    }

    /** 宽松 ID 解析: 兼容 JSON 数字与字符串(雪花ID以字符串承载回传), 空/非法返回 null。 */
    private static Long parseId(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
