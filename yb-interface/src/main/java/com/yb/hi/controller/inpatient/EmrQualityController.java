package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.EmrQualityRuleDTO;
import com.yb.hi.entity.emr.HisEmrQcDefect;
import com.yb.hi.entity.emr.HisEmrScoreStandard;
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
 * P5a-3 扩展: 内涵质控(五大类型) + 病案首页四维精准质控 + 甲乙丙等级评定 + 缺陷查询/统计 + 评分标准维护。
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

    /* ===================== P5a-3 内涵质控/首页质控/等级评定/缺陷查询(2026-10) ===================== */

    /**
     * 内涵质控(五大类型: 取值/对比/病种/计算/事件):
     * stage=1 运行(签名前) / stage=2 归档, 按规则 qc_stage(0通用/1运行/2归档)过滤,
     * 缺陷落 his_emr_qc_defect 并写入质控节点日志, 返回本次命中缺陷列表。
     */
    @PostMapping("/evaluate-content")
    public R<List<HisEmrQcDefect>> evaluateContent(
            @RequestParam Long recordId,
            @RequestParam(required = false, defaultValue = "1") Integer stage) {
        return R.ok(qualityService.evaluateContent(recordId, stage == null ? 1 : stage));
    }

    /**
     * 病案首页四维精准质控: 完整性(40%)/逻辑性(25%)/真实性(20%)/一致性(15%),
     * 加权总分 ≥90 甲 / 70-89 乙 / <70 丙, 纯查询评估不落库可重复调用。
     */
    @GetMapping("/evaluate-homepage/{casePageId}")
    public R<Map<String, Object>> evaluateHomepage(@PathVariable Long casePageId) {
        return R.ok(qualityService.evaluateHomepage(casePageId));
    }

    /** 甲乙丙等级评定: ≥90甲 / 70-89乙 / <70丙, 一票否决缺陷(severity=3未整改/未豁免)直接丙 */
    @GetMapping("/grade/{recordId}")
    public R<Map<String, Object>> grade(@PathVariable Long recordId) {
        return R.ok(qualityService.gradeRecord(recordId));
    }

    /** 病历缺陷列表(未整改在前, 新近在前, 含整改状态闭环信息) */
    @GetMapping("/defects/{recordId}")
    public R<List<HisEmrQcDefect>> defects(@PathVariable Long recordId) {
        return R.ok(qualityService.listDefects(recordId));
    }

    /** 缺陷统计(按类型/严重度/科室): month 为 yyyy-MM(空取当月), deptId 为空时全院并附科室分布前十 */
    @GetMapping("/defect-stats")
    public R<Map<String, Object>> defectStats(
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String month) {
        return R.ok(qualityService.defectStats(deptId, month));
    }

    /** 评分标准列表(卫健委五类: 时效/完整/逻辑/规范/内涵) */
    @GetMapping("/score-standards")
    public R<List<HisEmrScoreStandard>> scoreStandards() {
        return R.ok(qualityService.listScoreStandards());
    }

    /** 更新评分标准(白名单字段: 名称/基准分/权重/说明/状态; 仅牵头机构管理员) */
    @PutMapping("/score-standard/{id}")
    public R<HisEmrScoreStandard> updateScoreStandard(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历评分标准");
        return qualityService.updateScoreStandard(id, body);
    }
}
