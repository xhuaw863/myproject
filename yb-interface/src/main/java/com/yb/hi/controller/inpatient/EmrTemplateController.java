package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.EmrTemplateBatchDTO;
import com.yb.hi.dto.inpatient.EmrTemplateDTO;
import com.yb.hi.dto.inpatient.EmrTemplateFromDatasetDTO;
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

    /** 母板锁定章节向下传播(全院/科室母板 → 直接子模板; 超过阈值自动转后台异步) */
    @PostMapping("/propagate/{id}")
    public R<Map<String, Object>> propagate(@PathVariable Long id) {
        return templateService.propagate(id);
    }

    /** 按层级查询有效模板(合并视图: 个人覆盖科室覆盖全院; scopeLevel 0仅全院/1加科室/2全三级) */
    @GetMapping("/listByScope")
    public R<List<HisEmrTemplate>> listByScope(
            @RequestParam(required = false) Integer scopeLevel,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Long staffId) {
        return templateService.listByScope(scopeLevel, deptId, staffId);
    }

    /** 由数据集生成 Tiptap 文档模板(章节/小节/数据元 → emrSection/emrField 节点树) */
    @PostMapping("/createFromDataset")
    public R<HisEmrTemplate> createFromDataset(@RequestBody EmrTemplateFromDatasetDTO dto) {
        return templateService.createFromDataset(dto.getDatasetId(), dto.getName(), dto.getScopeLevel());
    }

    /** 批量更新数据元属性(attrs 逐键覆盖; Tiptap 文档与 fields 定义双写) */
    @PostMapping("/batch/updateElementAttr")
    public R<Map<String, Object>> batchUpdateElementAttr(@RequestBody EmrTemplateBatchDTO dto) {
        return templateService.batchUpdateElementAttr(dto.getTemplateIds(), dto.getFieldKey(), dto.getAttrs());
    }

    /** 批量替换章节内容(emrSection.attrs.key 命中; 母板锁定的子模板章节自动跳过) */
    @PostMapping("/batch/replaceSection")
    public R<Map<String, Object>> batchReplaceSection(@RequestBody EmrTemplateBatchDTO dto) {
        return templateService.batchReplaceSection(dto.getTemplateIds(), dto.getSectionKey(), dto.getNewContent());
    }

    /** 批量替换文档页眉 */
    @PostMapping("/batch/replaceHeader")
    public R<Map<String, Object>> batchReplaceHeader(@RequestBody EmrTemplateBatchDTO dto) {
        return templateService.batchReplaceHeader(dto.getTemplateIds(), dto.getNewHeader());
    }
}
