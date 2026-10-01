package com.yb.hi.controller.mr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.mr.MrDrgService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * DRG/DIP 预分组与入组对比接口(P3-A): 分组列表 / 同诊断入组差异对比 / 入组概览汇总。
 * 只读接口, 机构隔离经 OrgAccessGuard。
 */
@RestController
@RequestMapping("/api/his/mr/drg")
public class MrDrgController {

    private final MrDrgService drgService;

    public MrDrgController(MrDrgService drgService) {
        this.drgService = drgService;
    }

    /** 已编目病案 DRG/DIP 分组分页列表。 */
    @GetMapping("/list")
    public R<IPage<Map<String, Object>>> list(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) Long deptId,
                                              @RequestParam(required = false) String drgCode,
                                              @RequestParam(required = false) String dipCode,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate) {
        return R.ok(drgService.listGrouped(page, size, deptId, drgCode, dipCode, startDate, endDate));
    }

    /** 同诊断入组差异对比: 按诊断聚合各 DRG/DIP 分布。 */
    @GetMapping("/comparison")
    public R<List<Map<String, Object>>> comparison(@RequestParam(required = false) String diagCode,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return R.ok(drgService.comparisonByDiag(diagCode, limit));
    }

    /** DRG/DIP 入组概览汇总。 */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary(@RequestParam(required = false) String startDate,
                                          @RequestParam(required = false) String endDate) {
        return R.ok(drgService.summary(startDate, endDate));
    }
}
