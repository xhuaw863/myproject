package com.yb.hi.controller.mr;

import com.yb.hi.entity.mr.HisMrAnnotation;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrAnnotationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 病案批注与反馈接口(P1-C): 某病案批注列表 / 我的未处理 / 新增批注或回复 / 标记处理 / 删除。
 * 写接口 requireSelfOrgWrite 在 Service 层。
 */
@RestController
@RequestMapping("/api/his/mr/annotation")
public class MrAnnotationController {

    private final MrAnnotationService annotationService;

    public MrAnnotationController(MrAnnotationService annotationService) {
        this.annotationService = annotationService;
    }

    /** 某病案全部批注(顶层+回复, 时间升序)。 */
    @GetMapping("/list/{visitId}")
    public R<List<HisMrAnnotation>> list(@PathVariable Long visitId) {
        return R.ok(annotationService.listByVisit(visitId));
    }

    /** 我的未处理批注(接收人=当前职工)。 */
    @GetMapping("/my-todo")
    public R<List<HisMrAnnotation>> myTodo() {
        return R.ok(annotationService.myTodo());
    }

    /** 新增批注/回复: body {visitId, parentId?, toStaffId?, toStaffName?, annType?, targetField?, content}。 */
    @PostMapping
    public R<HisMrAnnotation> create(@RequestBody Map<String, Object> body) {
        return R.ok(annotationService.create(body));
    }

    /** 标记处理: body {resolved:0|1}。 */
    @PutMapping("/{id}/resolve")
    public R<Void> resolve(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer resolved = body == null ? null : toInt(body.get("resolved"), 0);
        annotationService.setResolved(id, resolved);
        return R.ok();
    }

    /** 删除批注(连同直接回复)。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        annotationService.delete(id);
        return R.ok();
    }

    /** 宽松 int 解析: 兼容 Number 与被序列化为字符串的布尔/数字。 */
    private static int toInt(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof Boolean) {
            return ((Boolean) o) ? 1 : 0;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
