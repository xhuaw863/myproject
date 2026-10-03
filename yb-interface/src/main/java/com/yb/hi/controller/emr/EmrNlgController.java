package com.yb.hi.controller.emr;

import com.yb.hi.entity.inpatient.HisEmrNlgTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrNlgService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病历NLG接口: 章节生成模板维护(列表/详情/保存/删除) + 自然语言生成(单章节/整文)。
 * 表 his_emr_nlg_template 由 DictSchemaMigration 启动期幂等建出;
 * 写操作仅牵头机构管理员(服务层 OrgAccessGuard 守卫), 读受租户插件隔离, 生成接口不限角色。
 */
@RestController
@RequestMapping("/api/his/emr/nlg")
public class EmrNlgController {

    private final EmrNlgService nlgService;

    public EmrNlgController(EmrNlgService nlgService) {
        this.nlgService = nlgService;
    }

    /** 模板列表(recordType 可选: 门诊2/住院1(及其他住院文书类型), 空或0=全部; sectionKey 可选精确过滤) */
    @GetMapping("/list")
    public R<List<HisEmrNlgTemplate>> list(@RequestParam(required = false) String recordType,
                                           @RequestParam(required = false) String sectionKey) {
        return nlgService.list(recordType, sectionKey);
    }

    /** 模板详情 */
    @GetMapping("/{id}")
    public R<HisEmrNlgTemplate> get(@PathVariable Long id) {
        return nlgService.get(id);
    }

    /** 新建/更新模板(id 空=新建; 仅牵头机构管理员) */
    @PostMapping("/save")
    public R<HisEmrNlgTemplate> save(@RequestBody HisEmrNlgTemplate template) {
        return nlgService.save(template);
    }

    /** 删除模板(逻辑删除; 仅牵头机构管理员) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        return nlgService.delete(id);
    }

    /** 单章节生成: body={sectionKey, fieldValues:{字段key:值}} → 生成的叙述文本 */
    @PostMapping("/generate")
    public R<String> generate(@RequestBody Map<String, Object> body) {
        String sectionKey = body == null || body.get("sectionKey") == null
                ? null : String.valueOf(body.get("sectionKey"));
        Map<String, Object> fieldValues = null;
        Object fv = body == null ? null : body.get("fieldValues");
        if (fv instanceof Map<?, ?>) {
            fieldValues = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) fv).entrySet()) {
                fieldValues.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return nlgService.generate(sectionKey, fieldValues);
    }

    /** 整文生成: body={documentJson: Tiptap明文JSON, recordType} → Map<sectionKey, 生成文本>(未配置模板章节跳过) */
    @PostMapping("/generate-document")
    public R<Map<String, String>> generateDocument(@RequestBody Map<String, Object> body) {
        String documentJson = body == null || body.get("documentJson") == null
                ? null : String.valueOf(body.get("documentJson"));
        String recordType = body == null || body.get("recordType") == null
                ? null : String.valueOf(body.get("recordType"));
        return nlgService.generateForDocument(documentJson, recordType);
    }
}
