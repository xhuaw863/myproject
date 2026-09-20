package com.yb.hi.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.StdDictImportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 标准字典维护(导入)接口 —— 仅平台超级管理员可用。
 * 从"字典标准"文件夹的 xlsx 中提取标准字典入库(std_* 表), 以湖北省医保编码数据库为主。
 * 幂等: 每次导入先清空目标表再全量写入。
 * 标准字典为全局共享的权威参照数据, 只允许平台超管维护; 其它用户仅能通过
 * StdDictQueryController(/api/std-dict/query) 浏览。
 */
@RestController
@RequestMapping("/api/std-dict")
public class StdDictImportController {

    private final StdDictImportService importService;

    public StdDictImportController(StdDictImportService importService) {
        this.importService = importService;
    }

    /** 可导入的标准字典标识列表 */
    @GetMapping("/types")
    public R<List<String>> types() {
        requireSuper();
        return R.ok(importService.keys());
    }

    /** 导入单类标准字典: type=drug|consumable|med_service|tcm|preparation|ivd|cons_item_rel|
     *  icd10|icd9|icd10_nat|icd9_nat|morphology|tcm_disease|tcm_syndrome|tcm_mapping */
    @GetMapping("/import/{type}")
    public R<Map<String, Object>> importOne(@PathVariable String type) {
        requireSuper();
        return R.ok(importService.importOne(type));
    }

    /** 导入全部标准字典(数据量大, 耗时较长) */
    @GetMapping("/import/all")
    public R<Map<String, Object>> importAll() {
        requireSuper();
        return R.ok(importService.importAll());
    }

    /** 查看导入登记 */
    @GetMapping("/versions")
    public R<List<Map<String, Object>>> versions() {
        requireSuper();
        return R.ok(importService.versions());
    }

    /** 仅平台超级管理员可维护标准字典 */
    private void requireSuper() {
        LoginUser u = UserContext.get();
        if (u == null || !Roles.SUPER_ADMIN.equals(u.getRole())) {
            throw new BizException(403, "标准字典仅平台超级管理员可维护, 其它用户只能浏览");
        }
    }
}
