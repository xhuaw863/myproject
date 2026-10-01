package com.yb.hi.controller.medtech;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.medtech.PacsIntegrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部影像 PACS/DICOMweb 集成接口(T2 阶段5-1)。
 * 边界: 本系统离线、无厂商端点, 此为"接入骨架/Mock", 非真接院外 PACS; 详见 {@link PacsIntegrationService}。
 * 机构归属在服务层守卫(仅登录机构范围内报告, 牵头可跨)。
 */
@RestController
@RequestMapping("/api/pacs")
public class PacsController {

    private final PacsIntegrationService pacsService;

    public PacsController(PacsIntegrationService pacsService) {
        this.pacsService = pacsService;
    }

    /** 集成配置视图(enabled/mode/mock/baseUrlConfigured), 供前端提示与运维核对。 */
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        return R.ok(pacsService.config());
    }

    /** 某报告的 DICOM Study 列表(检验/未启用返回空)。 */
    @GetMapping("/report/{reportId}/studies")
    public R<List<Map<String, Object>>> studies(@PathVariable Long reportId) {
        return R.ok(pacsService.studies(reportId));
    }

    /** 某报告的查看器 URL(mock 内嵌路由 / dicomweb 外部 URL; 未启用或非影像返回 null url)。 */
    @GetMapping("/report/{reportId}/viewer")
    public R<Map<String, Object>> viewer(@PathVariable Long reportId) {
        return R.ok(pacsService.viewer(reportId));
    }
}
