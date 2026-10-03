package com.yb.hi.controller.emr;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrLevelAssessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 电子病历应用水平分级自评接口(病历P7b-2): 快速评估(总分+等级) / 详细评估(8维度明细)。
 *
 * 只读评估接口; 鉴权走 AuthInterceptor 统一 JWT, 数据范围由服务层按登录身份收口
 * (牵头机构/平台超管=全租户, 非牵头锁定本机构; JdbcTemplate SQL 显式携带 tenant_id)。
 */
@RestController
@RequestMapping("/api/emr/level-assess")
public class EmrLevelAssessController {

    private final EmrLevelAssessService levelAssessService;

    public EmrLevelAssessController(EmrLevelAssessService levelAssessService) {
        this.levelAssessService = levelAssessService;
    }

    /**
     * 快速评估: GET /api/emr/level-assess/assess
     * 返回 {totalScore, maxScore, level, levelText, assessTime}。
     */
    @GetMapping("/assess")
    public R<Map<String, Object>> assess() {
        return R.ok(levelAssessService.assess());
    }

    /**
     * 详细评估: GET /api/emr/level-assess/detail
     * 返回 8 个维度明细 [{name, score, maxScore, metric, detail, suggestion}]。
     */
    @GetMapping("/detail")
    public R<List<Map<String, Object>>> detail() {
        return R.ok(levelAssessService.assessDetail());
    }
}
