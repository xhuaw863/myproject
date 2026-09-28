package com.yb.hi.platform;

import com.yb.hi.common.YbResponse;
import com.yb.hi.entity.yb.HisUploadStatus;
import com.yb.hi.service.doctor.HisVisitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 医保上传管线扫描器(批次4 M5, 设计 §6.2):
 * 周期扫描 his_upload_status 中 VISIT 失败待补(status=2, retry_count<6, next_retry 到期)的行,
 * 跨租户补传 2203; 成功后置已传(1), 失败指数退避(1/5/15/60min), 满 6 次转人工【医保上报告警】。
 * 手动重传(retryRow)由上报中心触发: 仅失败待补行可重传, 复位退避后立即补传一次。
 * 原生 SQL 显式 tenant_id(租户插件不作用于 jdbcTemplate; 调度线程无请求上下文)。
 */
@Slf4j
@Component
public class UploadStatusSweeper {

    private final JdbcTemplate jdbcTemplate;
    private final HisVisitService visitService;

    public UploadStatusSweeper(JdbcTemplate jdbcTemplate, HisVisitService visitService) {
        this.jdbcTemplate = jdbcTemplate;
        this.visitService = visitService;
    }

    @Scheduled(fixedDelay = 60000)
    public void sweep() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, biz_id FROM his_upload_status"
                        + " WHERE deleted = 0 AND biz_type = 'VISIT' AND status = 2"
                        + " AND retry_count < " + HisUploadStatus.MAX_AUTO_RETRY
                        + " AND next_retry <= NOW() ORDER BY id LIMIT 50");
        for (Map<String, Object> r : rows) {
            Long id = toLong(r.get("id"));
            Long tenantId = toLong(r.get("tenant_id"));
            try {
                attempt(id, tenantId, toLong(r.get("biz_id")), false);
            } catch (Exception e) {
                log.error("上传状态补传执行异常: uploadId={}, 原因: {}", id, e.getMessage(), e);
            }
        }
    }

    /** 手动重传(上报中心): 仅失败待补(status=2)行可重传; 复位退避计数后立即补传一次 */
    public Map<String, Object> retryRow(Long tenantId, Long id) {
        int claimed = jdbcTemplate.update(
                "UPDATE his_upload_status SET retry_count = 0, next_retry = DATE_ADD(NOW(), INTERVAL 30 MINUTE)"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND status = 2",
                id, tenantId);
        if (claimed != 1) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", false);
            res.put("err", "该记录当前状态不可手动重传(仅失败待补可重传)");
            return res;
        }
        List<Long> bizIds = jdbcTemplate.queryForList(
                "SELECT biz_id FROM his_upload_status WHERE id = ? AND tenant_id = ? AND deleted = 0",
                Long.class, id, tenantId);
        return attempt(id, tenantId, bizIds.isEmpty() ? null : bizIds.get(0), true);
    }

    /** 补传一条 VISIT(认领 -> 2203 -> 落状态)。manual=true 时认领已由 retryRow 完成 */
    private Map<String, Object> attempt(Long id, Long tenantId, Long bizId, boolean manual) {
        if (!manual) {
            int claimed = jdbcTemplate.update(
                    "UPDATE his_upload_status SET next_retry = DATE_ADD(NOW(), INTERVAL 30 MINUTE)"
                            + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND status = 2"
                            + " AND retry_count < " + HisUploadStatus.MAX_AUTO_RETRY + " AND next_retry <= NOW()",
                    id, tenantId);
            if (claimed != 1) {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("success", false);
                res.put("err", "记录已被其他任务处理或重试未到期");
                return res;
            }
        }
        int retryCount = 0;
        List<Integer> rcs = jdbcTemplate.queryForList(
                "SELECT retry_count FROM his_upload_status WHERE id = ? AND tenant_id = ? AND deleted = 0",
                Integer.class, id, tenantId);
        if (!rcs.isEmpty() && rcs.get(0) != null) {
            retryCount = rcs.get(0);
        }
        YbResponse resp = null;
        try {
            resp = visitService.uploadVisitYb(tenantId, bizId);
        } catch (Exception e) {
            log.warn("2203 补传调用异常: uploadId={}, visitId={}, 原因: {}", id, bizId, e.getMessage());
        }
        boolean ok = resp != null && resp.isSuccess();
        String msgid = ok && resp.getInfRefmsgid() != null ? resp.getInfRefmsgid() : null;
        String err = ok ? null : (resp == null ? "医保无响应" : (resp.isUnknown() ? "医保响应未知(超时)" : resp.getErrMsg()));
        Map<String, Object> res = new LinkedHashMap<>();
        if (ok) {
            jdbcTemplate.update("UPDATE his_upload_status SET status = 1, last_err = NULL, msgid = ?"
                    + " WHERE id = ? AND tenant_id = ? AND deleted = 0", msgid, id, tenantId);
            res.put("success", true);
            res.put("msgid", msgid);
            log.info("VISIT 就诊上传(2203)补传成功: tenantId={}, visitId={}, msgid={}", tenantId, bizId, msgid);
        } else {
            int n = retryCount + 1;
            String safeErr = err == null ? "" : (err.length() > 500 ? err.substring(0, 500) : err);
            jdbcTemplate.update("UPDATE his_upload_status SET retry_count = ?, last_err = ?,"
                            + " next_retry = DATE_ADD(NOW(), INTERVAL ? MINUTE)"
                            + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    n, safeErr, HisUploadStatus.backoffMinutes(n), id, tenantId);
            res.put("success", false);
            res.put("err", err);
            if (n >= HisUploadStatus.MAX_AUTO_RETRY) {
                log.error("【医保上报告警】VISIT 就诊上传(2203)连续失败{}次转人工: tenantId={}, visitId={}, 原因={}",
                        n, tenantId, bizId, safeErr);
            }
        }
        return res;
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
