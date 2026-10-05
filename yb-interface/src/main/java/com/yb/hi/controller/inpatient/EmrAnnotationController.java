package com.yb.hi.controller.inpatient;

import com.yb.hi.entity.inpatient.HisEmrAnnotation;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrAnnotationService;
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
 * 病历批注与修订线程接口(高级版): 设计器/文书协作审阅的批注增删查与解决状态维护。
 * 修订留痕本体在文档 emrTrack mark 内随模板保存, 本接口只管理批注文本/回复/锚点元数据。
 */
@RestController
@RequestMapping("/api/his/emr/annotation")
public class EmrAnnotationController {

    private final EmrAnnotationService annotationService;

    public EmrAnnotationController(EmrAnnotationService annotationService) {
        this.annotationService = annotationService;
    }

    /** 按目标拉取批注线程(targetType=template|record, targetId) */
    @GetMapping("/list")
    public R<List<HisEmrAnnotation>> list(@RequestParam String targetType, @RequestParam Long targetId) {
        return annotationService.listByTarget(targetType, targetId);
    }

    /** 新增批注/回复(parentId 非空=回复) */
    @PostMapping
    public R<HisEmrAnnotation> add(@RequestBody HisEmrAnnotation in) {
        return annotationService.add(in);
    }

    /** 标记解决/重新打开(status=open|resolved) */
    @PutMapping("/{id}/status")
    public R<Void> setStatus(@PathVariable Long id, @RequestParam String status) {
        return annotationService.setStatus(id, status);
    }

    /** 删除批注(仅作者本人或管理员) */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        return annotationService.delete(id);
    }
}
