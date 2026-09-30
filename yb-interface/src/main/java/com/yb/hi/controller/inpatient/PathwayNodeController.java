package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.PathwayNodeDTO;
import com.yb.hi.entity.inpatient.HisPathwayNode;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.PathwayTemplateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 临床路径节点接口: 列表(含任务) / 新增 / 编辑 / 删除(级联逻辑删除其下任务)。
 * 机构隔离与业务校验在 Service 层(见 PathwayTemplateService)。
 */
@RestController
@RequestMapping("/api/his/pathway/node")
public class PathwayNodeController {

    private final PathwayTemplateService templateService;

    public PathwayNodeController(PathwayTemplateService templateService) {
        this.templateService = templateService;
    }

    /** 节点列表(含每个节点下的任务列表, 按 day_no 排序) */
    @GetMapping("/list/{templateId}")
    public R<List<Map<String, Object>>> list(@PathVariable Long templateId) {
        return R.ok(templateService.listNodes(templateId));
    }

    /** 新增节点 */
    @PostMapping
    public R<HisPathwayNode> create(@RequestBody PathwayNodeDTO dto) {
        return R.ok(templateService.createNode(dto));
    }

    /** 编辑节点 */
    @PutMapping("/{id}")
    public R<HisPathwayNode> update(@PathVariable Long id, @RequestBody PathwayNodeDTO dto) {
        return R.ok(templateService.updateNode(id, dto));
    }

    /** 删除节点(级联逻辑删除其下所有任务) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        templateService.deleteNode(id);
        return R.ok();
    }
}
