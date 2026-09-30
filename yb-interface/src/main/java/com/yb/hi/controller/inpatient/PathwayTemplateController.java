package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.inpatient.PathwayTemplateDTO;
import com.yb.hi.entity.inpatient.HisPathwayTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.PathwayTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 临床路径模板接口: 分页列表 / 详情(含节点任务树) / 创建 / 编辑 / 启停切换 / 复制新版本。
 * 机构隔离与业务校验在 Service 层(见 PathwayTemplateService)。
 */
@RestController
@RequestMapping("/api/his/pathway/template")
public class PathwayTemplateController {

    private final PathwayTemplateService templateService;
    private final OrgAccessGuard guard;

    public PathwayTemplateController(PathwayTemplateService templateService, OrgAccessGuard guard) {
        this.templateService = templateService;
        this.guard = guard;
    }

    /** 模板列表(分页, keyword 匹配路径名/诊断名, status 可选: 1启用 0停用) */
    @GetMapping("/list")
    public R<IPage<HisPathwayTemplate>> list(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(templateService.listTemplates(guard.scopeOrgId(orgId), keyword, status, page, size));
    }

    /** 模板详情(含全部节点, 每个节点含全部任务) */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(templateService.getDetail(id));
    }

    /** 创建模板(初始 version=1, status=1启用) */
    @PostMapping
    public R<HisPathwayTemplate> create(@RequestBody PathwayTemplateDTO dto) {
        return R.ok(templateService.create(dto));
    }

    /** 编辑模板 */
    @PutMapping("/{id}")
    public R<HisPathwayTemplate> update(@PathVariable Long id, @RequestBody PathwayTemplateDTO dto) {
        return R.ok(templateService.update(id, dto));
    }

    /** 启用/停用切换 */
    @PutMapping("/{id}/status")
    public R<HisPathwayTemplate> toggleStatus(@PathVariable Long id) {
        return R.ok(templateService.toggleStatus(id));
    }

    /** 复制为新版本(version+1, pathwayCode 追加 -vN, 深拷贝节点任务) */
    @PostMapping("/{id}/copy")
    public R<HisPathwayTemplate> copy(@PathVariable Long id) {
        return R.ok(templateService.copyTemplate(id));
    }
}
