package com.yb.hi.controller.emr;

import com.yb.hi.entity.inpatient.HisCdssRule;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.CdssEngineService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CDSS 临床决策支持接口: 规则维护(列表/详情/保存/删除) + 条件评估(字段直评 / 病历文档抽取评估)。
 * 表 his_cdss_rule 由 DictSchemaMigration 启动期幂等建出;
 * 规则维护仅牵头机构管理员(服务层 OrgAccessGuard 守卫), 评估接口供医生站实时调用(登录即用, 不做角色限制)。
 */
@RestController
@RequestMapping("/api/his/emr/cdss")
public class CdssRuleController {

    private final CdssEngineService cdssEngineService;

    public CdssRuleController(CdssEngineService cdssEngineService) {
        this.cdssEngineService = cdssEngineService;
    }

    /** 规则列表(ruleType: drug_conflict/dose_alert/repeat_exam/critical_value/guideline 可选精确过滤;
     *  deptCode 非空时仅返回全局规则+适用该科室的规则; 列表含停用规则, enabled 字段区分) */
    @GetMapping("/rules")
    public R<List<HisCdssRule>> listRules(@RequestParam(required = false) String ruleType,
                                          @RequestParam(required = false) String deptCode) {
        return cdssEngineService.listRules(ruleType, deptCode);
    }

    /** 规则详情 */
    @GetMapping("/rule/{id}")
    public R<HisCdssRule> getRule(@PathVariable Long id) {
        return cdssEngineService.getRule(id);
    }

    /** 新建/更新规则(id 空=新建; 仅牵头机构管理员) */
    @PostMapping("/rule/save")
    public R<HisCdssRule> saveRule(@RequestBody HisCdssRule rule) {
        return cdssEngineService.saveRule(rule);
    }

    /** 删除规则(逻辑删除; 仅牵头机构管理员) */
    @DeleteMapping("/rule/{id}")
    public R<Void> deleteRule(@PathVariable Long id) {
        return cdssEngineService.deleteRule(id);
    }

    /** 条件评估: body={recordType, fieldValues:{fieldKey:value}, deptCode} → 命中告警 [{ruleCode,ruleName,level,message}] */
    @PostMapping("/evaluate")
    public R<List<Map<String, Object>>> evaluate(@RequestBody Map<String, Object> body) {
        return cdssEngineService.evaluate(text(body == null ? null : body.get("recordType")),
                fieldValues(body), text(body == null ? null : body.get("deptCode")));
    }

    /** 病历文档评估: body={documentJson, recordType, deptCode} → 先抽取 emrField 要素再评估 */
    @PostMapping("/evaluate-document")
    public R<List<Map<String, Object>>> evaluateDocument(@RequestBody Map<String, Object> body) {
        return cdssEngineService.evaluateDocument(text(body == null ? null : body.get("documentJson")),
                text(body == null ? null : body.get("recordType")),
                text(body == null ? null : body.get("deptCode")));
    }

    /* ================= 内部 ================= */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldValues(Map<String, Object> body) {
        Object v = body == null ? null : body.get("fieldValues");
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static String text(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
