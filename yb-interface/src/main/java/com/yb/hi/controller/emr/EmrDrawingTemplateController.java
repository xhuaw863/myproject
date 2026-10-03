package com.yb.hi.controller.emr;

import com.yb.hi.entity.inpatient.HisEmrDrawingTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrDrawingTemplateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 医学图示模板接口: SVG 标注底图维护(列表/详情/保存/删除)。
 * 表 his_emr_drawing_template 由 DictSchemaMigration 启动期幂等建出;
 * 写操作仅牵头机构管理员(服务层 OrgAccessGuard 守卫), 读受租户插件隔离。
 */
@RestController
@RequestMapping("/api/his/emr/drawing-template")
public class EmrDrawingTemplateController {

    private final EmrDrawingTemplateService templateService;

    public EmrDrawingTemplateController(EmrDrawingTemplateService templateService) {
        this.templateService = templateService;
    }

    /** 图示模板列表(category 可选: body_front/body_back/head/oral/hand/foot/wound/custom; keyword 模糊匹配编码/名称; 不含SVG大字段) */
    @GetMapping("/list")
    public R<List<HisEmrDrawingTemplate>> list(@RequestParam(required = false) String category,
                                               @RequestParam(required = false) String keyword) {
        return templateService.list(category, keyword);
    }

    /** 模板详情(含 SVG 源串) */
    @GetMapping("/{id}")
    public R<HisEmrDrawingTemplate> get(@PathVariable Long id) {
        return templateService.get(id);
    }

    /** 新建/更新模板(id 空=新建; 仅牵头机构管理员) */
    @PostMapping("/save")
    public R<HisEmrDrawingTemplate> save(@RequestBody HisEmrDrawingTemplate template) {
        return templateService.save(template);
    }

    /** 删除模板(逻辑删除; 仅牵头机构管理员) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        return templateService.delete(id);
    }
}
