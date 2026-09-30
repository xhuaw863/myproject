package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.EmrQualityRuleDTO;
import com.yb.hi.entity.inpatient.HisEmrQualityRule;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.EmrQualityService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 病历质控接口: 规则维护(仅牵头机构管理员) + 单份病历评分 + 科室质控排名 / 质控报告。
 * 33 条标准规则由 EmrQualityService 启动时按租户幂等播种(@Order(8))。
 */
@RestController
@RequestMapping("/api/his/emr/quality")
public class EmrQualityController {

    private final EmrQualityService qualityService;
    private final OrgAccessGuard guard;

    public EmrQualityController(EmrQualityService qualityService, OrgAccessGuard guard) {
        this.qualityService = qualityService;
        this.guard = guard;
    }

    /** 规则列表(recordType/ruleType/status 可选筛选) */
    @GetMapping("/rules")
    public R<List<HisEmrQualityRule>> rules(
            @RequestParam(required = false) Integer recordType,
            @RequestParam(required = false) Integer ruleType,
            @RequestParam(required = false) Integer status) {
        return qualityService.listRules(recordType, ruleType, status);
    }

    /** 新建质控规则(仅牵头机构管理员) */
    @PostMapping("/rule")
    public R<HisEmrQualityRule> create(@RequestBody EmrQualityRuleDTO dto) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历质控规则");
        return qualityService.createRule(dto);
    }

    /** 更新质控规则(仅牵头机构管理员) */
    @PutMapping("/rule/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody EmrQualityRuleDTO dto) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历质控规则");
        return qualityService.updateRule(id, dto);
    }

    /** 删除质控规则(逻辑删除; 仅牵头机构管理员) */
    @DeleteMapping("/rule/{id}")
    public R<Void> remove(@PathVariable Long id) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历质控规则");
        return qualityService.removeRule(id);
    }

    /** 单份病历质控评分(100分起扣, 一票否决直接0分; 结果持久化后返回评分明细) */
    @PostMapping("/evaluate/{recordId}")
    public R<Map<String, Object>> evaluate(@PathVariable Long recordId) {
        return R.ok(qualityService.evaluateQuality(recordId));
    }

    /** 科室质控排名(按平均分降序; orgId 非牵头机构锁定本机构) */
    @GetMapping("/ranking")
    public R<List<Map<String, Object>>> ranking(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return qualityService.getDeptQualityRanking(orgId, startDate, endDate);
    }

    /** 质控报告(总量/均分/不合格率/一票否决 + 分数段与问题分布 + 科室排名) */
    @GetMapping("/report")
    public R<Map<String, Object>> report(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return qualityService.getQualityReport(orgId, startDate, endDate);
    }
}
