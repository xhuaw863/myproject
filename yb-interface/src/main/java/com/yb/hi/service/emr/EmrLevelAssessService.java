package com.yb.hi.service.emr;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电子病历系统应用水平分级自评引擎(病历P7b-2): 按《电子病历系统应用水平分级评价标准》8个维度自动评分,
 * 每维度 10 分共 80 分, 总分映射五级(未达标/一级~五级)。
 *
 * 8 个维度(数据驱动, 只读不落库):
 * 1.病历创建与编辑(结构化模板覆盖) 2.数据采集与存储(结构化存储率) 3.质控与安全(质控规则/评分标准)
 * 4.互操作性(FHIR/CDA 导出 + Webhook + 扁平化视图) 5.知识库应用(宏变量/模板/规则)
 * 6.统计分析(归档/质控/时效/工作量数据) 7.安全管理(RBAC/审计/封存/签名链) 8.基础设施(版本/PDF/SSE/定时任务)。
 *
 * 数据口径: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 一律显式携带 tenant_id;
 * 含 org_id 的表按登录身份收口机构范围(牵头机构/平台超管=全租户, 非牵头锁定本机构; 口径同 EmrInteropService.readScopeOrg);
 * 全局真源表(sys_menu 无 tenant_id/org_id)仅按软删与状态统计。
 *
 * 容错约定: 表/列/视图缺失或单条 SQL 失败时对应指标按 0 分兜底并告警, 任一维度异常不阻断整体评估;
 * 守卫拒绝(403)与租户上下文缺失照常上抛(属策略/环境异常, 不做评分兜底)。
 */
@Slf4j
@Service
public class EmrLevelAssessService {

    /** 单维度满分 */
    private static final int DIM_MAX = 10;

    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    /** FHIR R4 / WS/T 500 CDA 导出能力探测(required=false 防御并行会话/启动时序; 缺失时互操作维度对应项 0 分) */
    @Autowired(required = false)
    private EmrExportService exportService;
    /** 时效质控定时任务探测(EmrTimelinessService 每 30 分钟扫描, 参考 EmrArchiveService 对 EmrVersionService 的防御式注入) */
    @Autowired(required = false)
    private EmrTimelinessService timelinessService;
    /** 归档扫描定时任务探测(EmrArchiveService 每小时扫描) */
    @Autowired(required = false)
    private EmrArchiveService archiveService;

    public EmrLevelAssessService(JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /* ==================== 对外入口 ==================== */

    /** 快速评估: 8 维度汇总总分 + 等级映射 */
    public Map<String, Object> assess() {
        List<Map<String, Object>> dimensions = assessDetail();
        int totalScore = 0;
        int maxScore = 0;
        for (Map<String, Object> d : dimensions) {
            totalScore += intOf(d.get("score"));
            maxScore += intOf(d.get("maxScore"));
        }
        int level = calcLevel(totalScore);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScore", totalScore);
        result.put("maxScore", maxScore);
        result.put("level", level);
        result.put("levelText", levelText(level));
        result.put("assessTime", LocalDateTime.now().toString());
        return result;
    }

    /** 详细评估: 8 个维度明细 [{name, score, maxScore, metric, detail, suggestion}] */
    public List<Map<String, Object>> assessDetail() {
        /* 租户上下文缺失属环境异常, 快速失败(评分无意义, 不做兜底) */
        TenantContext.require();
        List<Map<String, Object>> dims = new ArrayList<>();
        dims.add(assessCreation());       // 1.病历创建与编辑
        dims.add(assessDataStorage());    // 2.数据采集与存储
        dims.add(assessQuality());        // 3.质控与安全
        dims.add(assessInterop());        // 4.互操作性
        dims.add(assessKnowledge());      // 5.知识库应用
        dims.add(assessStatistics());     // 6.统计分析
        dims.add(assessSecurity());       // 7.安全管理
        dims.add(assessInfrastructure()); // 8.基础设施
        return dims;
    }

    /* ==================== 8 个维度评分 ==================== */

    /**
     * 维度1: 病历创建与编辑(10分) — 结构化模板数量分档:
     * &gt;50→10, 31-50→8, 11-30→6, 1-10→4, 0→0。
     */
    private Map<String, Object> assessCreation() {
        String name = "病历创建与编辑";
        try {
            long templates = countScoped("his_emr_template", null);
            int score;
            String suggestion;
            if (templates > 50) {
                score = 10;
                suggestion = "模板覆盖充分";
            } else if (templates > 30) {
                score = 8;
                suggestion = "建议增加专科模板覆盖";
            } else if (templates > 10) {
                score = 6;
                suggestion = "模板数量偏少，建议按科室补充";
            } else if (templates > 0) {
                score = 4;
                suggestion = "模板严重不足";
            } else {
                score = 0;
                suggestion = "未配置病历模板";
            }
            return buildDim(name, score, DIM_MAX, "结构化模板覆盖率", "模板总数: " + templates, suggestion);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            return fallbackDim(name, e);
        }
    }

    /**
     * 维度2: 数据采集与存储(10分) — 住院病历结构化存储率(structure_data 非空占比):
     * &gt;80%→10, &gt;60%→8, &gt;40%→6, &gt;20%→4, 其余→2; 无病历数据时给基础分 2。
     */
    private Map<String, Object> assessDataStorage() {
        String name = "数据采集与存储";
        try {
            long total = countScoped("his_inp_medical_record", null);
            long structured = countScoped("his_inp_medical_record",
                    "structure_data IS NOT NULL AND structure_data <> ''");
            double ratio = total == 0 ? 0 : structured * 100.0 / total;
            int score;
            String suggestion;
            if (total == 0) {
                score = 2;
                suggestion = "暂无住院病历数据，建议启用结构化书写积累数据";
            } else if (ratio > 80) {
                score = 10;
                suggestion = "结构化存储率优秀，数据采集与存储充分";
            } else if (ratio > 60) {
                score = 8;
                suggestion = "结构化存储率良好，建议扩大结构化文书覆盖范围";
            } else if (ratio > 40) {
                score = 6;
                suggestion = "结构化存储率一般，建议医生站默认结构化模板书写";
            } else if (ratio > 20) {
                score = 4;
                suggestion = "结构化存储率偏低，建议培训并强化结构化录入";
            } else {
                score = 2;
                suggestion = "结构化存储率过低，请全面启用结构化模板";
            }
            String detail = String.format("结构化病历 %d/%d (%.1f%%)", structured, total, ratio);
            return buildDim(name, score, DIM_MAX, "结构化存储率", detail, suggestion);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            return fallbackDim(name, e);
        }
    }

    /**
     * 维度3: 质控与安全(10分) — 启用质控规则数分档:
     * &gt;100→10, &gt;50→8, &gt;20→6, &gt;0→4, 0→0; 评分标准(卫健委五类)作为配套明细展示。
     */
    private Map<String, Object> assessQuality() {
        String name = "质控与安全";
        try {
            long rules = countScoped("his_emr_quality_rule", "status = 1");
            long standards = countScoped("his_emr_score_standard", "status = 1");
            int score;
            String suggestion;
            if (rules > 100) {
                score = 10;
                suggestion = "质控规则体系完备";
            } else if (rules > 50) {
                score = 8;
                suggestion = "质控规则较充分，建议补充内涵质控规则";
            } else if (rules > 20) {
                score = 6;
                suggestion = "质控规则数量一般，建议按四类(完整/时限/逻辑/规范)补齐";
            } else if (rules > 0) {
                score = 4;
                suggestion = "质控规则偏少，质控覆盖不足";
            } else {
                score = 0;
                suggestion = "未启用质控规则";
            }
            String detail = "启用质控规则 " + rules + " 条, 评分标准 " + standards + " 项";
            return buildDim(name, score, DIM_MAX, "质控规则覆盖", detail, suggestion);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            return fallbackDim(name, e);
        }
    }

    /**
     * 维度4: 互操作性(10分) — FHIR R4 导出 +3, WS/T 500 CDA 导出 +3,
     * Webhook 实时订阅(启用&gt;0) +2, 扁平化互操作视图 v_emr_element_flat +2。
     */
    private Map<String, Object> assessInterop() {
        String name = "互操作性";
        try {
            int score = 0;
            List<String> ok = new ArrayList<>();
            List<String> miss = new ArrayList<>();
            /* FHIR R4 / WS/T 500 CDA 两类标准导出(EmrExportService 同源承载 exportFhir/exportCda) */
            if (exportService != null) {
                score += 3;
                ok.add("FHIR R4 导出");
                score += 3;
                ok.add("WS/T 500 CDA 导出");
            } else {
                miss.add("FHIR/CDA 标准导出服务");
            }
            /* Webhook 实时推送(启用订阅 >0) */
            long webhooks = countScoped("his_emr_webhook_subscription", "status = 1");
            if (webhooks > 0) {
                score += 2;
                ok.add("Webhook 订阅 " + webhooks + " 个");
            } else {
                miss.add("Webhook 订阅");
            }
            /* 扁平化互操作视图(P7b-1, 建库期 VIEW 权限受限时可能缺失) */
            if (viewExists("v_emr_element_flat")) {
                score += 2;
                ok.add("扁平化互操作视图");
            } else {
                miss.add("扁平化视图 v_emr_element_flat");
            }
            score = Math.min(score, DIM_MAX);
            String detail = "已具备: " + (ok.isEmpty() ? "无" : String.join("、", ok));
            String suggestion = miss.isEmpty() ? "互操作能力完备" : "建议补齐: " + String.join("、", miss);
            return buildDim(name, score, DIM_MAX, "标准导出与集成覆盖", detail, suggestion);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            return fallbackDim(name, e);
        }
    }

    /**
     * 维度5: 知识库应用(10分) — 宏变量(≥10→4, ≥5→3, ≥1→2) + 模板库(>0→3) + 启用质控规则(>0→3)。
     */
    private Map<String, Object> assessKnowledge() {
        String name = "知识库应用";
        try {
            long macros = countScoped("his_emr_macro", null);
            long templates = countScoped("his_emr_template", null);
            long rules = countScoped("his_emr_quality_rule", "status = 1");
            int score = 0;
            List<String> miss = new ArrayList<>();
            if (macros >= 10) {
                score += 4;
            } else if (macros >= 5) {
                score += 3;
            } else if (macros > 0) {
                score += 2;
            } else {
                miss.add("宏变量");
            }
            if (templates > 0) {
                score += 3;
            } else {
                miss.add("模板库");
            }
            if (rules > 0) {
                score += 3;
            } else {
                miss.add("质控规则");
            }
            score = Math.min(score, DIM_MAX);
            String detail = "宏变量 " + macros + " 项, 模板库 " + templates + " 个, 启用质控规则 " + rules + " 条";
            String suggestion = miss.isEmpty() ? "知识库要素齐备" : "建议补充: " + String.join("、", miss);
            return buildDim(name, score, DIM_MAX, "知识资产覆盖", detail, suggestion);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            return fallbackDim(name, e);
        }
    }

    /**
     * 维度6: 统计分析(10分) — 四类统计域的数据供给:
     * 归档统计(已归档病历>0) +3, 质控统计(质控缺陷>0) +3, 时效统计(时效缺陷>0) +2, 工作量统计(数据元>0) +2。
     */
    private Map<String, Object> assessStatistics() {
        String name = "统计分析";
        try {
            long archived = countScoped("his_inp_medical_record", "archive_time IS NOT NULL");
            long defects = countScoped("his_emr_qc_defect", null);
            long overdue = countScoped("his_emr_qc_defect", "defect_type = '时效'");
            long elements = countScoped("his_emr_element", null);
            int score = 0;
            List<String> miss = new ArrayList<>();
            if (archived > 0) {
                score += 3;
            } else {
                miss.add("归档统计(无归档数据)");
            }
            if (defects > 0) {
                score += 3;
            } else {
                miss.add("质控统计(无质控缺陷)");
            }
            if (overdue > 0) {
                score += 2;
            } else {
                miss.add("时效统计(无时效缺陷)");
            }
            if (elements > 0) {
                score += 2;
            } else {
                miss.add("工作量统计(无数据元)");
            }
            String detail = String.format("归档数据 %d | 质控缺陷 %d | 时效缺陷 %d | 数据元 %d",
                    archived, defects, overdue, elements);
            String suggestion = miss.isEmpty() ? "统计维度数据覆盖全面" : "待积累: " + String.join("、", miss);
            return buildDim(name, score, DIM_MAX, "统计报表数据覆盖", detail, suggestion);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            return fallbackDim(name, e);
        }
    }

    /**
     * 维度7: 安全管理(10分) — RBAC 菜单体系 +2, 审计留痕(独立审计日志, 无则版本快照) +3/2,
     * 封存能力(seal_time 列就绪) +3, 签名链三级(规则链或实际签署环节 ≥3) +4; 合计按 10 分封顶。
     */
    private Map<String, Object> assessSecurity() {
        String name = "安全管理";
        try {
            int raw = 0;
            List<String> parts = new ArrayList<>();
            /* RBAC 菜单权限体系(sys_menu 为全局真源, 无 tenant_id/org_id 列, 仅按软删与状态统计) */
            long menus = queryCount("SELECT COUNT(*) FROM sys_menu WHERE deleted = 0 AND status = 1");
            if (menus > 0) {
                raw += 2;
                parts.add("RBAC 菜单 " + menus + " 项");
            } else {
                parts.add("RBAC 菜单缺失");
            }
            /* 审计日志留痕(his_emr_audit_log; 无独立审计时回退版本快照留痕计部分分) */
            long audits = countScoped("his_emr_audit_log", null);
            long versions = countTenant("his_emr_version", true, null);
            if (audits > 0) {
                raw += 3;
                parts.add("审计日志 " + audits + " 条");
            } else if (versions > 0) {
                raw += 2;
                parts.add("版本快照留痕 " + versions + " 条(无独立审计日志)");
            } else {
                parts.add("无审计留痕");
            }
            /* 封存能力: P7a-2 封存链(状态 6=已封存)结构就绪 */
            boolean sealCapability = columnExists("his_inp_medical_record", "seal_time");
            long sealed = sealCapability ? countScoped("his_inp_medical_record", "status = 6") : 0;
            if (sealCapability) {
                raw += 3;
                parts.add("封存能力就绪(已封存 " + sealed + " 份)");
            } else {
                parts.add("封存能力未就绪");
            }
            /* 签名链: 签名规则链或实际签署记录覆盖环节数 ≥3 视为三级签名模式 */
            long tenantId = TenantContext.require();
            long ruleStages = queryCount("SELECT COUNT(DISTINCT stage) FROM his_emr_signature_rule"
                    + " WHERE tenant_id = ? AND deleted = 0 AND required = 1", tenantId);
            long dataStages = queryCount("SELECT COUNT(DISTINCT stage) FROM his_emr_signature"
                    + " WHERE tenant_id = ? AND deleted = 0", tenantId);
            long stages = Math.max(ruleStages, dataStages);
            if (stages >= 3) {
                raw += 4;
                parts.add("三级签名链 " + stages + " 环节");
            } else if (stages > 0) {
                raw += 2;
                parts.add("签名链 " + stages + " 环节(不足三级)");
            } else {
                parts.add("签名链未部署");
            }
            int score = Math.min(raw, DIM_MAX);
            String suggestion = score >= DIM_MAX ? "安全管理能力完备"
                    : (sealCapability ? "建议完善审计留痕与三级签名链" : "建议部署封存/审计/签名链能力");
            return buildDim(name, score, DIM_MAX, "权限/审计/封存/签名链", String.join(", ", parts), suggestion);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            return fallbackDim(name, e);
        }
    }

    /**
     * 维度8: 基础设施(10分) — 版本管理(his_emr_version 有快照) +3, PDF 归档(pdf_path 非空) +3,
     * SSE 实时通知(事件类型覆盖) +2, 定时任务(时效质控/归档扫描后台任务) +2。
     */
    private Map<String, Object> assessInfrastructure() {
        String name = "基础设施";
        try {
            int score = 0;
            List<String> parts = new ArrayList<>();
            /* 版本管理: 病历版本快照(表无 org_id 列, 按租户统计) */
            long versions = countTenant("his_emr_version", true, null);
            if (versions > 0) {
                score += 3;
                parts.add("版本快照 " + versions + " 条");
            } else {
                parts.add("无版本快照");
            }
            /* PDF 归档: 归档 PDF 已生成 */
            long pdfs = countScoped("his_inp_medical_record", "pdf_path IS NOT NULL");
            if (pdfs > 0) {
                score += 3;
                parts.add("归档 PDF " + pdfs + " 份");
            } else {
                parts.add("无归档 PDF");
            }
            /* SSE 实时通知: EmrEventType 事件类型定义覆盖 */
            int eventTypes = EmrEventType.values().length;
            if (eventTypes >= 5) {
                score += 2;
            } else if (eventTypes > 0) {
                score += 1;
            }
            parts.add("SSE 事件 " + eventTypes + " 类");
            /* 定时任务: 时效质控扫描(每30分钟)与归档扫描(每小时)就绪 */
            int jobs = 0;
            if (timelinessService != null) {
                jobs++;
            }
            if (archiveService != null) {
                jobs++;
            }
            score += jobs;
            parts.add("定时任务 " + jobs + "/2");
            String suggestion = score >= DIM_MAX ? "基础设施完备" : "建议补齐版本/PDF/定时任务等基础能力";
            return buildDim(name, score, DIM_MAX, "版本/归档/通知/定时任务", String.join(" | ", parts), suggestion);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            return fallbackDim(name, e);
        }
    }

    /* ==================== 辅助方法 ==================== */

    /** 总分 → 等级: ≥70→5级, ≥56→4级, ≥42→3级, ≥28→2级, ≥14→1级, 其余未达标 */
    private int calcLevel(int score) {
        if (score >= 70) {
            return 5;
        }
        if (score >= 56) {
            return 4;
        }
        if (score >= 42) {
            return 3;
        }
        if (score >= 28) {
            return 2;
        }
        if (score >= 14) {
            return 1;
        }
        return 0;
    }

    /** 等级文案 */
    private String levelText(int level) {
        String[] texts = {"未达标", "一级", "二级", "三级", "四级", "五级"};
        return level >= 0 && level < texts.length ? texts[level] : "未知";
    }

    /**
     * 机构范围计数: tenant_id + deleted=0 + 机构收口(非牵头锁定本机构, 牵头/超管不限);
     * 表缺失/SQL 失败时兜底 0 并告警(单表探测失败不得拖垮整体评估)。
     */
    private long countScoped(String table, String extraWhere, Object... extraArgs) {
        Long tenantId = TenantContext.require();
        Long org = scopeOrg();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(table)
                .append(" WHERE tenant_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (org != null) {
            sql.append(" AND org_id = ?");
            args.add(org);
        }
        appendExtra(sql, args, extraWhere, extraArgs);
        return queryCount(sql.toString(), args.toArray());
    }

    /** 租户级计数(表无 org_id 列, 如 his_emr_version): tenant_id + 可选软删条件 */
    private long countTenant(String table, boolean softDelete, String extraWhere, Object... extraArgs) {
        Long tenantId = TenantContext.require();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(table).append(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (softDelete) {
            sql.append(" AND deleted = 0");
        }
        appendExtra(sql, args, extraWhere, extraArgs);
        return queryCount(sql.toString(), args.toArray());
    }

    /** 拼装附加条件与参数(extraWhere 由各维度硬编码, 不含外部输入, 无注入面) */
    private void appendExtra(StringBuilder sql, List<Object> args, String extraWhere, Object... extraArgs) {
        if (extraWhere != null && !extraWhere.isEmpty()) {
            sql.append(" AND ").append(extraWhere);
        }
        if (extraArgs != null) {
            for (Object a : extraArgs) {
                args.add(a);
            }
        }
    }

    /** 计数/标量查询(容错): 失败返回 0 并告警, 不向上抛 */
    private long queryCount(String sql, Object... args) {
        try {
            Long v = jdbcTemplate.queryForObject(sql, Long.class, args);
            return v == null ? 0L : v;
        } catch (Exception e) {
            log.warn("分级评估查询失败: {} [{}]", e.getMessage(), sql);
            return 0L;
        }
    }

    /** 列存在性探测(结构能力判断; information_schema 为全局元数据, 无租户维度) */
    private boolean columnExists(String table, String column) {
        try {
            Long v = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns"
                            + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                    Long.class, table, column);
            return v != null && v > 0;
        } catch (Exception e) {
            log.warn("分级评估列探测失败[{}.{}]: {}", table, column, e.getMessage());
            return false;
        }
    }

    /** 视图存在性探测(P7b-1 扁平化视图可能因 VIEW 权限受限缺失) */
    private boolean viewExists(String view) {
        try {
            Long v = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.views"
                            + " WHERE table_schema = DATABASE() AND table_name = ?",
                    Long.class, view);
            return v != null && v > 0;
        } catch (Exception e) {
            log.warn("分级评估视图探测失败[{}]: {}", view, e.getMessage());
            return false;
        }
    }

    /**
     * 机构读范围: 牵头机构/平台超管=null(全租户), 非牵头锁定本机构;
     * 非特权且无归属机构时抛 403(与 EmrInteropService.readScopeOrg 口径一致, 防无身份穿透全租户)。
     */
    private Long scopeOrg() {
        Long scope = guard.scopeOrgId(null);
        LoginUser u = UserContext.get();
        if (scope == null && u != null && !u.hasRole(Roles.SUPER_ADMIN) && !guard.isLead(u) && u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法执行分级评估");
        }
        return scope;
    }

    /** 维度组装(保序, 便于前端与导出稳定列序) */
    private Map<String, Object> buildDim(String name, int score, int maxScore,
                                         String metric, String detail, String suggestion) {
        Map<String, Object> dim = new LinkedHashMap<>();
        dim.put("name", name);
        dim.put("score", score);
        dim.put("maxScore", maxScore);
        dim.put("metric", metric);
        dim.put("detail", detail);
        dim.put("suggestion", suggestion);
        return dim;
    }

    /** 维度异常兜底: 0 分占位, 保证单维度失败不影响其余维度与总分结构 */
    private Map<String, Object> fallbackDim(String name, Exception e) {
        log.warn("分级评估维度[{}]异常, 按0分兜底: {}", name, e.getMessage());
        return buildDim(name, 0, DIM_MAX, "评估异常",
                "数据源探测失败: " + e.getMessage(), "检查数据表结构与租户上下文后重试");
    }

    /** Number → int(容错: 非数字按 0) */
    private int intOf(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }
}
