package com.yb.hi.controller.inpatient;

import com.yb.hi.entity.inpatient.HisNursingTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingTemplateService;
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
 * 护理文书模板接口(护士站): 列表(按文书类型/病区作用域) / 详情 / 保存更新 / 删除(逻辑删除)。
 * document 承载 Tiptap ProseMirror JSON(节点口径与医生站一致), fields 承载字段定义 JSON。
 * 标准种子模板(NURS_GENERAL/NURS_CRITICAL/NURS_SURGERY)由 NursingTemplateService 启动期按租户播种。
 */
@RestController
@RequestMapping("/api/his/inp/nursing/template")
public class NursingTemplateController {

    private final NursingTemplateService templateService;

    public NursingTemplateController(NursingTemplateService templateService) {
        this.templateService = templateService;
    }

    /** 模板列表: recordType 过滤可选; wardId 传值时按病区作用域命中(空作用域=全院通用始终命中)。 */
    @GetMapping("/list")
    public R<List<HisNursingTemplate>> list(@RequestParam(required = false) String recordType,
                                            @RequestParam(required = false) Long wardId) {
        return R.ok(templateService.listByType(recordType, wardId));
    }

    /** 模板详情(含 document Tiptap JSON 与 fields 字段定义)。 */
    @GetMapping("/{id}")
    public R<HisNursingTemplate> detail(@PathVariable Long id) {
        return R.ok(templateService.getById(id));
    }

    /** 保存/更新模板: 无 id=新增(补默认纸张/方向/作用域), 有 id=更新(仅覆盖非 null 字段)。 */
    @PostMapping({"", "/"})
    public R<HisNursingTemplate> save(@RequestBody HisNursingTemplate tpl) {
        return R.ok(templateService.save(tpl));
    }

    /** 删除模板(逻辑删除)。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return R.ok();
    }
}
