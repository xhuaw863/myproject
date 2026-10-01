package com.yb.hi.controller.mr;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrQualityDiffService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 质控前后对比接口(P3-B): 单份对比 / 批量对比汇总。
 * 只读计算, 不落库。
 */
@RestController
@RequestMapping("/api/his/mr/quality-diff")
public class MrQualityDiffController {

    private final MrQualityDiffService diffService;

    public MrQualityDiffController(MrQualityDiffService diffService) {
        this.diffService = diffService;
    }

    /** 单份病案质控前后对比。 */
    @GetMapping("/{visitId}")
    public R<Map<String, Object>> diff(@PathVariable Long visitId) {
        return R.ok(diffService.diff(visitId));
    }

    /** 批量对比: body {visitIds:[...]}。 */
    @PostMapping("/batch")
    public R<List<Map<String, Object>>> batch(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("visitIds");
        List<Long> visitIds = new java.util.ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Number) {
                    visitIds.add(((Number) o).longValue());
                } else if (o != null) {
                    try { visitIds.add(Long.parseLong(o.toString().trim())); } catch (Exception ignore) { }
                }
            }
        }
        return R.ok(diffService.batchDiff(visitIds));
    }
}
