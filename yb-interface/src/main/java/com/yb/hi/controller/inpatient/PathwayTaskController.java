package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.PathwayTaskDTO;
import com.yb.hi.entity.inpatient.HisPathwayTask;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.PathwayTemplateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 临床路径节点任务接口: 新增 / 编辑 / 删除 / 批量添加(关联收费项目或药品)。
 * 机构隔离与业务校验在 Service 层(见 PathwayTemplateService)。
 */
@RestController
@RequestMapping("/api/his/pathway/task")
public class PathwayTaskController {

    private final PathwayTemplateService templateService;

    public PathwayTaskController(PathwayTemplateService templateService) {
        this.templateService = templateService;
    }

    /** 新增任务(关联收费项目或药品) */
    @PostMapping
    public R<HisPathwayTask> create(@RequestBody PathwayTaskDTO dto) {
        return R.ok(templateService.createTask(dto));
    }

    /** 编辑任务 */
    @PutMapping("/{id}")
    public R<HisPathwayTask> update(@PathVariable Long id, @RequestBody PathwayTaskDTO dto) {
        return R.ok(templateService.updateTask(id, dto));
    }

    /** 删除任务 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        templateService.deleteTask(id);
        return R.ok();
    }

    /** 批量添加任务(单事务, 任一条失败整批回滚) */
    @PostMapping("/batch")
    public R<List<HisPathwayTask>> batch(@RequestBody List<PathwayTaskDTO> dtos) {
        return R.ok(templateService.batchCreateTasks(dtos));
    }
}
