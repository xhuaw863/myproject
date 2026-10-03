package com.yb.hi.controller.emr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.inpatient.HisEmrFragment;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrFragmentService;
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
 * 病历片段接口: 可复用文档块维护(列表/详情/保存/删除) + 打印展开批量解析。
 * 表 his_emr_fragment 由 DictSchemaMigration 启动期幂等建出;
 * 写操作仅牵头机构管理员(服务层 OrgAccessGuard 守卫), 读受租户插件隔离。
 */
@RestController
@RequestMapping("/api/his/emr/fragment")
public class EmrFragmentController {

    private final EmrFragmentService fragmentService;

    public EmrFragmentController(EmrFragmentService fragmentService) {
        this.fragmentService = fragmentService;
    }

    /** 片段分页列表(scopeLevel: 0全院 1科室 2个人; deptId/staffId 归属过滤; keyword 模糊匹配编码/名称) */
    @GetMapping("/list")
    public R<IPage<HisEmrFragment>> list(@RequestParam(required = false) Integer scopeLevel,
                                         @RequestParam(required = false) Long deptId,
                                         @RequestParam(required = false) Long staffId,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return fragmentService.list(scopeLevel, deptId, staffId, keyword, page, size);
    }

    /** 片段详情(含 document 内容) */
    @GetMapping("/{id}")
    public R<HisEmrFragment> get(@PathVariable Long id) {
        return fragmentService.get(id);
    }

    /** 新建/更新片段(id 空=新建; 更新时 version 自增; 仅牵头机构管理员) */
    @PostMapping("/save")
    public R<HisEmrFragment> save(@RequestBody HisEmrFragment fragment) {
        return fragmentService.save(fragment);
    }

    /** 删除片段(逻辑删除; 仅牵头机构管理员) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        return fragmentService.delete(id);
    }

    /** 批量解析片段内容(打印展开用): body=[fragmentId, ...] → {fragmentId: document JSON} */
    @PostMapping("/resolve")
    public R<Map<Long, String>> resolve(@RequestBody List<Long> fragmentIds) {
        return R.ok(fragmentService.resolveFragments(fragmentIds));
    }
}
