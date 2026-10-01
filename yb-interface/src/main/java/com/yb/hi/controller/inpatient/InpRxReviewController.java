package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.inpatient.HisRxReview;
import com.yb.hi.entity.inpatient.HisRxReviewRule;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.HisRxReviewService;
import com.yb.hi.service.inpatient.RxReviewRuleEngine;
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
 * 住院处方点评接口(T2 阶段3): 点评单列表 / 待点评池 / 发起点评 / 点评提交 / 删除。
 * 机构归属与越权守卫在 Service 层(见 HisRxReviewService)。
 */
@RestController
@RequestMapping("/api/his/inp/rx-review")
public class InpRxReviewController {

    private final HisRxReviewService rxReviewService;
    private final RxReviewRuleEngine ruleEngine;

    public InpRxReviewController(HisRxReviewService rxReviewService, RxReviewRuleEngine ruleEngine) {
        this.rxReviewService = rxReviewService;
        this.ruleEngine = ruleEngine;
    }

    /** 点评单列表(inpVisitId 可选按就诊过滤, status 可选 1待点评 2已点评, 分页) */
    @GetMapping("/list")
    public R<IPage<HisRxReview>> list(
            @RequestParam(required = false) Long inpVisitId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(rxReviewService.listReviews(inpVisitId, status, page, size));
    }

    /** 待点评池: 指定就诊下尚未点评的药品医嘱候选(inpVisitId 必传) */
    @GetMapping("/pool")
    public R<List<Map<String, Object>>> pool(@RequestParam Long inpVisitId) {
        return R.ok(rxReviewService.pool(inpVisitId));
    }

    /** 发起点评: 从医嘱创建点评单(冻结医嘱要素快照, status=1待点评) */
    @PostMapping("/initiate")
    public R<HisRxReview> initiate(@RequestBody Map<String, Object> body) {
        Long orderId = body == null || body.get("orderId") == null ? null
                : Long.valueOf(String.valueOf(body.get("orderId")));
        return R.ok(rxReviewService.initiate(orderId));
    }

    /** 点评提交: 填写结论/问题类型/评分/意见, 记录点评人与时间, status→2已点评 */
    @PostMapping("/{id}/review")
    public R<HisRxReview> review(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer result = bodyInt(body, "result");
        String problemType = body == null || body.get("problemType") == null ? null : String.valueOf(body.get("problemType"));
        Integer score = bodyInt(body, "score");
        String comment = body == null || body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        return R.ok(rxReviewService.submitReview(id, result, problemType, score, comment));
    }

    /** 规则引擎清单(只读展示/透明; 含停用)——T2阶段5-2 */
    @GetMapping("/rules")
    public R<List<HisRxReviewRule>> rules() {
        return R.ok(ruleEngine.listRules());
    }

    /** 删除点评单(逻辑删除) */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        rxReviewService.removeReview(id);
        return R.ok();
    }

    private static Integer bodyInt(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null || String.valueOf(body.get(key)).isEmpty()) {
            return null;
        }
        return Integer.valueOf(String.valueOf(body.get(key)));
    }
}
