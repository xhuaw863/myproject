package com.yb.hi.controller.yb;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.yb.HisYbUploadQueue;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.yb.HisYbUploadQueueMapper;
import com.yb.hi.service.yb.CatalogUploadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 目录对照上传管理端(M4): 3301/3302 队列查看 + 手动触发上报 + 失败重传。
 * 事件源为医保目录对照变更(his_yb_map_log), 队列自动累积, 上报按每批 ≤100 条先撤后传。
 */
@RestController
@RequestMapping("/api/yb/catalog-upload")
public class CatalogUploadController {

    private final CatalogUploadService catalogUploadService;
    private final HisYbUploadQueueMapper queueMapper;

    public CatalogUploadController(CatalogUploadService catalogUploadService,
                                   HisYbUploadQueueMapper queueMapper) {
        this.catalogUploadService = catalogUploadService;
        this.queueMapper = queueMapper;
    }

    /** 手动触发上报(可选按目录类型过滤: charge/drug/cons; 先 3302 撤销批后 3301 上传批) */
    @PostMapping("/run")
    public R<Map<String, Object>> run(@RequestParam(required = false) String catalog) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return R.fail(403, "无租户上下文");
        }
        try {
            return R.ok(catalogUploadService.runUpload(tenantId, catalog));
        } catch (Exception e) {
            return R.fail(500, "对照上报失败: " + e.getMessage());
        }
    }

    /** 失败重传: 失败行复位为待传(配合 run 重新上报) */
    @PostMapping("/retry")
    public R<String> retry() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return R.fail(403, "无租户上下文");
        }
        return R.ok("已复位 " + catalogUploadService.retryFailed(tenantId) + " 条待重传");
    }

    /** 队列分页(catalog/status 可选过滤) */
    @GetMapping("/queue")
    public R<IPage<HisYbUploadQueue>> queue(@RequestParam(required = false) String catalog,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size) {
        IPage<HisYbUploadQueue> p = queueMapper.selectPage(new Page<>(page, size),
                Wrappers.<HisYbUploadQueue>lambdaQuery()
                        .eq(HisYbUploadQueue::getDeleted, 0)
                        .eq(catalog != null && !catalog.isEmpty(), HisYbUploadQueue::getCatalogType, catalog)
                        .eq(status != null, HisYbUploadQueue::getStatus, status)
                        .orderByDesc(HisYbUploadQueue::getId));
        return R.ok(p);
    }
}
