package com.yb.hi.platform;

import com.yb.hi.common.YbResponse;
import com.yb.hi.entity.yb.HisUploadStatus;
import com.yb.hi.service.doctor.HisVisitService;
import com.yb.hi.service.inpatient.InpUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 医保上传管线扫描器(批次4 M5, 设计 §6.2):
 * 周期扫描 his_upload_status 中纯上传类的待传(0, 上传时机参数 deferred 入队)与失败待补(2,
 * retry_count<6, next_retry 到期)行, 按 biz_type 分派补传器: VISIT->2203门诊就诊(uploadVisitYb) /
 * INP_REG->2401入院登记 / INP_DISCH->2402出院办理。
 * 成功后置已传(1), 失败指数退避(1/5/15/60min), 满 6 次转人工【医保上报告警】。
 * 手动重传(retryRow)由上报中心触发: 仅失败待补行可重传, 复位退避后立即补传一次。
 * 责任分工: 2304/2305 UNKNOWN 资金补偿归 CompTaskSweeper, 本扫描器只处理 2401/2402/2203 纯上传语义。
 * 原生 SQL 显式 tenant_id(租户插件不作用于 jdbcTemplate; 调度线程无请求上下文)。
 */
@Slf4j
@Component
public class UploadStatusSweeper {

    private final JdbcTemplate jdbcTemplate;
    private final HisVisitService visitService;
    private final InpUploadService inpUploadService;

    public UploadStatusSweeper(JdbcTemplate jdbcTemplate, HisVisitService visitService,
                               InpUploadService inpUploadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.visitService = visitService;
        this.inpUploadService = inpUploadService;
    }

    @Scheduled(fixedDelay = 60000)
    public void sweep() {
        // 待传(0)目前仅 VISIT 会产生(deferred 入队); 失败待补(2)含三个纯上传类型
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, biz_id, biz_type, status FROM his_upload_status"
                        + " WHERE deleted = 0 AND (biz_type IN ('INP_REG', 'INP_DISCH') AND status = 2"
                        + " OR biz_type = 'VISIT' AND status IN (0, 2))"
                        + " AND retry_count < " + HisUploadStatus.MAX_AUTO_RETRY
                        + " AND next_retry <= NOW() ORDER BY id LIMIT 50");
        for (Map<String, Object> r : rows) {
            Long id = toLong(r.get("id"));
            Long tenantId = toLong(r.get("tenant_id"));
            try {
                attempt(id, tenantId, toLong(r.get("biz_id")), str(r.get("biz_type")), false,
                        toLong(r.get("status")).intValue());
            } catch (Exception e) {
                log.error("上传状态补传执行异常: uploadId={}, 原因: {}", id, e.getMessage(), e);
            }
        }
    }

    /** 手动重传(上报中心): 仅纯上传类(VISIT/INP_REG/INP_DISCH)且失败待补(status=2)行可重传; 复位退避后立即补传一次。
     *  INP_SETL/INP_FEE 为资金补偿跟踪行, 归 CompTaskSweeper 收敛, 不在此手动重传。 */
    public Map<String, Object> retryRow(Long tenantId, Long id) {
        List<Map<String, Object>> biz = jdbcTemplate.queryForList(
                "SELECT biz_id, biz_type, status FROM his_upload_status WHERE id = ? AND tenant_id = ? AND deleted = 0",
                id, tenantId);
        Map<String, Object> res = new LinkedHashMap<>();
        if (biz.isEmpty()) {
            res.put("success", false);
            res.put("err", "记录不存在");
            return res;
        }
        String bizType = str(biz.get(0).get("biz_type"));
        if (!isRetryable(bizType)) {
            res.put("success", false);
            res.put("err", "该业务类型由补偿任务收敛, 不支持手动重传(仅就诊/入出院登记上传可重传)");
            return res;
        }
        int claimed = jdbcTemplate.update(
                "UPDATE his_upload_status SET retry_count = 0, next_retry = DATE_ADD(NOW(), INTERVAL 30 MINUTE)"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND status = 2",
                id, tenantId);
        if (claimed != 1) {
            res.put("success", false);
            res.put("err", "该记录当前状态不可手动重传(仅失败待补可重传)");
            return res;
        }
        return attempt(id, tenantId, toLong(biz.get(0).get("biz_id")), bizType, true, 2);
    }

    /** 是否本扫描器可补传的纯上传类型 */
    private static boolean isRetryable(String bizType) {
        return HisUploadStatus.BIZ_VISIT.equals(bizType)
                || HisUploadStatus.BIZ_INP_REG.equals(bizType)
                || HisUploadStatus.BIZ_INP_DISCH.equals(bizType);
    }

    /** 补传一条纯上传记录(认领 -> 按 biz_type 调对应上传器 -> 落状态)。manual=true 时认领已由 retryRow 完成;
     *  claimStatus: 扫描到的当前状态(0待传/2失败待补), 认领 UPDATE 按它做条件防并发与状态漂移 */
    private Map<String, Object> attempt(Long id, Long tenantId, Long bizId, String bizType, boolean manual, int claimStatus) {
        if (!manual) {
            int claimed = jdbcTemplate.update(
                    "UPDATE his_upload_status SET next_retry = DATE_ADD(NOW(), INTERVAL 30 MINUTE)"
                            + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND status = ?"
                            + " AND retry_count < " + HisUploadStatus.MAX_AUTO_RETRY + " AND next_retry <= NOW()",
                    id, tenantId, claimStatus);
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
            resp = dispatchUpload(bizType, tenantId, bizId);
        } catch (Exception e) {
            log.warn("{} 补传调用异常: uploadId={}, bizId={}, 原因: {}", bizType, id, bizId, e.getMessage());
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
            log.info("{} 上传补传成功: tenantId={}, bizId={}, msgid={}", bizType, tenantId, bizId, msgid);
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
                log.error("【医保上报告警】{} 上传连续失败{}次转人工: tenantId={}, bizId={}, 原因={}",
                        bizType, n, tenantId, bizId, safeErr);
            }
        }
        return res;
    }

    /** 按 biz_type 分派补传器: VISIT->2203 / INP_REG->2401 / INP_DISCH->2402 */
    private YbResponse dispatchUpload(String bizType, Long tenantId, Long bizId) {
        if (HisUploadStatus.BIZ_INP_REG.equals(bizType)) {
            return inpUploadService.uploadInpRegister(tenantId, bizId);
        }
        if (HisUploadStatus.BIZ_INP_DISCH.equals(bizType)) {
            return inpUploadService.uploadInpDischarge(tenantId, bizId);
        }
        return visitService.uploadVisitYb(tenantId, bizId);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
