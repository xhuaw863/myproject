package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.EmrTemplateDTO;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.EmrTemplateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 病历结构化模板接口: 模板列表/详情/按编码查询/字段定义 + 模板维护(仅牵头机构管理员)。
 * 8 类标准模板由 EmrTemplateService 启动时按租户幂等播种(@Order(7))。
 */
@RestController
@RequestMapping("/api/his/emr/template")
public class EmrTemplateController {

    private final EmrTemplateService templateService;
    private final OrgAccessGuard guard;

    public EmrTemplateController(EmrTemplateService templateService, OrgAccessGuard guard) {
        this.templateService = templateService;
        this.guard = guard;
    }

    /** 模板列表(recordType/category/scope 可选; mine=true 仅当前用户可见的启用模板) */
    @GetMapping("/list")
    public R<List<HisEmrTemplate>> list(
            @RequestParam(required = false) Integer recordType,
            @RequestParam(required = false) Integer category,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Integer scope,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return templateService.listTemplates(recordType, category, deptId, scope, mine);
    }

    /** 模板详情 */
    @GetMapping("/{id}")
    public R<HisEmrTemplate> detail(@PathVariable Long id) {
        return templateService.getTemplate(id);
    }

    /** 按编码获取模板(取版本号最大的启用版本) */
    @GetMapping("/code/{code}")
    public R<HisEmrTemplate> byCode(@PathVariable String code) {
        return templateService.getTemplateByCode(code);
    }

    /** 模板字段定义(供前端渲染结构化表单; 规范化为纯字符串键值) */
    @GetMapping("/{id}/fields")
    public R<List<Map<String, String>>> fields(@PathVariable Long id) {
        return R.ok(templateService.getFieldDefs(id));
    }

    /** 模板完整字段定义 JSON(含 dictRef/subFields/section 等新属性, 供设计器/增强渲染器) */
    @GetMapping("/{id}/defs")
    public R<Object> defs(@PathVariable Long id) {
        return templateService.getDefs(id);
    }

    /** 新建模板(归属层级 personal/dept/global 由服务层鉴权) */
    @PostMapping
    public R<HisEmrTemplate> create(@RequestBody EmrTemplateDTO dto) {
        return templateService.createTemplate(dto);
    }

    /** 更新模板(版本号自增; 按归属层级鉴权) */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody EmrTemplateDTO dto) {
        return templateService.updateTemplate(id, dto);
    }

    /** 删除模板(逻辑删除; 按归属层级鉴权) */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        return templateService.removeTemplate(id);
    }
}
