package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.OrderTemplateDTO;
import com.yb.hi.entity.inpatient.HisOrderTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.OrderTemplateService;
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

/**
 * 住院医嘱模板/套餐接口: 列表/详情/创建/编辑/删除/使用计数/我的常用模板。
 */
@RestController
@RequestMapping("/api/his/inp/order-template")
public class OrderTemplateController {

    private final OrderTemplateService orderTemplateService;

    public OrderTemplateController(OrderTemplateService orderTemplateService) {
        this.orderTemplateService = orderTemplateService;
    }

    /** 模板分页(templateType/scopeType/deptId/doctorId/status/applyScene/surgeryPhase/keyword 可选) */
    @GetMapping("/list")
    public R<IPage<HisOrderTemplate>> list(@RequestParam(required = false) Integer templateType,
                                           @RequestParam(required = false) Integer scopeType,
                                           @RequestParam(required = false) Long deptId,
                                           @RequestParam(required = false) Long doctorId,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer applyScene,
                                           @RequestParam(required = false) Integer surgeryPhase,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "10") long size) {
        return orderTemplateService.list(templateType, scopeType, deptId, doctorId, status, keyword,
                applyScene, surgeryPhase, new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)));
    }

    /** 模板详情 */
    @GetMapping("/{id}")
    public R<HisOrderTemplate> detail(@PathVariable Long id) {
        return orderTemplateService.getDetail(id);
    }

    /** 创建模板 */
    @PostMapping
    public R<HisOrderTemplate> create(@RequestBody OrderTemplateDTO dto) {
        return orderTemplateService.create(dto);
    }

    /** 编辑模板(个人模板仅本人可改) */
    @PutMapping("/{id}")
    public R<HisOrderTemplate> update(@PathVariable Long id, @RequestBody OrderTemplateDTO dto) {
        return orderTemplateService.update(id, dto);
    }

    /** 删除模板(逻辑删除; 个人模板仅本人可删) */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        return orderTemplateService.remove(id);
    }

    /** 使用次数+1(开嘱引用模板时调用) */
    @PutMapping("/{id}/usage")
    public R<Void> incrementUsage(@PathVariable Long id) {
        return orderTemplateService.incrementUsage(id);
    }

    /** 我的常用模板(个人级, 热度倒序) */
    @GetMapping("/my")
    public R<List<HisOrderTemplate>> myTemplates(@RequestParam(required = false) Long doctorId) {
        return orderTemplateService.getMyTemplates(doctorId);
    }
}
