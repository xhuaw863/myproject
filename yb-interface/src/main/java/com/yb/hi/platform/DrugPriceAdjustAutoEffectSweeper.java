package com.yb.hi.platform;

import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.service.warehouse.DrugPriceAdjustService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 药品调价单到生效日自动生效扫描器(三期 C1):
 * 全局开关 warehouse.price_adjust_auto_effect_enabled=true 时, 周期扫描 status=0 且 auto_effect=1 且 effective_date<=今日 的草稿调价单,
 * 逐单设置租户上下文后复用 DrugPriceAdjustService.confirm(id) 执行(该方法内含原子认领 0→3, 天然防多实例/多次重复生效)。
 * 开关默认关闭(opt-in), 关闭时扫描器直接空转不改动任何数据; 单条失败仅记 warn 不影响其余(认领与改价同事务回滚, 状态回 0 待下次重试)。
 * 原生 SQL 显式 tenant_id(调度线程无请求上下文, 租户插件不作用于 jdbcTemplate)。
 */
@Slf4j
@Component
public class DrugPriceAdjustAutoEffectSweeper {

    private static final String SWITCH_KEY = "warehouse.price_adjust_auto_effect_enabled";

    private final JdbcTemplate jdbcTemplate;
    private final DrugPriceAdjustService priceAdjustService;

    public DrugPriceAdjustAutoEffectSweeper(JdbcTemplate jdbcTemplate, DrugPriceAdjustService priceAdjustService) {
        this.jdbcTemplate = jdbcTemplate;
        this.priceAdjustService = priceAdjustService;
    }

    @Scheduled(cron = "${yb.price-adjust.sweep-cron:0 * * * * ?}")
    public void sweep() {
        if (!enabled()) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id FROM his_drug_price_adjust"
                        + " WHERE deleted = 0 AND status = 0 AND auto_effect = 1 AND effective_date <= CURDATE()"
                        + " ORDER BY id LIMIT 50");
        for (Map<String, Object> r : rows) {
            Long id = toLong(r.get("id"));
            Long tenantId = toLong(r.get("tenant_id"));
            try {
                TenantContext.set(tenantId);
                priceAdjustService.confirm(id);
                log.info("调价单自动生效成功: id={}, tenant={}", id, tenantId);
            } catch (Exception e) {
                log.warn("调价单自动生效跳过: id={}, tenant={}, 原因: {}", id, tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** 全局开关(仅读 scope_level=0 定义行): 缺省/异常一律视为关闭。 */
    private boolean enabled() {
        try {
            List<String> v = jdbcTemplate.queryForList(
                    "SELECT param_value FROM sys_param WHERE param_key = ? AND scope_level = 0 AND scope_id = 0 AND deleted = 0",
                    String.class, SWITCH_KEY);
            return !v.isEmpty() && "true".equalsIgnoreCase(v.get(0));
        } catch (Exception e) {
            return false;
        }
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
