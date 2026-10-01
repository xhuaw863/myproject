package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.mr.HisMrCatalog;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrCatalogService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 病案编目接口(病案统计科侧): 编目列表 / 生成快照 / 详情 / 保存头与明细 / 定稿 / 实时校验。
 * 读接口对已登录用户开放; 写接口锁定守卫在 Service(requireEditable: 锁定 409)。机构隔离经 OrgAccessGuard。
 */
@RestController
@RequestMapping("/api/his/mr/catalog")
public class MrCatalogController {

    private final MrCatalogService catalogService;

    public MrCatalogController(MrCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** 编目分页(未录/已录): catalogStatus 编目状态, catalogerId 我的待办, keyword 姓名/住院号/病案号。 */
    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) Integer catalogStatus,
                                              @RequestParam(required = false) Long catalogerId,
                                              @RequestParam(required = false) Long deptId,
                                              @RequestParam(required = false) String keyword) {
        return R.ok(catalogService.listPage(page, size, catalogStatus, catalogerId, keyword, deptId));
    }

    /** 生成/重建编目快照(幂等)。 */
    @PostMapping("/generate/{visitId}")
    public R<HisMrCatalog> generate(@PathVariable Long visitId) {
        return R.ok(catalogService.generate(visitId));
    }

    /** 编目详情(主表 + 诊断/手术/扩展明细 + 校验错误 + 留痕)。 */
    @GetMapping("/{visitId}")
    public R<Map<String, Object>> detail(@PathVariable Long visitId) {
        return R.ok(catalogService.detail(visitId));
    }

    /** 保存首页头信息(白名单字段 + 变更留痕)。 */
    @PutMapping("/{visitId}/header")
    public R<Void> saveHeader(@PathVariable Long visitId, @RequestBody Map<String, Object> data) {
        catalogService.saveHeader(visitId, data);
        return R.ok();
    }

    /** 全量替换诊断明细。 */
    @PutMapping("/{visitId}/diags")
    public R<Void> saveDiags(@PathVariable Long visitId, @RequestBody List<Map<String, Object>> diags) {
        catalogService.saveDiags(visitId, diags);
        return R.ok();
    }

    /** 全量替换手术明细。 */
    @PutMapping("/{visitId}/opers")
    public R<Void> saveOpers(@PathVariable Long visitId, @RequestBody List<Map<String, Object>> opers) {
        catalogService.saveOpers(visitId, opers);
        return R.ok();
    }

    /** 全量替换扩展记录(转科/过敏/重症)。 */
    @PutMapping("/{visitId}/others/{recType}")
    public R<Void> saveOthers(@PathVariable Long visitId, @PathVariable String recType,
                              @RequestBody List<Map<String, Object>> rows) {
        catalogService.saveOthers(visitId, recType, rows);
        return R.ok();
    }

    /** 编目定稿(强制类校验通过→已编目)。 */
    @PostMapping("/{visitId}/finalize")
    public R<List<Map<String, Object>>> finalizeCatalog(@PathVariable Long visitId) {
        return R.ok(catalogService.finalizeCatalog(visitId));
    }

    /** 实时校验(不落库), 供右侧错误栏定位。 */
    @GetMapping("/{visitId}/validate")
    public R<List<Map<String, Object>>> validate(@PathVariable Long visitId) {
        return R.ok(catalogService.validate(visitId));
    }
}
