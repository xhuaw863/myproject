package com.yb.hi.controller.yb;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.yb.HisUploadStatus;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.UploadStatusSweeper;
import com.yb.hi.service.yb.UploadStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 医保上报中心(M5): 上传管线状态分页(biz_type/状态筛选) + 失败待补手动重传。
 * 手动重传复位退避计数后立即补传一次(UploadStatusSweeper.retryRow); 写操作 ADMIN 角色鉴权。
 */
@RestController
@RequestMapping("/api/yb/upload-status")
public class UploadStatusController {

    private final UploadStatusService uploadStatusService;
    private final UploadStatusSweeper sweeper;

    public UploadStatusController(UploadStatusService uploadStatusService, UploadStatusSweeper sweeper) {
        this.uploadStatusService = uploadStatusService;
        this.sweeper = sweeper;
    }

    /** 上传状态分页(bizType/status 可选筛选) */
    @GetMapping("/list")
    public R<IPage<HisUploadStatus>> list(@RequestParam(required = false) String bizType,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return R.fail(403, "无租户上下文");
        }
        return R.ok(uploadStatusService.page(tenantId, bizType, status, page, size));
    }

    /** 失败待补手动重传(仅 status=2 可重传; 复位退避后立即补传一次) */
    @PostMapping("/{id}/retry")
    public R<Map<String, Object>> retry(@PathVariable Long id) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return R.fail(403, "无租户上下文");
        }
        return R.ok(sweeper.retryRow(tenantId, id));
    }
}
