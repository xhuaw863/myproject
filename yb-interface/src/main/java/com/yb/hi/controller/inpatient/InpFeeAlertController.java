package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.inpatient.HisInpFeeAlert;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.InpFeeAlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 住院费用预警接口: 预警分页/生成/处理, 预交金不足患者清单与预警统计(机构隔离)。
 */
@RestController
@RequestMapping("/api/his/inp/fee-alert")
public class InpFeeAlertController {

    private final InpFeeAlertService inpFeeAlertService;
    private final OrgAccessGuard guard;

    public InpFeeAlertController(InpFeeAlertService inpFeeAlertService, OrgAccessGuard guard) {
        this.inpFeeAlertService = inpFeeAlertService;
        this.guard = guard;
    }

    /** 预警分页(visitId/alertType 可选) */
    @GetMapping("/list")
    public R<IPage<HisInpFeeAlert>> list(@RequestParam(required = false) Long visitId,
                                         @RequestParam(required = false) Integer alertType,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "10") long size) {
        return inpFeeAlertService.listAlerts(visitId, alertType,
                new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)));
    }

    /** 手动生成预警(自动链路会去重未处理预警) */
    @PostMapping("/create")
    public R<Void> create(@RequestBody Map<String, Object> body) {
        Long visitId = body == null || body.get("visitId") == null ? null
                : Long.valueOf(String.valueOf(body.get("visitId")));
        Integer alertType = body == null || body.get("alertType") == null ? null
                : Integer.valueOf(String.valueOf(body.get("alertType")));
        Long chargeDetailId = body == null || body.get("chargeDetailId") == null ? null
                : Long.valueOf(String.valueOf(body.get("chargeDetailId")));
        BigDecimal alertAmount = body == null || body.get("alertAmount") == null ? null
                : new BigDecimal(String.valueOf(body.get("alertAmount")));
        BigDecimal threshold = body == null || body.get("thresholdAmount") == null ? null
                : new BigDecimal(String.valueOf(body.get("thresholdAmount")));
        return inpFeeAlertService.createAlert(visitId, alertType, chargeDetailId, alertAmount, threshold);
    }

    /** 处理预警(1放行 2拦截) */
    @PutMapping("/{id}/handle")
    public R<Void> handle(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer result = body == null || body.get("result") == null ? null
                : Integer.valueOf(String.valueOf(body.get("result")));
        Object reason = body == null ? null : body.get("reason");
        return inpFeeAlertService.handleAlert(id, result, reason == null ? null : String.valueOf(reason));
    }

    /** 预交金不足预警患者清单(在院且余额低于预警线) */
    @GetMapping("/warning-patients")
    public R<List<Map<String, Object>>> warningPatients(@RequestParam(required = false) Long orgId) {
        return inpFeeAlertService.getWarningPatients(guard.scopeOrgId(orgId));
    }

    /** 预警统计(按类型分组: 总数/未处理/已处理) */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) Long orgId) {
        return inpFeeAlertService.getAlertStats(guard.scopeOrgId(orgId));
    }
}
