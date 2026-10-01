package com.yb.hi.controller.pharmacy;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.pharmacy.OutpPrescriptionAuditService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门诊处方审核接口(P2): 待审队列 / 审核详情 / 批量通过 / 驳回 / 重审 / 自动审核 / 统计 / 驳回原因字典。
 * 审核写走本机构管理员/药师/超管角色守卫(日常业务, 与窗口签到同档, 不限牵头); 读接口租户级开放。
 */
@RestController
@RequestMapping("/api/his/pharmacy/rx-audit")
public class OutpPrescriptionAuditController {

    private final OutpPrescriptionAuditService auditService;

    public OutpPrescriptionAuditController(OutpPrescriptionAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/pending")
    public R<Map<String, Object>> pending(@RequestParam(required = false) Long deptId,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Integer auditStatus,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return R.ok(auditService.pendingPage(deptId, keyword, auditStatus, page, size));
    }

    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam Long prescriptionId) {
        return R.ok(auditService.detail(prescriptionId));
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(auditService.stats());
    }

    @GetMapping("/reject-reasons")
    public R<List<String>> rejectReasons() {
        return R.ok(auditService.rejectReasons());
    }

    /** 批量通过: body {ids:[...]} */
    @PostMapping("/batch-audit")
    public R<Map<String, Object>> batchAudit(@RequestBody Map<String, Object> body) {
        requirePharmWrite();
        List<Long> ids = toLongList(body.get("ids"));
        int affected = auditService.batchAudit(ids, currentUserName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", ids.size());
        out.put("affected", affected);
        return R.ok(out);
    }

    /** 驳回: body {prescriptionId, reason} */
    @PostMapping("/reject")
    public R<Void> reject(@RequestBody Map<String, Object> body) {
        requirePharmWrite();
        auditService.reject(toLong(body.get("prescriptionId")), str(body.get("reason")), currentUserName());
        return R.ok();
    }

    /** 重审(驳回→待审): body {prescriptionId} */
    @PostMapping("/re-audit")
    public R<Void> reAudit(@RequestBody Map<String, Object> body) {
        requirePharmWrite();
        auditService.reAudit(toLong(body.get("prescriptionId")), currentUserName());
        return R.ok();
    }

    /** 自动审核(合理用药 Mock): body {prescriptionId} */
    @PostMapping("/auto-audit")
    public R<Map<String, Object>> autoAudit(@RequestBody Map<String, Object> body) {
        requirePharmWrite();
        return R.ok(auditService.autoAudit(toLong(body.get("prescriptionId")), currentUserName()));
    }

    /* ================= 守卫/工具 ================= */

    /** 审核写守卫: 本机构管理员/药师/超管(日常业务不限牵头) */
    private void requirePharmWrite() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (!lu.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN, Roles.PHARMACIST, Roles.SUPER_ADMIN)) {
            throw new BizException(403, "仅本机构管理员或药师可执行处方审核");
        }
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return lu.getRealName() != null && !lu.getRealName().isEmpty() ? lu.getRealName() : lu.getUsername();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> toLongList(Object v) {
        List<Long> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<Object>) v) {
                Long l = toLong(o);
                if (l != null) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    private static Long toLong(Object v) {
        return v == null ? null : (v instanceof Number ? ((Number) v).longValue() : Long.valueOf(v.toString().trim()));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
