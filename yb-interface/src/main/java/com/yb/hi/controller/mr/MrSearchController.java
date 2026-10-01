package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrSearchService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 病案检索查询接口(P1-D): 快速检索 / 复合检索(四条件块并行) / 诊断手术检索 / 出院人数对比。
 * 均为读接口, 机构隔离 scopeOrgId 在 Service 层。
 */
@RestController
@RequestMapping("/api/his/mr/search")
public class MrSearchController {

    private final MrSearchService searchService;

    public MrSearchController(MrSearchService searchService) {
        this.searchService = searchService;
    }

    /** 快速检索: 关键字跨姓名/住院号/编目号/主诊。 */
    @GetMapping("/quick")
    public R<IPage<Map<String, Object>>> quick(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long deptId,
                                               @RequestParam(required = false) Integer status) {
        return R.ok(searchService.quick(page, size, keyword, deptId, status));
    }

    /** 复合检索: 患者/诊断/手术/就诊属性四条件块按 logic 组合。 */
    @PostMapping("/advanced")
    public R<IPage<Map<String, Object>>> advanced(@RequestBody Map<String, Object> req) {
        return R.ok(searchService.advanced(req));
    }

    /** 诊断手术检索: 按编码/名称命中医嘱诊断或手术。scope=diag|oper|both。 */
    @GetMapping("/diag-oper")
    public R<IPage<Map<String, Object>>> diagOper(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(required = false) String code,
                                                  @RequestParam(required = false) String name,
                                                  @RequestParam(required = false, defaultValue = "both") String scope) {
        return R.ok(searchService.diagOper(page, size, code, name, scope));
    }

    /** 出院人数对比: 当期 vs 对比期, 按出院科室统计。 */
    @GetMapping("/discharge-compare")
    public R<Map<String, Object>> dischargeCompare(@RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(required = false) String compareFrom,
                                                   @RequestParam(required = false) String compareTo) {
        return R.ok(searchService.dischargeCompare(from, to, compareFrom, compareTo));
    }
}
