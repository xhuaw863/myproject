package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.inpatient.InpChargeQueryDTO;
import com.yb.hi.dto.inpatient.InpSettleDTO;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpSettle;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.InpChargeService;
import com.yb.hi.service.inpatient.InpSettleService;
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
 * 住院结算接口: 费用明细/汇总/补录、预结算(2303)、正式结算(2301+2304)、结算历史、撤销结算(2305)、住院日结。
 * 机构隔离: 读走 scopeOrgId; 写(补录/结算)以 currentOrgId 归属。
 */
@RestController
@RequestMapping("/api/his/inp/settle")
public class InpSettleController {

    private final InpSettleService inpSettleService;
    private final InpChargeService inpChargeService;
    private final OrgAccessGuard guard;

    public InpSettleController(InpSettleService inpSettleService, InpChargeService inpChargeService,
                               OrgAccessGuard guard) {
        this.inpSettleService = inpSettleService;
        this.inpChargeService = inpChargeService;
        this.guard = guard;
    }

    /** 费用明细列表(分页; 按就诊+记账日期+费用类别筛选) */
    @GetMapping("/charge/list")
    public R<IPage<HisInpChargeDetail>> chargeList(InpChargeQueryDTO dto,
                                                   @RequestParam(required = false) Long orgId) {
        return R.ok(inpChargeService.listCharges(dto, guard.scopeOrgId(orgId)));
    }

    /** 费用汇总(按费用类别分组 SUM, 含合计) */
    @GetMapping("/charge/summary/{visitId}")
    public R<Map<String, Object>> chargeSummary(@PathVariable Long visitId) {
        return R.ok(inpChargeService.chargeSummary(visitId));
    }

    /** 手动补录费用(传 chargeItemId 时服务端按目录定价, 金额服务端计算) */
    @PostMapping("/charge")
    public R<HisInpChargeDetail> addCharge(@RequestBody HisInpChargeDetail detail) {
        return R.ok(inpChargeService.addCharge(detail, guard.currentOrgId()));
    }

    /** 预结算(医保患者透传2303取试算, 非医保仅院内试算) */
    @PostMapping("/pre")
    public R<Map<String, Object>> preSettle(@RequestParam Long visitId) {
        return R.ok(inpSettleService.preSettle(visitId));
    }

    /** 正式结算(出院/中途共用: 汇总未结算费用->扣预交金->医保链路->落结算单; 出院释放床位, 中途留院) */
    @PostMapping
    public R<Map<String, Object>> settle(@RequestBody InpSettleDTO dto) {
        return R.ok(inpSettleService.settle(dto, guard.currentOrgId()));
    }

    /** 结算历史(含中途/出院/退费, 按结算时间倒序; 已撤销记录 yb_status=4 供前端标识) */
    @GetMapping("/settlements/{visitId}")
    public R<List<HisInpSettle>> listSettlements(@PathVariable Long visitId) {
        return R.ok(inpSettleService.listSettlements(visitId));
    }

    /** 撤销结算(医保患者调2305, 就诊回在院, 预交金余额冲回) */
    @PostMapping("/{id}/cancel")
    public R<Map<String, Object>> cancel(@PathVariable Long id) {
        return R.ok(inpSettleService.cancelSettle(id));
    }

    /** 住院日结统计(当日结算/出入院/预交金汇总, date 缺省当天) */
    @GetMapping("/daily")
    public R<Map<String, Object>> daily(@RequestParam(required = false) Long orgId,
                                        @RequestParam(required = false) String date) {
        return R.ok(inpSettleService.dailySummary(guard.scopeOrgId(orgId), date));
    }
}
