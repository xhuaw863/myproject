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
 * 导出防护三参数定义种子(全局行 tenant_id=0, 幂等; 供 ExportGuard 60s 缓存读取)。
 *
 * 单独成类而非并入 DemoDataInitializer: 后者为多会话并发高频改动文件, 独立新增文件零冲突。
 * sys_param 表由 DictSchemaMigration(@Order(0)) 建立, 本类 @Order(2) 复用 DemoDataInitializer
 * 已建的 system 分组(系统通用)。
 *
 * 默认值口径: 行数上限 10 万(常规导出远低于此, 仅拦截失控全量)、每用户每分钟 6 次、
 * 全局并发导出 5 路(信号量启动时固化, 调整 concurrency 需重启生效)。
 */
@Slf4j
@Order(2)
@Component
public class ExportGuardParamSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public ExportGuardParamSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (Exception e) {
            // 表未建等场景静默跳过, 下次启动补种(与 PharmacyPriceDiffParamSeeder 容错口径一致)
            log.warn("导出防护参数种子跳过: {}", e.getMessage());
        }
    }

    private void seed() {
        // {分组, 参数键, 名称, 数据类型, 默认值, 枚举选项, 最小值, 最大值, 必填, 允许作用域, 备注}
        Object[][] params = {
                {"system", ExportGuard.P_MAX_ROWS, "单次导出行数上限", "int", "100000",
                        null, "100", "1000000", 0, "0,1,2",
                        "导出组装行数超过该上限直接拒绝并提示收窄条件; 仅拦截失控全量导出, 常规导出不受影响"},
                {"system", ExportGuard.P_RATE_PER_MIN, "每用户每分钟导出次数", "int", "6",
                        null, "1", "60", 0, "0,1,2",
                        "滑动窗口限流: 单个账号 60 秒内允许的导出请求次数, 超出返回 429 提示稍后再试"},
                {"system", ExportGuard.P_CONCURRENCY, "全局并发导出上限", "int", "5",
                        null, "1", "50", 0, "0",
                        "同时刻全系统允许在途的导出请求数(信号量); 启动时固化, 调整后需重启生效"},
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
                                    + " deleted = 0, update_by = 'export-guard-seed', update_time = NOW() WHERE id = ?",
                            p[4], p[0], p[2], p[3], p[4], p[5], toBd(p[6]), toBd(p[7]), p[8], p[9], p[10],
                            hit.get(0).get("id"));
                }
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO sys_param (tenant_id, param_key, param_value, scope_level, scope_id, group_code,"
                            + " param_name, data_type, default_value, enum_options, min_value, max_value, required,"
                            + " allow_scope, remark, create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (0, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'export-guard-seed', NOW(), 'export-guard-seed', NOW(), 0)",
                    key, p[4], p[0], p[2], p[3], p[4], p[5], toBd(p[6]), toBd(p[7]), p[8], p[9], p[10]);
            added++;
        }
        if (added > 0) {
            log.info("导出防护参数种子: 新增定义 {} 条(上限 10 万行 / 每分钟 6 次 / 并发 5 路)", added);
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
