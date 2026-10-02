package com.yb.hi.platform;

import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.service.inpatient.SurgeryApplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 手术术前提醒调度器(P2c, 规范2.2.2.3.7.6):
 * 每日按 yb.surgery.preop-cron(默认 07:30)对已安排且临近排期(未来 preop-hours 小时内)的手术批量生成 notify_type=3 提醒。
 * 调度线程无请求上下文, 逐租户扫描 his_surgery 中存在的 tenant_id, 显式设置/清理 TenantContext 后驱动 SurgeryApplyService;
 * 单租户失败不影响其余租户。去重在 Service 内保证(同 surgery_id 已有未发送提醒则跳过), 幂等可重复执行。
 */
@Slf4j
@Component
public class SurgeryNotifyJob {

    private final JdbcTemplate jdbcTemplate;
    private final SurgeryApplyService applyService;

    /** 术前提醒提前量(小时): 对排期在未来该时长内的已安排手术生成提醒。 */
    @Value("${yb.surgery.preop-hours:24}")
    private int preOpHours;

    public SurgeryNotifyJob(JdbcTemplate jdbcTemplate, SurgeryApplyService applyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.applyService = applyService;
    }

    @Scheduled(cron = "${yb.surgery.preop-cron:0 30 7 * * *}")
    public void generatePreOpReminders() {
        int hours = preOpHours <= 0 ? 24 : preOpHours;
        List<Long> tenants;
        try {
            tenants = jdbcTemplate.queryForList(
                    "SELECT DISTINCT tenant_id FROM his_surgery"
                            + " WHERE deleted = 0 AND tenant_id > 0 ORDER BY tenant_id",
                    Long.class);
        } catch (Exception e) {
            log.error("术前提醒调度: 租户扫描失败, 原因: {}", e.getMessage(), e);
            return;
        }
        if (tenants.isEmpty()) {
            log.info("术前提醒调度: 无手术数据, 跳过");
            return;
        }
        log.info("术前提醒调度开始: beforeHours={}, 租户{}个", hours, tenants.size());
        int total = 0;
        for (Long tenantId : tenants) {
            try {
                TenantContext.set(tenantId);
                total += applyService.generatePreOpReminders(hours);
            } catch (Exception e) {
                log.error("【术前提醒告警】租户{}生成失败: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("术前提醒调度结束: 新增提醒={}条", total);
    }

    /**
     * 每日送达回查调度(P4a, 幂等): 逐租户对已提交网关(send_status=1)的通知回查最终送达并回填状态。
     * 与术前提醒同模式设/清 TenantContext; 单租户失败不影响其余。仅处理 send_status=1, 可重复执行。
     */
    @Scheduled(cron = "${yb.surgery.delivery-cron:0 30 8 * * *}")
    public void recheckDelivery() {
        List<Long> tenants;
        try {
            tenants = jdbcTemplate.queryForList(
                    "SELECT DISTINCT tenant_id FROM his_surgery_notify"
                            + " WHERE deleted = 0 AND tenant_id > 0 AND send_status = 1 ORDER BY tenant_id",
                    Long.class);
        } catch (Exception e) {
            log.error("送达回查调度: 租户扫描失败, 原因: {}", e.getMessage(), e);
            return;
        }
        if (tenants.isEmpty()) {
            return;
        }
        int total = 0;
        for (Long tenantId : tenants) {
            try {
                TenantContext.set(tenantId);
                total += applyService.batchQueryDelivery();
            } catch (Exception e) {
                log.error("【送达回查告警】租户{}失败: {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("送达回查调度结束: 状态变更={}条", total);
    }
}
