package com.yb.hi.controller.inpatient;

import com.yb.hi.entity.inpatient.HisSurgeryAuthRule;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.SurgeryAuthRuleService;
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
 * 手术操作权限规则接口(规范2.2.2.3.7.4): 规则 CRUD + 主刀权限判定 check + 候选人带权限标记下拉 candidates。
 */
@RestController
@RequestMapping("/api/his/surgery-auth-rule")
public class SurgeryAuthRuleController {

    private final SurgeryAuthRuleService authRuleService;
    private final OrgAccessGuard guard;

    public SurgeryAuthRuleController(SurgeryAuthRuleService authRuleService, OrgAccessGuard guard) {
        this.authRuleService = authRuleService;
        this.guard = guard;
    }

    /** 规则列表 */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long orgId) {
        return R.ok(authRuleService.list(guard.scopeOrgId(orgId)));
    }

    /** 新建规则 */
    @PostMapping
    public R<HisSurgeryAuthRule> create(@RequestBody HisSurgeryAuthRule rule) {
        return R.ok(authRuleService.create(rule));
    }

    /** 更新规则 */
    @PutMapping("/{id}")
    public R<HisSurgeryAuthRule> update(@PathVariable Long id, @RequestBody HisSurgeryAuthRule rule) {
        return R.ok(authRuleService.update(id, rule));
    }

    /** 删除规则(逻辑删) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        authRuleService.delete(id);
        return R.ok(null);
    }

    /** 权限判定: 返回 allowed + reason(等级规则/自定义白名单/职工自身级别回退) */
    @GetMapping("/check")
    public R<Map<String, Object>> check(@RequestParam Long staffId,
                                        @RequestParam(required = false) String surgeryCode,
                                        @RequestParam(required = false) String surgeryName,
                                        @RequestParam(required = false) Integer surgeryLevel) {
        return R.ok(authRuleService.check(staffId, surgeryCode, surgeryName, surgeryLevel));
    }

    /** 主刀候选人列表(带 allowed/reason 标记, 供申请对话框过滤标注) */
    @GetMapping("/candidates")
    public R<List<Map<String, Object>>> candidates(@RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String surgeryCode,
                                                   @RequestParam(required = false) String surgeryName,
                                                   @RequestParam(required = false) Integer surgeryLevel) {
        return R.ok(authRuleService.candidates(keyword, surgeryCode, surgeryName, surgeryLevel));
    }
}
