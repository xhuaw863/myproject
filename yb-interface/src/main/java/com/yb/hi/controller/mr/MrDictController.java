package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.mr.HisMrBaseDict;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrDictService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 病案系统维护字典接口(P2): 类别元数据 / 分页列表 / 下拉选项 / 新增 / 编辑 / 删除。
 * 写接口 requireSelfOrgWrite 在 Service 层。type ∈ case_base|wt_base|ward|med_team|holiday。
 */
@RestController
@RequestMapping("/api/his/mr/dict")
public class MrDictController {

    private final MrDictService dictService;

    public MrDictController(MrDictService dictService) {
        this.dictService = dictService;
    }

    @GetMapping("/types")
    public R<List<String>> types() {
        return R.ok(dictService.types());
    }

    @GetMapping("/list")
    public R<IPage<HisMrBaseDict>> list(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "50") long size,
                                        @RequestParam String type,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Integer validFlag) {
        return R.ok(dictService.listPage(page, size, type, keyword, validFlag));
    }

    @GetMapping("/options")
    public R<List<HisMrBaseDict>> options(@RequestParam String type) {
        return R.ok(dictService.options(type));
    }

    @PostMapping("/{type}")
    public R<HisMrBaseDict> create(@PathVariable String type, @RequestBody Map<String, Object> body) {
        return R.ok(dictService.create(type, body));
    }

    @PutMapping("/{id}")
    public R<HisMrBaseDict> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return R.ok(dictService.update(id, body));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        dictService.delete(id);
        return R.ok();
    }
}
