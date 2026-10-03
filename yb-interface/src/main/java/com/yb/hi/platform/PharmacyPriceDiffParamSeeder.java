package com.yb.hi.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 二期: 发药批次价差容差参数定义种子(全局行 tenant_id=0, 幂等)。
 *
 * 单独成类而非并入 DemoDataInitializer: 后者为多会话并发高频改动文件, 独立新增文件零冲突,
 * 由隔离索引按纯新增提交。sys_param 表由 DictSchemaMigration(@Order(0)) 建立, 本类 @Order(2)
 * 在 DemoDataInitializer(@Order(1)) 之后运行, 复用其已建的 pharmacy 分组。
 *
 * 全部参数默认令容差闸处于"关闭"态(enabled=false), 即维持既有发药行为(照发并记录 priceDiff 损益),
 * 需启用时由机构/租户在系统参数管理页覆盖 pharmacy.price_diff_tolerance_enabled=true 并配置阈值。
 */
@Slf4j
@Order(2)
@Component
public class PharmacyPriceDiffParamSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public PharmacyPriceDiffParamSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (Exception e) {
            // 表未建等场景静默跳过, 下次启动补种(与 DemoDataInitializer 容错口径一致)
            log.warn("发药价差容差参数种子跳过: {}", e.getMessage());
        }
    }

    private void seed() {
        // {分组, 参数键, 名称, 数据类型, 默认值, 枚举选项, 最小值, 最大值, 必填, 允许作用域, 备注}
        Object[][] params = {
                {"pharmacy", "pharmacy.price_diff_tolerance_enabled", "发药价差容差闸启用", "bool", "false",
                        null, null, null, 0, "0,1,2",
                        "启用后发药时按预估实发批次金额与划价快照比对, 超容差阈值按动作处置; 关闭则照发并仅记录价差损益"},
                {"pharmacy", "pharmacy.price_diff_tolerance_rate", "发药价差容差(差率%)", "decimal", "5.00",
                        null, "0", "100", 0, "0,1,2",
                        "实发批次金额相对划价快照的允许偏差百分比; 0 表示不按差率约束"},
                {"pharmacy", "pharmacy.price_diff_tolerance_amount", "发药价差容差(绝对额元)", "decimal", "0.00",
                        null, "0", null, 0, "0,1,2",
                        "实发批次金额相对划价快照的允许偏差绝对金额; 0 表示不按绝对额约束; 与差率任一命中即判超阈"},
                {"pharmacy", "pharmacy.price_diff_action", "发药价差超阈动作", "enum", "block",
                        "block,override", null, null, 0, "0,1,2",
                        "block=超阈直接禁止发药(须换批/改派); override=超阈允许主管放行留痕后发药"},
        };
        int added = 0;
        for (Object[] p : params) {
            String key = (String) p[1];
            List<Map<String, Object>> hit = jdbcTemplate.queryForList(
                    "SELECT id, deleted FROM sys_param WHERE param_key = ? AND scope_level = 0 AND scope_id = 0 LIMIT 1", key);
            if (!hit.isEmpty()) {
                Number deleted = (Number) hit.get(0).get("deleted");
                if (deleted != null && deleted.intValue() == 1) {
                    jdbcTemplate.update(
                            "UPDATE sys_param SET tenant_id = 0, param_value = ?, scope_id = 0, group_code = ?,"
                                    + " param_name = ?, data_type = ?, default_value = ?, enum_options = ?,"
                                    + " min_value = ?, max_value = ?, required = ?, allow_scope = ?, remark = ?,"
                                    + " deleted = 0, update_by = 'pharm-pricediff-seed', update_time = NOW() WHERE id = ?",
                            p[4], p[0], p[2], p[3], p[4], p[5], toBd(p[6]), toBd(p[7]), p[8], p[9], p[10],
                            hit.get(0).get("id"));
                }
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO sys_param (tenant_id, param_key, param_value, scope_level, scope_id, group_code,"
                            + " param_name, data_type, default_value, enum_options, min_value, max_value, required,"
                            + " allow_scope, remark, create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (0, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pharm-pricediff-seed', NOW(), 'pharm-pricediff-seed', NOW(), 0)",
                    key, p[4], p[0], p[2], p[3], p[4], p[5], toBd(p[6]), toBd(p[7]), p[8], p[9], p[10]);
            added++;
        }
        if (added > 0) {
            log.info("发药价差容差参数种子: 新增定义 {} 条(默认关闭容差闸, 保持既有发药行为)", added);
        }
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
