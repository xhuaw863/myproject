package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.mr.HisMrQualityErr;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrReviewService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 首页质量审核与确认锁定接口: 审核队列 / 批量审核 / 单份错误项 / 确认 / 锁定 / 解锁。
 */
@RestController
@RequestMapping("/api/his/mr/review")
public class MrReviewController {

    private final MrReviewService reviewService;

    public MrReviewController(MrReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 审核队列(auditStatus/catalogStatus 过滤)。 */
    @GetMapping("/queue")
    public R<IPage<Map<String, Object>>> queue(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) Integer auditStatus,
                                               @RequestParam(required = false) Integer catalogStatus) {
        return R.ok(reviewService.queuePage(page, size, auditStatus, catalogStatus));
    }

    /** 批量审核: body {visitIds:[]}, 返回汇总。 */
    @PostMapping("/batch-audit")
    @SuppressWarnings("unchecked")
    public R<Map<String, Object>> batchAudit(@RequestBody Map<String, Object> body) {
        return R.ok(reviewService.batchAudit(toLongs((List<Object>) body.get("visitIds"))));
    }

    /** 单份错误项(审核页右栏/定位)。 */
    @GetMapping("/errors/{visitId}")
    public R<List<HisMrQualityErr>> errors(@PathVariable Long visitId) {
        return R.ok(reviewService.errorsOf(visitId));
    }

    /** 审核确认: body {opinion} */
    @PostMapping("/{visitId}/confirm")
    public R<Void> confirm(@PathVariable Long visitId, @RequestBody(required = false) Map<String, Object> body) {
        String opinion = body == null || body.get("opinion") == null ? null : String.valueOf(body.get("opinion"));
        reviewService.confirm(visitId, opinion);
        return R.ok();
    }

    /** 锁定。 */
    @PostMapping("/{visitId}/lock")
    public R<Void> lock(@PathVariable Long visitId) {
        reviewService.lock(visitId);
        return R.ok();
    }

    /** 解锁: body {reason} */
    @PostMapping("/{visitId}/unlock")
    public R<Void> unlock(@PathVariable Long visitId, @RequestBody Map<String, Object> body) {
        String reason = body == null ? null : (String) body.get("reason");
        reviewService.unlock(visitId, reason);
        return R.ok();
    }

    private static List<Long> toLongs(List<Object> raw) {
        List<Long> out = new ArrayList<>();
        if (raw != null) {
            for (Object o : raw) {
                if (o == null) {
                    continue;
                }
                try {
                    out.add(o instanceof Number ? ((Number) o).longValue() : Long.parseLong(String.valueOf(o).trim()));
                } catch (NumberFormatException ignore) {
                    /* 跳过非法项 */
                }
            }
        }
        return out;
    }
}
