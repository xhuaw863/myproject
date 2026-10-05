package com.yb.hi.platform;

import com.yb.hi.service.doctor.DiseaseReportDict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 疾病报卡触发规则种子(全局表 his_disease_report_trigger_rule, 幂等: 表空才种)。
 *
 * 背景: 原 HisDiseaseReportService.matchCat 把"ICD前缀→报卡大类"判定写死在 Java 里, 法定目录调整须发版;
 * 现升格为规则数据, 触发判定改查表, ADMIN 维护界面(public-health/report-trigger-rule)增删改启停即时生效。
 * 种子 1:1 还原原硬编码触发面: 肿瘤(prefix C + class tumor, priority 10) > 传染病(法定目录前缀, 20) > 精障(6 病 F 码, 30);
 * 码表源取 DiseaseReportDict(与卡片表单值域同一真源)。
 * 高血压(I10)/糖尿病(E10/E11/E14) 规则一并种入但 enabled=0: 4/5 类卡片表单仍为简版占位, 由机构在维护界面自主启用。
 *
 * 单独成类(同 ExamSurchargeParamSeeder 隔离式 Seeder 惯例): 表由 DictSchemaMigration(@Order(0)) 建立, 本类 @Order(2) 在其后运行。
 */
@Slf4j
@Order(2)
@Component
public class DiseaseReportTriggerRuleSeeder implements ApplicationRunner {

    private static final String SEED_BY = "dr-trigger-rule-seeder";

    private final JdbcTemplate jdbcTemplate;
    private final DiseaseReportDict dictProvider;

    public DiseaseReportTriggerRuleSeeder(JdbcTemplate jdbcTemplate, DiseaseReportDict dictProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.dictProvider = dictProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (Exception e) {
            // 表未建等场景静默跳过, 下次启动补种(与其余 Seeder 容错口径一致)
            log.warn("疾病报卡触发规则种子跳过: {}", e.getMessage());
        }
    }

    private void seed() {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_disease_report_trigger_rule WHERE deleted = 0", Integer.class);
        if (cnt != null && cnt > 0) {
            return;
        }
        Map<String, Object> dicts = dictProvider.dicts();
        int added = 0;
        // 肿瘤(cat=3, priority=10): 前缀 C + 诊断类别 tumor 两条(还原原 matchCat 首条规则)
        added += insert(3, "prefix", "C", 10, 1, "恶性肿瘤(ICD-10 C00-C97, 全国肿瘤登记报告); 原硬编码规则1:1还原");
        added += insert(3, "class", "tumor", 10, 1, "诊断类别标记为肿瘤的诊断(病案/中医肿瘤类目); 原硬编码规则1:1还原");
        // 法定传染病(cat=1, priority=20): 逐条取 DiseaseReportDict.infectiousDisease 前缀码
        for (Map<String, Object> it : castItems(dicts.get("infectiousDisease"))) {
            String code = str(it.get("code"));
            if (code.isEmpty()) {
                continue;
            }
            String cls = str(it.get("cls"));
            added += insert(1, "prefix", code.toUpperCase(), 20, 1,
                    "法定传染病" + (cls.isEmpty() ? "" : "(" + cls + "类)") + ": " + str(it.get("name")));
        }
        // 严重精神障碍(cat=2, priority=30): 精障 6 病 F 码前缀
        for (Map<String, Object> it : castItems(dicts.get("smiDisease"))) {
            String code = str(it.get("code"));
            if (code.isEmpty()) {
                continue;
            }
            added += insert(2, "prefix", code.toUpperCase(), 30, 1, "严重精神障碍6病: " + str(it.get("name")));
        }
        // 慢病占位规则(enabled=0, 表单简版待属地化模板, 由维护界面自主启用)
        added += insert(4, "prefix", "I10", 40, 0, "高血压报告卡(属地化模板为简版占位, 启用前请确认卡片格式要求)");
        added += insert(5, "prefix", "E10", 40, 0, "糖尿病报告卡-1型(属地化模板为简版占位)");
        added += insert(5, "prefix", "E11", 40, 0, "糖尿病报告卡-2型(属地化模板为简版占位)");
        added += insert(5, "prefix", "E14", 40, 0, "糖尿病报告卡-未特指型(属地化模板为简版占位)");
        log.info("疾病报卡触发规则种子: 导入 {} 条(肿瘤2+传染病/精障按法定目录+慢病占位4条停用)", added);
    }

    private int insert(int cat, String matchType, String pattern, int priority, int enabled, String remark) {
        return jdbcTemplate.update(
                "INSERT INTO his_disease_report_trigger_rule (report_category, match_type, code_pattern, priority, enabled, remark,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, NOW(), 0)",
                cat, matchType, pattern, priority, enabled, remark, SEED_BY, SEED_BY);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castItems(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : java.util.Collections.emptyList();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
