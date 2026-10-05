package com.yb.hi.service.yb;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.yb.HisUploadStatus;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.yb.HisUploadStatusMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 医保上传管线状态机(M5, 设计 §6.1/6.2): his_upload_status 逐单状态的请求侧读写。
 * 定时补传与手动重传的执行在 UploadStatusSweeper(原生 SQL 跨租户扫描, 调 HisVisitService.uploadVisitYb);
 * 本服务只负责状态落库/查询, 不依赖业务服务, 避免构造器循环。
 * 公开方法均显式传 tenantId 并内部设置 TenantContext(供管理端/守卫/调度调用, 与 CatalogUploadService 同风格)。
 */
@Slf4j
@Service
public class UploadStatusService {

    private final HisUploadStatusMapper mapper;

    public UploadStatusService(HisUploadStatusMapper mapper) {
        this.mapper = mapper;
    }

    /** 按业务键查状态(无记录视为待传) */
    public HisUploadStatus findByBiz(Long tenantId, String bizType, Long bizId) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            return selectByBiz(bizType, bizId);
        } finally {
            restore(outer);
        }
    }

    /** 嵌套租户上下文恢复: 有外层请求上下文则还原, 无则清除(调度线程) */
    private static void restore(Long outer) {
        if (outer == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(outer);
        }
    }

    /** 业务键查询(须在租户上下文内调用) */
    private HisUploadStatus selectByBiz(String bizType, Long bizId) {
        return mapper.selectOne(Wrappers.<HisUploadStatus>lambdaQuery()
                .eq(HisUploadStatus::getBizType, bizType)
                .eq(HisUploadStatus::getBizId, bizId)
                .eq(HisUploadStatus::getDeleted, 0)
                .last("LIMIT 1"));
    }

    /**
     * 记录 2203 就诊上传结果(兼容入口): 委托泛化 record(BIZ_VISIT)。
     */
    public void recordVisit(Long tenantId, Long visitId, String mdtrtId,
                            boolean success, String msgid, String err) {
        record(tenantId, HisUploadStatus.BIZ_VISIT, visitId, mdtrtId, success, msgid, err);
    }

    /**
     * 按业务键 upsert 上传结果(批次5 M1 泛化: VISIT/TRACE 等共用, 与 uk_biz 一致):
     * 成功 -> status=1/msgid/清错误; 失败 -> status=2, retry_count+1, next_retry 指数退避; 达上限转人工告警。
     */
    public void record(Long tenantId, String bizType, Long bizId, String mdtrtId,
                       boolean success, String msgid, String err) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            HisUploadStatus row = selectByBiz(bizType, bizId);
            if (row == null) {
                row = new HisUploadStatus();
                row.setBizType(bizType);
                row.setBizId(bizId);
                row.setMdtrtId(mdtrtId);
                row.setStatus(success ? HisUploadStatus.STATUS_UPLOADED : HisUploadStatus.STATUS_FAILED);
                row.setRetryCount(success ? 0 : 1);
                row.setNextRetry(success ? null : nextRetry(1));
                row.setLastErr(success ? null : truncate(err));
                row.setMsgid(msgid);
                mapper.insert(row);
            } else if (success) {
                // updateById 默认忽略 null 字段, 清 last_err/next_retry 需显式 set null
                mapper.update(null, Wrappers.<HisUploadStatus>lambdaUpdate()
                        .set(HisUploadStatus::getStatus, HisUploadStatus.STATUS_UPLOADED)
                        .set(HisUploadStatus::getMdtrtId, mdtrtId)
                        .set(HisUploadStatus::getMsgid, msgid)
                        .set(HisUploadStatus::getLastErr, null)
                        .set(HisUploadStatus::getNextRetry, null)
                        .eq(HisUploadStatus::getId, row.getId())
                        .eq(HisUploadStatus::getDeleted, 0));
            } else {
                int n = row.getRetryCount() == null ? 0 : row.getRetryCount();
                row.setStatus(HisUploadStatus.STATUS_FAILED);
                row.setMdtrtId(mdtrtId);
                row.setRetryCount(n + 1);
                row.setNextRetry(nextRetry(n + 1));
                row.setLastErr(truncate(err));
                mapper.updateById(row);
                if (n + 1 >= HisUploadStatus.MAX_AUTO_RETRY) {
                    log.error("【医保上报告警】{} 上传连续失败{}次转人工: tenantId={}, bizId={}, 原因={}",
                            bizType, n + 1, tenantId, bizId, truncate(err));
                }
            }
        } finally {
            restore(outer);
        }
    }

    /**
     * 入待传队列(上传时机参数 deferred 模式: 不即时调医保接口, 由定时扫描批量补传):
     * 幂等 —— 已有行不降级不改写(已传/失败待补/已撤销各有归宿, 待传行重复入队也无害);
     * 仅无行时新建 status=0, retry_count=0, next_retry=NOW(下个扫描周期即拾取)。
     */
    public void markPending(Long tenantId, String bizType, Long bizId, String mdtrtId) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            HisUploadStatus row = selectByBiz(bizType, bizId);
            if (row != null) {
                return;
            }
            row = new HisUploadStatus();
            row.setBizType(bizType);
            row.setBizId(bizId);
            row.setMdtrtId(mdtrtId);
            row.setStatus(HisUploadStatus.STATUS_PENDING);
            row.setRetryCount(0);
            row.setNextRetry(java.time.LocalDateTime.now());
            mapper.insert(row);
        } finally {
            restore(outer);
        }
    }

    /** 退号撤销: VISIT 标记 status=3 已撤销(不再补传, 收费守卫阻断) */
    public void markRevoked(Long tenantId, String bizType, Long bizId, String mdtrtId) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            HisUploadStatus row = selectByBiz(bizType, bizId);
            if (row == null) {
                row = new HisUploadStatus();
                row.setBizType(bizType);
                row.setBizId(bizId);
                row.setMdtrtId(mdtrtId);
                row.setStatus(HisUploadStatus.STATUS_REVOKED);
                row.setRetryCount(0);
                row.setNextRetry(null);
                mapper.insert(row);
            } else if (row.getStatus() == null || row.getStatus() != HisUploadStatus.STATUS_REVOKED) {
                // updateById 默认忽略 null 字段, 清 next_retry 需显式 set null
                mapper.update(null, Wrappers.<HisUploadStatus>lambdaUpdate()
                        .set(HisUploadStatus::getStatus, HisUploadStatus.STATUS_REVOKED)
                        .set(HisUploadStatus::getMdtrtId, mdtrtId)
                        .set(HisUploadStatus::getNextRetry, null)
                        .eq(HisUploadStatus::getId, row.getId())
                        .eq(HisUploadStatus::getDeleted, 0));
            }
        } finally {
            restore(outer);
        }
    }

    /** 上报中心分页: bizType/status 可选筛选, 按 id 倒序 */
    public IPage<HisUploadStatus> page(Long tenantId, String bizType, Integer status, long page, long size) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            return mapper.selectPage(new Page<>(page, size),
                    Wrappers.<HisUploadStatus>lambdaQuery()
                            .eq(HisUploadStatus::getDeleted, 0)
                            .eq(StringUtils.hasText(bizType), HisUploadStatus::getBizType, bizType)
                            .eq(status != null, HisUploadStatus::getStatus, status)
                            .orderByDesc(HisUploadStatus::getId));
        } finally {
            restore(outer);
        }
    }

    /** 上报中心统计: 按状态计数(供状态筛选与汇总展示) */
    public List<HisUploadStatus> list(Long tenantId, String bizType, Integer status) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            return mapper.selectList(Wrappers.<HisUploadStatus>lambdaQuery()
                    .eq(HisUploadStatus::getDeleted, 0)
                    .eq(StringUtils.hasText(bizType), HisUploadStatus::getBizType, bizType)
                    .eq(status != null, HisUploadStatus::getStatus, status));
        } finally {
            restore(outer);
        }
    }

    /** 失败次数 n 的下次重试时间(指数退避 1/5/15/60min) */
    private static LocalDateTime nextRetry(int retryCount) {
        return LocalDateTime.now().plusMinutes(HisUploadStatus.backoffMinutes(retryCount));
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
