package com.yb.hi.service.yb;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.common.YbResponse;
import com.yb.hi.config.YbConfig;
import com.yb.hi.dto.CatalogRevokeReq;
import com.yb.hi.dto.CatalogUploadReq;
import com.yb.hi.entity.yb.HisYbUploadQueue;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.yb.HisYbUploadQueueMapper;
import com.yb.hi.service.OutpatientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录对照上传(M4): 事件源 his_yb_map_log 落 his_yb_upload_queue,
 * 手动/接口触发按动作分批上报: MAP→3301(new_code); CLEAR→3302(old_code);
 * CHANGE→先 3302(old_code) 后 3301(new_code); 每批 ≤100 条(规范 3301 重点说明 2)。
 * list_type 目录类别为 3301/3302 必填字典, 取值与平台确认后按目录类型配置
 * (yb.list-type-drug/cons/charge), 未配置时该目录类型拒绝上报(防错误字典值上平台)。
 */
@Slf4j
@Service
public class CatalogUploadService {

    /** 每批最大条数(规范 3301 重点说明 2: 每次不能超过 100 条) */
    private static final int MAX_BATCH = 100;

    private final HisYbUploadQueueMapper queueMapper;
    private final OutpatientService outpatientService;
    private final YbConfig ybConfig;

    public CatalogUploadService(HisYbUploadQueueMapper queueMapper,
                                OutpatientService outpatientService, YbConfig ybConfig) {
        this.queueMapper = queueMapper;
        this.outpatientService = outpatientService;
        this.ybConfig = ybConfig;
    }

    /** 按目录类型取配置的 list_type(未配置返回 null) */
    public String listTypeOf(String catalogType) {
        if ("drug".equals(catalogType)) {
            return ybConfig.getListTypeDrug();
        }
        if ("cons".equals(catalogType)) {
            return ybConfig.getListTypeCons();
        }
        if ("charge".equals(catalogType)) {
            return ybConfig.getListTypeCharge();
        }
        return null;
    }

    /**
     * 对照变更入队(his_yb_map_log 事件源): action 与 logChange 的 changeType 同域。
     * MAP: newCode 上传; CLEAR: oldCode 撤销; CHANGE: 先撤 oldCode 后传 newCode; EFF 不入队。
     * 无有效码(如 CLEAR 但 oldCode 为空)时跳过。
     */
    public void enqueue(String catalogType, Long catalogId, String itemCode, String itemName,
                        String oldCode, String newCode, String action) {
        if (!HisYbUploadQueue.ACTION_MAP.equals(action)
                && !HisYbUploadQueue.ACTION_CHANGE.equals(action)
                && !HisYbUploadQueue.ACTION_CLEAR.equals(action)) {
            return;
        }
        String uploadCode = StringUtils.hasText(newCode) ? newCode : null;
        String revokeCode = StringUtils.hasText(oldCode) ? oldCode : null;
        if (HisYbUploadQueue.ACTION_MAP.equals(action) && uploadCode == null) {
            return;
        }
        if (HisYbUploadQueue.ACTION_CLEAR.equals(action) && revokeCode == null) {
            return;
        }
        if (HisYbUploadQueue.ACTION_CHANGE.equals(action) && uploadCode == null && revokeCode == null) {
            return;
        }
        HisYbUploadQueue q = new HisYbUploadQueue();
        q.setCatalogType(catalogType);
        q.setCatalogId(catalogId);
        q.setItemCode(itemCode);
        q.setItemName(itemName);
        q.setListType(listTypeOf(catalogType));
        q.setOldCode(revokeCode);
        q.setNewCode(uploadCode);
        q.setAction(action);
        q.setStatus(HisYbUploadQueue.STATUS_PENDING);
        queueMapper.insert(q);
    }

    /**
     * 触发上传(tenantId 显式传入: 供管理端与调度调用, 内部设置 TenantContext 使 Mapper 自动带租户):
     * 先撤销批(3302)后上传批(3301), 每批 ≤100 条; catalogFilter 可按目录类型过滤。
     * list_type 未配置的行直接置失败(不向平台发错误字典值)。
     * 返回 {revoked, uploaded, failed, skipped} 汇总。
     */
    public java.util.Map<String, Object> runUpload(Long tenantId, String catalogFilter) {
        TenantContext.set(tenantId);
        try {
            List<HisYbUploadQueue> pending = queueMapper.selectList(Wrappers.<HisYbUploadQueue>lambdaQuery()
                    .eq(HisYbUploadQueue::getStatus, HisYbUploadQueue.STATUS_PENDING)
                    .eq(StringUtils.hasText(catalogFilter), HisYbUploadQueue::getCatalogType, catalogFilter)
                    .orderByAsc(HisYbUploadQueue::getId));
            int revoked = 0, uploaded = 0, failed = 0;
            // 1) 撤销批: CLEAR 与 CHANGE 的 oldCode -> 3302(先撤后传, 保证 CHANGE 新旧码不并存)
            List<HisYbUploadQueue> revokeRows = new ArrayList<>();
            for (HisYbUploadQueue q : pending) {
                if (StringUtils.hasText(q.getOldCode())
                        && (HisYbUploadQueue.ACTION_CLEAR.equals(q.getAction())
                        || HisYbUploadQueue.ACTION_CHANGE.equals(q.getAction()))) {
                    revokeRows.add(q);
                }
            }
            int[] r = processBatches(tenantId, revokeRows, true);
            revoked = r[0];
            failed += r[1];
            // 2) 上传批: MAP 与 CHANGE 的 newCode -> 3301(撤销批已置失败的 CHANGE 行跳过, 不重复计数)
            List<HisYbUploadQueue> uploadRows = new ArrayList<>();
            for (HisYbUploadQueue q : pending) {
                if (q.getStatus() == HisYbUploadQueue.STATUS_FAILED) {
                    continue;
                }
                if (StringUtils.hasText(q.getNewCode())
                        && (HisYbUploadQueue.ACTION_MAP.equals(q.getAction())
                        || HisYbUploadQueue.ACTION_CHANGE.equals(q.getAction()))) {
                    uploadRows.add(q);
                }
            }
            r = processBatches(tenantId, uploadRows, false);
            uploaded = r[0];
            failed += r[1];
            log.info("3301/3302 对照上报: tenantId={}, 撤销{}条/上传{}条/失败{}条", tenantId, revoked, uploaded, failed);
            java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
            res.put("revoked", revoked);
            res.put("uploaded", uploaded);
            res.put("failed", failed);
            return res;
        } finally {
            TenantContext.clear();
        }
    }

    /** 分批处理(≤100/批): 返回 [成功条数, 失败条数]; list_type 按当前配置实时解析(配置后补可重传) */
    private int[] processBatches(Long tenantId, List<HisYbUploadQueue> rows, boolean revoke) {
        int ok = 0, fail = 0;
        for (int i = 0; i < rows.size(); i += MAX_BATCH) {
            List<HisYbUploadQueue> batch = rows.subList(i, Math.min(i + MAX_BATCH, rows.size()));
            List<HisYbUploadQueue> unconfigured = new ArrayList<>();
            List<HisYbUploadQueue> ready = new ArrayList<>();
            for (HisYbUploadQueue q : batch) {
                String lt = listTypeOf(q.getCatalogType());
                if (!StringUtils.hasText(lt)) {
                    unconfigured.add(q);
                } else {
                    q.setListType(lt);
                    ready.add(q);
                }
            }
            for (HisYbUploadQueue q : unconfigured) {
                markFailed(q, "list_type 未配置(yb.list-type-" + q.getCatalogType() + "), 拒绝上报");
                fail++;
            }
            if (ready.isEmpty()) {
                continue;
            }
            YbResponse resp;
            String batchNo;
            try {
                if (revoke) {
                    List<CatalogRevokeReq> reqs = new ArrayList<>();
                    for (HisYbUploadQueue q : ready) {
                        CatalogRevokeReq req = new CatalogRevokeReq();
                        req.setFixmedinsCode(ybConfig.getFixmedinsCode());
                        req.setFixmedinsHilistId(q.getItemCode());
                        req.setListType(q.getListType());
                        req.setMedListCodg(q.getOldCode());
                        reqs.add(req);
                    }
                    resp = outpatientService.catalogRevoke(reqs);
                } else {
                    List<CatalogUploadReq> reqs = new ArrayList<>();
                    for (HisYbUploadQueue q : ready) {
                        CatalogUploadReq req = new CatalogUploadReq();
                        req.setFixmedinsHilistId(q.getItemCode());
                        req.setFixmedinsHilistName(q.getItemName());
                        req.setListType(q.getListType());
                        req.setMedListCodg(q.getNewCode());
                        reqs.add(req);
                    }
                    resp = outpatientService.catalogUpload(reqs);
                }
                batchNo = resp == null || resp.getInfRefmsgid() == null
                        ? "B" + System.currentTimeMillis() : resp.getInfRefmsgid();
            } catch (Exception e) {
                log.error("3301/3302 调用异常: tenantId={}, revoke={}, 原因: {}", tenantId, revoke, e.getMessage());
                for (HisYbUploadQueue q : ready) {
                    markFailed(q, "医保调用异常: " + e.getMessage());
                    fail++;
                }
                continue;
            }
            if (resp.isSuccess()) {
                for (HisYbUploadQueue q : ready) {
                    q.setStatus(HisYbUploadQueue.STATUS_UPLOADED);
                    q.setBatchNo(batchNo);
                    q.setUploadTime(LocalDateTime.now());
                    q.setLastErr(null);
                    queueMapper.updateById(q);
                    ok++;
                }
            } else {
                String err = resp.isUnknown() ? "医保响应未知(超时)" : resp.getErrMsg();
                for (HisYbUploadQueue q : ready) {
                    markFailed(q, err);
                    fail++;
                }
            }
        }
        return new int[]{ok, fail};
    }

    private void markFailed(HisYbUploadQueue q, String err) {
        q.setStatus(HisYbUploadQueue.STATUS_FAILED);
        q.setLastErr(err == null || err.length() > 500 ? (err == null ? "" : err.substring(0, 500)) : err);
        queueMapper.updateById(q);
        log.warn("【对照上报】失败: {}({}) 动作={} 原因={}", q.getItemCode(), q.getCatalogType(), q.getAction(), err);
    }

    /** 失败重传: 将失败行(2)复位为待传(0), 返回复位条数 */
    public int retryFailed(Long tenantId) {
        TenantContext.set(tenantId);
        try {
            List<HisYbUploadQueue> failed = queueMapper.selectList(Wrappers.<HisYbUploadQueue>lambdaQuery()
                    .eq(HisYbUploadQueue::getStatus, HisYbUploadQueue.STATUS_FAILED));
            int n = 0;
            for (HisYbUploadQueue q : failed) {
                q.setStatus(HisYbUploadQueue.STATUS_PENDING);
                q.setLastErr(null);
                queueMapper.updateById(q);
                n++;
            }
            log.info("3301/3302 失败重传复位: tenantId={}, {} 条", tenantId, n);
            return n;
        } finally {
            TenantContext.clear();
        }
    }
}
