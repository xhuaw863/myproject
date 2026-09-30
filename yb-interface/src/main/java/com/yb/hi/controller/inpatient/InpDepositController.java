package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.InpDepositDTO;
import com.yb.hi.entity.inpatient.HisInpDeposit;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.InpDepositService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 住院预交金接口: 缴纳/退还、流水查询、余额查询。
 * 机构归属: 写操作以 currentOrgId 落账。
 */
@RestController
@RequestMapping("/api/his/inp/deposit")
public class InpDepositController {

    private final InpDepositService inpDepositService;
    private final OrgAccessGuard guard;

    public InpDepositController(InpDepositService inpDepositService, OrgAccessGuard guard) {
        this.inpDepositService = inpDepositService;
        this.guard = guard;
    }

    /** 缴纳/退还预交金(更新 inp_visit.deposit_balance, 落 balance_after 流水) */
    @PostMapping
    public R<Map<String, Object>> deposit(@RequestBody InpDepositDTO dto) {
        return R.ok(inpDepositService.deposit(dto, guard.currentOrgId()));
    }

    /** 预交金流水(按住院就诊, 最近的在前) */
    @GetMapping("/list")
    public R<List<HisInpDeposit>> list(@RequestParam Long inpVisitId) {
        return R.ok(inpDepositService.listByVisit(inpVisitId));
    }

    /** 当前预交金余额 */
    @GetMapping("/balance/{visitId}")
    public R<BigDecimal> balance(@PathVariable Long visitId) {
        return R.ok(inpDepositService.getBalance(visitId));
    }
}
