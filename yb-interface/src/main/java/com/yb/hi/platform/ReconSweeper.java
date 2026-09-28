package com.yb.hi.platform;

import com.yb.hi.service.yb.ReconService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 医保对账调度器(批次4 M3):
 * 每日按 yb.recon-cron(默认 07:00)对 T-1 结算日执行 3201/3202 对账(对账日期以医保结算返回的结算时间为依据,
 * 规范3201重点说明2)。逐租户扫描 T-1 有结算留存的租户驱动 ReconService, 单租户失败不影响其余租户。
 * 调度线程无请求上下文, tenantId 由 ReconService 显式设置/清理。
 */
@Slf4j
@Component
public class ReconSweeper {

    private final JdbcTemplate jdbcTemplate;
    private final ReconService reconService;

    public ReconSweeper(JdbcTemplate jdbcTemplate, ReconService reconService) {
        this.jdbcTemplate = jdbcTemplate;
        this.reconService = reconService;
    }

    @Scheduled(cron = "${yb.recon-cron:0 0 7 * * *}")
    public void sweep() {
        LocalDate stmtDate = LocalDate.now().minusDays(1);
        List<Long> tenants;
        try {
            tenants = jdbcTemplate.queryForList(
                    "SELECT DISTINCT tenant_id FROM setl_record"
                            + " WHERE tenant_id > 0 AND DATE(setl_time) = ?"
                            + " ORDER BY tenant_id",
                    Long.class, stmtDate);
        } catch (Exception e) {
            log.error("对账调度: 结算留存扫描失败, 原因: {}", e.getMessage(), e);
            return;
        }
        if (tenants.isEmpty()) {
            log.info("对账调度: {} 无结算流水, 跳过", stmtDate);
            return;
        }
        log.info("对账调度开始: stmtDate={}, 租户{}个", stmtDate, tenants.size());
        int ok = 0;
        for (Long tenantId : tenants) {
            try {
                reconService.reconcile(tenantId, stmtDate);
                ok++;
            } catch (Exception e) {
                log.error("【对账告警】租户{}对账失败: {}", tenantId, e.getMessage(), e);
            }
        }
        log.info("对账调度结束: stmtDate={}, 成功{}/{}", stmtDate, ok, tenants.size());
    }
}
