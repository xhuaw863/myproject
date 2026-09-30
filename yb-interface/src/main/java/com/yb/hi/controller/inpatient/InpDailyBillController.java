package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.inpatient.HisInpDailyBill;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.InpDailyBillService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 住院每日费用清单接口: 单患者生成/全院批量生成/查询/打印留痕。
 * 机构隔离: 批量生成以 currentOrgId 归属; 单据查询按就诊维度(租户插件自动隔离)。
 */
@RestController
@RequestMapping("/api/his/inp/daily-bill")
public class InpDailyBillController {

    private final InpDailyBillService inpDailyBillService;
    private final OrgAccessGuard guard;

    public InpDailyBillController(InpDailyBillService inpDailyBillService, OrgAccessGuard guard) {
        this.inpDailyBillService = inpDailyBillService;
        this.guard = guard;
    }

    /** 生成某患者某日费用清单(幂等: 已生成直接返回既有清单) */
    @PostMapping("/generate")
    public R<HisInpDailyBill> generate(@RequestParam Long visitId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return inpDailyBillService.generateDailyBill(visitId, date);
    }

    /** 全院批量生成某日清单(在院患者逐个生成, orgId 取当前登录机构) */
    @PostMapping("/batch-generate")
    public R<Map<String, Object>> batchGenerate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return inpDailyBillService.batchGenerate(date, guard.currentOrgId());
    }

    /** 获取某患者某日清单(未生成报404) */
    @GetMapping
    public R<HisInpDailyBill> get(@RequestParam Long visitId,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return inpDailyBillService.getDailyBill(visitId, date);
    }

    /** 患者清单分页列表(按清单日期倒序) */
    @GetMapping("/list")
    public R<IPage<HisInpDailyBill>> list(@RequestParam Long visitId,
                                          @RequestParam(required = false, defaultValue = "1") long page,
                                          @RequestParam(required = false, defaultValue = "20") long size) {
        return inpDailyBillService.listByVisit(visitId, page, size);
    }

    /** 标记已打印(printed_flag=1 + 打印时间) */
    @PutMapping("/{id}/printed")
    public R<Void> markPrinted(@PathVariable Long id) {
        return inpDailyBillService.markPrinted(id);
    }
}
