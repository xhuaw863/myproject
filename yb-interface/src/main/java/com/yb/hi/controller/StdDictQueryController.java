package com.yb.hi.controller;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 标准字典查询接口(供业务对照选择)。
 * 统一返回 {code,name,spec,extra} 分页结构, 复用现有 DictQueryController 风格。
 * 标准字典为全局共享数据, 不做租户隔离。
 */
@RestController
@RequestMapping("/api/std-dict/query")
public class StdDictQueryController {

    private final StdDictQueryService queryService;

    public StdDictQueryController(StdDictQueryService queryService) {
        this.queryService = queryService;
    }

    /** 可查询的标准字典元信息列表: [{key,name,stdType,srcDoc}] */
    @GetMapping("/types")
    public R<List<Map<String, Object>>> types() {
        return R.ok(queryService.typesMeta());
    }

    /** 值域下拉取值: type=cv_code|wst364|hbvalue|whvalue, code=分组编码(dict_code/cv_code), 返回 [{code,name}] */
    @GetMapping("/values")
    public R<List<Map<String, Object>>> values(@RequestParam String type, @RequestParam String code) {
        return R.ok(queryService.values(type, code));
    }

    /** 分页查询: type=drug|consumable|med_service|tcm|preparation|ivd|cons_item_rel|
     *  icd10|icd9|icd10_nat|icd9_nat|morphology|tcm_disease|tcm_syndrome|tcm_mapping */
    @GetMapping("/{type}")
    public R<Map<String, Object>> query(@PathVariable String type,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "20") long size) {
        Map<String, Object> data = queryService.query(type, keyword, page, size);
        if (data.containsKey("error")) {
            return R.fail(String.valueOf(data.get("error")));
        }
        return R.ok(data);
    }
}
