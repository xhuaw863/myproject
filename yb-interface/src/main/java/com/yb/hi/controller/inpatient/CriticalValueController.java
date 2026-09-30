package com.yb.hi.controller.inpatient;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.CriticalValueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 住院危急值闭环接口(T37): 结果上报告警 / 处理记录分页 / 通知-确认-处置-关闭流转 / 规则 CRUD / 未处理计数。
 * 说明: 状态推进全部由服务层乐观更新(WHERE status=from)保证并发安全; 确认与处置前端先行电子签名。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/his/inp/critical-value")
public class CriticalValueController {

    private final CriticalValueService criticalValueService;

    /** 模拟检验结果上报: body {visitId, itemCode, itemName, resultValue, unit} → 匹配规则命中即告警。 */
    @PostMapping("/check")
    public R<Map<String, Object>> check(@RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new BizException(400, "请求体不能为空");
        }
        return R.ok(criticalValueService.checkAndAlert(
                toLong(body.get("visitId")),
                str(body.get("itemCode")),
                str(body.get("itemName")),
                str(body.get("resultValue")),
                str(body.get("unit"))));
    }

    /** 处理记录分页: 可选 wardId / status 过滤, 返回 {total, rows}。 */
    @GetMapping("/records")
    public R<Map<String, Object>> records(@RequestParam(required = false) Long wardId,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return R.ok(criticalValueService.listRecords(wardId, status, page, size));
    }

    /** 标记已通知(1→2)。 */
    @PostMapping("/{id}/notify")
    public R<Void> notify(@PathVariable Long id) {
        criticalValueService.notifyDoctor(id);
        return R.ok();
    }

    /** 医生确认(2→3, 前端已先行电子签名)。 */
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable Long id) {
        criticalValueService.confirmByDoctor(id);
        return R.ok();
    }

    /** 记录处置措施(3→4): body {measures: "处置措施文本"}。 */
    @PostMapping("/{id}/handle")
    public R<Void> handle(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String measures = body == null ? null : str(body.get("measures"));
        criticalValueService.handleCriticalValue(id, measures);
        return R.ok();
    }

    /** 关闭(4→5)。 */
    @PostMapping("/{id}/close")
    public R<Void> close(@PathVariable Long id) {
        criticalValueService.closeCriticalValue(id);
        return R.ok();
    }

    /** 规则列表。 */
    @GetMapping("/rules")
    public R<List<Map<String, Object>>> rules() {
        return R.ok(criticalValueService.listRules());
    }

    /** 保存规则(有 id 更新, 无 id 新增)。 */
    @PostMapping("/rules")
    public R<Void> saveRule(@RequestBody Map<String, Object> rule) {
        criticalValueService.saveRule(rule);
        return R.ok();
    }

    /** 删除规则(软删)。 */
    @DeleteMapping("/rules/{id}")
    public R<Void> deleteRule(@PathVariable Long id) {
        criticalValueService.deleteRule(id);
        return R.ok();
    }

    /** 未处理数量(status IN 1,2,3), 供医生站/护士站看板待办计数; 可选 wardId。 */
    @GetMapping("/unhandled-count")
    public R<Integer> unhandledCount(@RequestParam(required = false) Long wardId) {
        return R.ok(criticalValueService.getUnhandledCount(wardId));
    }

    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Long.valueOf(s);
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
