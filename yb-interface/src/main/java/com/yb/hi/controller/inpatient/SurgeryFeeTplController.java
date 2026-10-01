package com.yb.hi.controller.inpatient;

import com.yb.hi.entity.inpatient.HisSurgeryFeeTpl;
import com.yb.hi.entity.inpatient.HisSurgeryFeeTplItem;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.SurgeryFeeTplService;
import lombok.Data;
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
 * 手术费用模板接口(规范2.2.2.3.7.21): 个人/科室/全院三级模板 CRUD + 一键导入记账 apply。
 * 请求体: {tpl: 主表, items: 明细[]}; 可见性与维护权限在 Service 内判定。
 */
@RestController
@RequestMapping("/api/his/surgery-fee-tpl")
public class SurgeryFeeTplController {

    private final SurgeryFeeTplService tplService;
    private final OrgAccessGuard guard;

    public SurgeryFeeTplController(SurgeryFeeTplService tplService, OrgAccessGuard guard) {
        this.tplService = tplService;
        this.guard = guard;
    }

    /** 模板创建/更新请求体 */
    @Data
    public static class TplBody {
        private HisSurgeryFeeTpl tpl;
        private List<HisSurgeryFeeTplItem> items;
    }

    /** 可见模板列表(个人+本科室+全院, 记费抽屉"导入模板"下拉) */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long orgId) {
        return R.ok(tplService.listVisible(guard.scopeOrgId(orgId)));
    }

    /** 模板详情(主表+明细) */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(tplService.detail(id));
    }

    /** 新建模板(含明细) */
    @PostMapping
    public R<HisSurgeryFeeTpl> create(@RequestBody TplBody body) {
        return R.ok(tplService.create(body.getTpl(), body.getItems()));
    }

    /** 更新模板(级别不可变更, 明细全删重插) */
    @PutMapping("/{id}")
    public R<HisSurgeryFeeTpl> update(@PathVariable Long id, @RequestBody TplBody body) {
        return R.ok(tplService.update(id, body.getTpl(), body.getItems()));
    }

    /** 删除模板(级联逻辑删明细) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        tplService.delete(id);
        return R.ok(null);
    }

    /** 一键导入: 模板明细批量写入手术记账(住院/门诊双写链路由 SurgeryFeeService 决定) */
    @PostMapping("/apply")
    public R<Map<String, Object>> apply(@RequestParam Long surgeryId, @RequestParam Long tplId) {
        return R.ok(tplService.applyToSurgery(surgeryId, tplId));
    }
}
