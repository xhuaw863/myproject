package com.yb.hi.service.ris;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.ris.RisQcRuleDTO;
import com.yb.hi.entity.medtech.HisExamReport;
import com.yb.hi.entity.ris.HisRisQcRecord;
import com.yb.hi.entity.ris.HisRisQcRule;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.medtech.HisExamReportMapper;
import com.yb.hi.mapper.ris.HisRisQcRecordMapper;
import com.yb.hi.mapper.ris.HisRisQcRuleMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 质控服务(规则维护 + 报告质控检查 + 评分汇总 + 科室质控统计)。
 * 口径:
 * 1) 规则四类: COMPLETENESS 完整性(字段非空) / TIMELINESS 时效性(报告耗时阈值) /
 *    CONSISTENCY 一致性(申请-报告部位匹配、阳性标志-结论一致) / TERMINOLOGY 术语(禁用模糊表述);
 * 2) rule_expression 为 JSON: {"field":"findings"} / {"maxHours":24} / {"maxMinutes":30} /
 *    {"type":"bodyPartMatch"} / {"forbidden":["考虑"]} 按类型解释;
 * 3) 检查时点(checkPoint): ON_SAVE/ON_SUBMIT/ON_REVIEW/BATCH; 每轮检查先软删该报告旧评分记录,
 *    保证 getReportScore 聚合恒为最新一轮;
 * 4) 评分: 100 分制, 不通过规则按 score_deduction 扣分, 下限 0; >=90 合格(甲级口径);
 * 5) 机构范围: 报告所属机构规则 + org_id=0 全局种子规则并集, 规则 dept_type 空=通用。
 */
@Slf4j
@Service
public class RisQcService {

    /** 合格分数线(甲级 90) */
    private static final int PASS_SCORE = 90;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final HisRisQcRuleMapper ruleMapper;
    private final HisRisQcRecordMapper recordMapper;
    private final HisExamReportMapper reportMapper;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public RisQcService(HisRisQcRuleMapper ruleMapper,
                        HisRisQcRecordMapper recordMapper,
                        HisExamReportMapper reportMapper,
                        JdbcTemplate jdbcTemplate,
                        OrgAccessGuard guard) {
        this.ruleMapper = ruleMapper;
        this.recordMapper = recordMapper;
        this.reportMapper = reportMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /* ================= 报告质控检查 ================= */

    /**
     * 对报告执行质控检查: 报告机构 + org_id=0 全局的启用规则, 按 checkPoint(空=全部时点)过滤,
     * 逐条解析 rule_expression 评估报告字段, 生成评分记录(每轮全量重建)。
     * 返回检查结果列表: { ruleId, ruleCode, ruleName, ruleType, passed, message, detail, scoreDeduction, severity }。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> checkReport(Long reportId, String checkPoint) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisExamReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(400, "报告不存在");
        }
        ReportCtx ctx = loadCtx(report);
        // 规则范围: 报告所属机构 + org_id=0 全局种子; 启用中; checkPoint 匹配; dept_type 通用或匹配报告科室类型
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisRisQcRule> qw =
                Wrappers.<HisRisQcRule>lambdaQuery()
                        .eq(HisRisQcRule::getEnabled, 1)
                        .and(w -> w.eq(HisRisQcRule::getOrgId, report.getOrgId())
                                .or().eq(HisRisQcRule::getOrgId, 0L));
        if (StringUtils.hasText(checkPoint)) {
            qw.eq(HisRisQcRule::getCheckPoint, checkPoint.trim());
        }
        qw.orderByAsc(HisRisQcRule::getId);
        List<HisRisQcRule> rules = ruleMapper.selectList(qw);

        // 每轮全量重建评分记录: 旧行逻辑删除(保留审计), 本轮重新插入
        recordMapper.delete(Wrappers.<HisRisQcRecord>lambdaQuery()
                .eq(HisRisQcRecord::getReportId, reportId));

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> results = new ArrayList<>();
        for (HisRisQcRule rule : rules) {
            if (StringUtils.hasText(rule.getDeptType()) && StringUtils.hasText(ctx.deptType)
                    && !rule.getDeptType().equalsIgnoreCase(ctx.deptType)) {
                continue;
            }
            boolean passed;
            String detail;
            try {
                passed = evaluate(rule, ctx);
                detail = passed ? "通过" : detailOf(rule, ctx);
            } catch (Exception e) {
                // 表达式解析失败按不通过留痕, 不阻断检查流程
                passed = false;
                detail = "规则表达式解析失败: " + e.getMessage();
                log.warn("RIS质控规则解析失败: ruleCode={}, {}", rule.getRuleCode(), e.getMessage());
            }
            HisRisQcRecord rec = new HisRisQcRecord();
            rec.setOrgId(report.getOrgId());
            rec.setReportId(reportId);
            rec.setRuleId(rule.getId());
            rec.setCheckTime(now);
            rec.setPassed(passed ? 1 : 0);
            rec.setDetail(detail);
            recordMapper.insert(rec);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("recordId", rec.getId());
            r.put("ruleId", rule.getId());
            r.put("ruleCode", rule.getRuleCode());
            r.put("ruleName", rule.getRuleName());
            r.put("ruleType", rule.getRuleType());
            r.put("checkPoint", rule.getCheckPoint());
            r.put("severity", rule.getSeverity());
            r.put("passed", passed);
            r.put("message", rule.getMessage());
            r.put("detail", detail);
            r.put("scoreDeduction", passed ? 0 : nz(rule.getScoreDeduction()));
            results.add(r);
        }
        log.info("RIS报告质控检查: reportId={}, checkPoint={}, 规则{}条", reportId, checkPoint, results.size());
        return results;
    }

    /** 报告质控评分(最新一轮): 100 - 未通过规则扣分合计, 下限 0; 附通过/未通过计数与明细。 */
    public Map<String, Object> getReportScore(Long reportId) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        List<HisRisQcRecord> records = recordMapper.selectList(Wrappers.<HisRisQcRecord>lambdaQuery()
                .eq(HisRisQcRecord::getReportId, reportId)
                .orderByAsc(HisRisQcRecord::getId));
        Map<Long, HisRisQcRule> ruleMap = new LinkedHashMap<>();
        for (HisRisQcRecord rec : records) {
            if (!ruleMap.containsKey(rec.getRuleId())) {
                HisRisQcRule rule = ruleMapper.selectById(rec.getRuleId());
                if (rule != null) {
                    ruleMap.put(rec.getRuleId(), rule);
                }
            }
        }
        int score = 100;
        int passedCount = 0;
        int failedCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (HisRisQcRecord rec : records) {
            HisRisQcRule rule = ruleMap.get(rec.getRuleId());
            boolean passed = rec.getPassed() != null && rec.getPassed() == 1;
            if (passed) {
                passedCount++;
            } else {
                failedCount++;
                score -= rule == null ? 0 : nz(rule.getScoreDeduction());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", rec.getRuleId());
            m.put("ruleCode", rule == null ? null : rule.getRuleCode());
            m.put("ruleName", rule == null ? null : rule.getRuleName());
            m.put("ruleType", rule == null ? null : rule.getRuleType());
            m.put("passed", passed);
            m.put("detail", rec.getDetail());
            m.put("scoreDeduction", passed ? 0 : (rule == null ? 0 : nz(rule.getScoreDeduction())));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", reportId);
        out.put("score", Math.max(score, 0));
        out.put("qualified", score >= PASS_SCORE);
        out.put("passLine", PASS_SCORE);
        out.put("passedCount", passedCount);
        out.put("failedCount", failedCount);
        out.put("records", items);
        return out;
    }

    /* ================= 规则 CRUD ================= */

    /** 规则列表(全部含停用; 登录机构 + org_id=0 全局种子并集)。 */
    public List<HisRisQcRule> listRules() {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisRisQcRule> qw =
                Wrappers.<HisRisQcRule>lambdaQuery();
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            qw.and(w -> w.eq(HisRisQcRule::getOrgId, scope).or().eq(HisRisQcRule::getOrgId, 0L));
        }
        qw.orderByAsc(HisRisQcRule::getRuleType)
                .orderByAsc(HisRisQcRule::getRuleCode);
        return ruleMapper.selectList(qw);
    }

    /** 新增规则: 名称/类型/检查时点/表达式必填, 编码空则生成, 同租户防重。 */
    public HisRisQcRule createRule(RisQcRuleDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getRuleName())) {
            throw new BizException(400, "规则名称不能为空");
        }
        if (!StringUtils.hasText(dto.getRuleType())) {
            throw new BizException(400, "规则类型不能为空");
        }
        if (!StringUtils.hasText(dto.getCheckPoint())) {
            throw new BizException(400, "检查时点不能为空");
        }
        if (!StringUtils.hasText(dto.getRuleExpression())) {
            throw new BizException(400, "规则表达式不能为空");
        }
        Long orgId = guard.currentOrgId();
        String code = StringUtils.hasText(dto.getRuleCode())
                ? dto.getRuleCode().trim()
                : "QC" + LocalDateTime.now().format(TS) + (int) (Math.random() * 90 + 10);
        Long dup = ruleMapper.selectCount(Wrappers.<HisRisQcRule>lambdaQuery()
                .eq(HisRisQcRule::getRuleCode, code));
        if (dup != null && dup > 0) {
            throw new BizException("规则编码已存在: " + code);
        }
        HisRisQcRule r = new HisRisQcRule();
        r.setOrgId(orgId);
        r.setRuleCode(code);
        r.setRuleName(dto.getRuleName().trim());
        r.setRuleType(dto.getRuleType().trim());
        r.setDeptType(StringUtils.hasText(dto.getDeptType()) ? dto.getDeptType().trim() : null);
        r.setCheckPoint(dto.getCheckPoint().trim());
        r.setRuleExpression(dto.getRuleExpression());
        r.setSeverity(dto.getSeverity() == null ? 1 : dto.getSeverity());
        r.setScoreDeduction(dto.getScoreDeduction() == null ? 0 : dto.getScoreDeduction());
        r.setMessage(StringUtils.hasText(dto.getMessage()) ? dto.getMessage().trim() : dto.getRuleName().trim());
        r.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        ruleMapper.insert(r);
        log.info("RIS质控规则新增: id={}, code={}, type={}", r.getId(), r.getRuleCode(), r.getRuleType());
        return r;
    }

    /** 编辑规则(编码不允许变更)。 */
    public HisRisQcRule updateRule(Long id, RisQcRuleDTO dto) {
        if (id == null) {
            throw new BizException(400, "规则ID不能为空");
        }
        if (dto == null) {
            throw new BizException(400, "规则内容不能为空");
        }
        HisRisQcRule r = ruleMapper.selectById(id);
        if (r == null) {
            throw new BizException(400, "质控规则不存在");
        }
        if (StringUtils.hasText(dto.getRuleName())) {
            r.setRuleName(dto.getRuleName().trim());
        }
        if (StringUtils.hasText(dto.getRuleType())) {
            r.setRuleType(dto.getRuleType().trim());
        }
        if (dto.getDeptType() != null) {
            r.setDeptType(StringUtils.hasText(dto.getDeptType()) ? dto.getDeptType().trim() : null);
        }
        if (StringUtils.hasText(dto.getCheckPoint())) {
            r.setCheckPoint(dto.getCheckPoint().trim());
        }
        if (StringUtils.hasText(dto.getRuleExpression())) {
            r.setRuleExpression(dto.getRuleExpression());
        }
        if (dto.getSeverity() != null) {
            r.setSeverity(dto.getSeverity());
        }
        if (dto.getScoreDeduction() != null) {
            r.setScoreDeduction(dto.getScoreDeduction());
        }
        if (StringUtils.hasText(dto.getMessage())) {
            r.setMessage(dto.getMessage().trim());
        }
        if (dto.getEnabled() != null) {
            r.setEnabled(dto.getEnabled());
        }
        ruleMapper.updateById(r);
        log.info("RIS质控规则编辑: id={}, code={}", id, r.getRuleCode());
        return ruleMapper.selectById(id);
    }

    /** 逻辑删除规则。 */
    public void deleteRule(Long id) {
        if (id == null) {
            throw new BizException(400, "规则ID不能为空");
        }
        HisRisQcRule r = ruleMapper.selectById(id);
        if (r == null) {
            throw new BizException(400, "质控规则不存在");
        }
        ruleMapper.deleteById(id);
        log.info("RIS质控规则删除: id={}, code={}", id, r.getRuleCode());
    }

    /* ================= 质控统计 ================= */

    /**
     * 质控统计(合格率 + 常见扣分项): 统计期间内有评分记录的报告,
     * 每报告得分 = 100 - 未通过规则扣分合计, >=90 记合格; 常见扣分项按规则聚合未通过次数 TOP10。
     */
    public Map<String, Object> statistics(Long orgId, String startDate, String endDate) {
        Long scope = guard.scopeOrgId(orgId);
        StringBuilder where = new StringBuilder(" WHERE qc.deleted = 0 AND qc.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (scope != null) {
            where.append(" AND qc.org_id = ?");
            args.add(scope);
        }
        if (StringUtils.hasText(startDate)) {
            where.append(" AND qc.check_time >= ?");
            args.add(startDate.trim() + " 00:00:00");
        }
        if (StringUtils.hasText(endDate)) {
            where.append(" AND qc.check_time <= ?");
            args.add(endDate.trim() + " 23:59:59");
        }
        // 每报告得分
        String scoreSql = "SELECT qc.report_id AS reportId,"
                + " (100 - COALESCE(SUM(CASE WHEN qc.passed = 0 THEN IFNULL(r.score_deduction, 0) ELSE 0 END), 0)) AS score"
                + " FROM his_ris_qc_record qc LEFT JOIN his_ris_qc_rule r ON r.id = qc.rule_id AND r.deleted = 0"
                + where + " GROUP BY qc.report_id";
        List<Map<String, Object>> scoreRows = jdbcTemplate.queryForList(scoreSql, args.toArray());
        int totalReports = scoreRows.size();
        int qualifiedReports = 0;
        for (Map<String, Object> row : scoreRows) {
            int score = row.get("score") instanceof Number ? ((Number) row.get("score")).intValue() : 0;
            if (score >= PASS_SCORE) {
                qualifiedReports++;
            }
        }
        // 常见扣分项 TOP10
        String dedSql = "SELECT r.id AS ruleId, r.rule_code AS ruleCode, r.rule_name AS ruleName,"
                + " r.rule_type AS ruleType, COUNT(*) AS failCount,"
                + " SUM(IFNULL(r.score_deduction, 0)) AS totalDeduction"
                + " FROM his_ris_qc_record qc JOIN his_ris_qc_rule r ON r.id = qc.rule_id AND r.deleted = 0"
                + where + " AND qc.passed = 0"
                + " GROUP BY r.id, r.rule_code, r.rule_name, r.rule_type"
                + " ORDER BY failCount DESC, totalDeduction DESC LIMIT 10";
        List<Map<String, Object>> commonDeductions = jdbcTemplate.queryForList(dedSql, args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orgId", scope);
        out.put("startDate", startDate);
        out.put("endDate", endDate);
        out.put("totalReports", totalReports);
        out.put("qualifiedReports", qualifiedReports);
        out.put("unqualifiedReports", totalReports - qualifiedReports);
        out.put("qualifiedRate", totalReports == 0 ? 0.0 : Math.round(qualifiedReports * 1000.0 / totalReports) / 10.0);
        out.put("passLine", PASS_SCORE);
        out.put("commonDeductions", commonDeductions);
        return out;
    }

    /* ================= 规则评估 ================= */

    /** 按规则类型解释 rule_expression 并评估报告上下文。 */
    private boolean evaluate(HisRisQcRule rule, ReportCtx ctx) {
        JSONObject expr = parseExpr(rule.getRuleExpression());
        String type = rule.getRuleType() == null ? "" : rule.getRuleType().trim().toUpperCase();
        switch (type) {
            case "COMPLETENESS":
                return checkCompleteness(expr, ctx);
            case "TIMELINESS":
                return checkTimeliness(expr, ctx);
            case "CONSISTENCY":
                return checkConsistency(expr, ctx);
            case "TERMINOLOGY":
                return checkTerminology(expr, ctx);
            default:
                throw new BizException("未知规则类型: " + rule.getRuleType());
        }
    }

    /** 完整性: {"field":"findings"} 指定报告字段非空(field 缺省时校验所见+结论)。 */
    private boolean checkCompleteness(JSONObject expr, ReportCtx ctx) {
        String field = expr.getString("field");
        if (!StringUtils.hasText(field)) {
            return StringUtils.hasText(ctx.findings) && StringUtils.hasText(ctx.conclusion);
        }
        String v = ctx.field(field.trim());
        return StringUtils.hasText(v);
    }

    /** 时效性: {"maxHours":24} 或 {"maxMinutes":30}; 耗时 = 报告时间(缺省 NOW) - 申请时间(缺省建单时间)。 */
    private boolean checkTimeliness(JSONObject expr, ReportCtx ctx) {
        long limitMinutes;
        Integer maxHours = expr.getInteger("maxHours");
        Integer maxMinutes = expr.getInteger("maxMinutes");
        if (maxHours != null) {
            limitMinutes = maxHours * 60L;
        } else if (maxMinutes != null) {
            limitMinutes = maxMinutes;
        } else {
            limitMinutes = 24 * 60L;
        }
        LocalDateTime start = ctx.applyTime != null ? ctx.applyTime : ctx.createTime;
        LocalDateTime end = ctx.reportTime != null ? ctx.reportTime : LocalDateTime.now();
        if (start == null) {
            return true;
        }
        long costMinutes = Duration.between(start, end).toMinutes();
        return costMinutes <= limitMinutes;
    }

    /** 一致性: {"type":"bodyPartMatch"} 申请-报告部位匹配 / {"type":"positiveConsistent"} 阳性标志-结论一致。 */
    private boolean checkConsistency(JSONObject expr, ReportCtx ctx) {
        String t = expr.getString("type");
        if ("bodyPartMatch".equalsIgnoreCase(t)) {
            if (!StringUtils.hasText(ctx.reqBodyPart) || !StringUtils.hasText(ctx.bodyPart)) {
                return true;
            }
            // 申请部位与报告部位双向包含其一即视为匹配(多部位以逗号/顿号分隔, 允许部分覆盖)
            String req = ctx.reqBodyPart.trim();
            String rep = ctx.bodyPart.trim();
            return rep.contains(req) || req.contains(rep);
        }
        if ("positiveConsistent".equalsIgnoreCase(t)) {
            // 未判定阳性(-1/空)不通过; 阳性(1)结论不得含"未见异常/未见明显"; 阴性(0)结论不得为空
            if (ctx.positiveFlag == null || ctx.positiveFlag < 0) {
                return false;
            }
            if (ctx.positiveFlag == 1) {
                return !(StringUtils.hasText(ctx.conclusion)
                        && (ctx.conclusion.contains("未见异常") || ctx.conclusion.contains("未见明显")));
            }
            return StringUtils.hasText(ctx.conclusion);
        }
        return true;
    }

    /** 术语: {"forbidden":["考虑","大致正常"]} 扫描所见/结论/印象, 命中任一禁用词即不通过。 */
    private boolean checkTerminology(JSONObject expr, ReportCtx ctx) {
        com.alibaba.fastjson2.JSONArray forbidden = expr.getJSONArray("forbidden");
        if (forbidden == null || forbidden.isEmpty()) {
            return true;
        }
        String text = (StringUtils.hasText(ctx.findings) ? ctx.findings : "")
                + (StringUtils.hasText(ctx.conclusion) ? ctx.conclusion : "")
                + (StringUtils.hasText(ctx.impression) ? ctx.impression : "");
        for (int i = 0; i < forbidden.size(); i++) {
            String word = forbidden.getString(i);
            if (StringUtils.hasText(word) && text.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** 不通过详情(按类型给出可读说明, 供 qc_record.detail 留痕)。 */
    private static String detailOf(HisRisQcRule rule, ReportCtx ctx) {
        String type = rule.getRuleType() == null ? "" : rule.getRuleType().trim().toUpperCase();
        try {
            JSONObject expr = parseExpr(rule.getRuleExpression());
            if ("COMPLETENESS".equals(type)) {
                String field = expr.getString("field");
                String name = StringUtils.hasText(field) ? field : "findings/conclusion";
                return "字段为空: " + name;
            }
            if ("TIMELINESS".equals(type)) {
                LocalDateTime start = ctx.applyTime != null ? ctx.applyTime : ctx.createTime;
                LocalDateTime end = ctx.reportTime != null ? ctx.reportTime : LocalDateTime.now();
                long cost = start == null ? 0 : Duration.between(start, end).toMinutes();
                return "报告耗时 " + cost + " 分钟, 超出阈值";
            }
            if ("CONSISTENCY".equals(type)) {
                String t = expr.getString("type");
                if ("positiveConsistent".equalsIgnoreCase(t)) {
                    return ctx.positiveFlag == null || ctx.positiveFlag < 0
                            ? "阳性标志未判定" : "阳性标志与结论不一致";
                }
                return "申请部位[" + safe(ctx.reqBodyPart) + "]与报告部位[" + safe(ctx.bodyPart) + "]不匹配";
            }
            if ("TERMINOLOGY".equals(type)) {
                com.alibaba.fastjson2.JSONArray forbidden = expr.getJSONArray("forbidden");
                List<String> hits = new ArrayList<>();
                String text = (StringUtils.hasText(ctx.findings) ? ctx.findings : "")
                        + (StringUtils.hasText(ctx.conclusion) ? ctx.conclusion : "")
                        + (StringUtils.hasText(ctx.impression) ? ctx.impression : "");
                if (forbidden != null) {
                    for (int i = 0; i < forbidden.size(); i++) {
                        String word = forbidden.getString(i);
                        if (StringUtils.hasText(word) && text.contains(word)) {
                            hits.add(word);
                        }
                    }
                }
                return hits.isEmpty() ? "命中禁用术语" : "命中禁用术语: " + String.join("、", hits);
            }
        } catch (Exception ignore) {
            // detail 生成失败不影响主流程
        }
        return "未通过";
    }

    private static JSONObject parseExpr(String expression) {
        if (!StringUtils.hasText(expression)) {
            return new JSONObject();
        }
        return JSONObject.parseObject(expression);
    }

    /* ================= 报告上下文 ================= */

    /** 报告质控上下文: 主表字段 + RIS 补列(positive_flag/exam_dept_type) + 申请单(部位/申请时间)。 */
    private ReportCtx loadCtx(HisExamReport report) {
        ReportCtx ctx = new ReportCtx();
        ctx.findings = report.getFindings();
        ctx.conclusion = report.getConclusion();
        ctx.reportTime = report.getReportTime();
        ctx.createTime = report.getCreateTime();
        ctx.criticalFlag = report.getCriticalFlag();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.impression, r.technique, r.body_part, r.positive_flag, r.exam_dept_type, r.modality,"
                        + " r.request_id, q.apply_time, q.body_part AS req_body_part, q.modality AS req_modality,"
                        + " q.is_urgent"
                        + " FROM his_exam_report r"
                        + " LEFT JOIN his_exam_request q ON q.id = r.request_id AND q.deleted = 0"
                        + " WHERE r.id = ? AND r.tenant_id = ? AND r.deleted = 0",
                report.getId(), tenantId());
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            ctx.impression = str(row.get("impression"));
            ctx.technique = str(row.get("technique"));
            ctx.bodyPart = str(row.get("body_part"));
            ctx.deptType = str(row.get("exam_dept_type"));
            ctx.modality = str(row.get("modality"));
            Object pf = row.get("positive_flag");
            ctx.positiveFlag = pf instanceof Number ? ((Number) pf).intValue() : null;
            Object at = row.get("apply_time");
            ctx.applyTime = at instanceof java.sql.Timestamp ? ((java.sql.Timestamp) at).toLocalDateTime() : null;
            ctx.reqBodyPart = str(row.get("req_body_part"));
            ctx.reqModality = str(row.get("req_modality"));
            Object iu = row.get("is_urgent");
            ctx.urgent = iu instanceof Number && ((Number) iu).intValue() == 1;
        }
        return ctx;
    }

    /** 报告质控评估上下文(跨主表 + RIS 补列 + 申请单字段)。 */
    private static class ReportCtx {
        String findings;
        String conclusion;
        String impression;
        String technique;
        String bodyPart;
        String deptType;
        String modality;
        String reqBodyPart;
        String reqModality;
        Integer positiveFlag;
        Integer criticalFlag;
        boolean urgent;
        LocalDateTime reportTime;
        LocalDateTime createTime;
        LocalDateTime applyTime;

        String field(String name) {
            if (name == null) {
                return null;
            }
            switch (name.trim().toLowerCase()) {
                case "findings": return findings;
                case "conclusion": return conclusion;
                case "impression": return impression;
                case "technique": return technique;
                case "body_part": return bodyPart;
                default: return null;
            }
        }
    }

    /* ================= 辅助 ================= */

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String safe(String s) {
        return StringUtils.hasText(s) ? s : "-";
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}

