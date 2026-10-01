package com.yb.hi.controller.doctor;

import com.yb.hi.entity.doctor.HisChronicDisease;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.doctor.HisChargeAddonRuleService;
import com.yb.hi.service.doctor.HisChronicDiseaseService;
import com.yb.hi.service.doctor.HisOrderFreqService;
import com.yb.hi.service.doctor.HisPrescribeAuthService;
import com.yb.hi.service.doctor.HisRxSplitRuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yb.hi.entity.doctor.HisChargeAddonRule;
import com.yb.hi.entity.doctor.HisRxSplitRule;

import java.util.List;
import java.util.Map;

/**
 * 门诊医生站医嘱/处方进阶接口(OP-C, 需求2.2.2.3.14.3/14.4):
 * 处方权限只读校验 / 慢特病备案 / 医嘱处方高频助手 / 拆方与自动计费规则读取。
 * 独立于既有 HisPrescriptionController/HisOrderController, 避免与其(及在途药房流程)写路径耦合。
 */
@RestController
@RequestMapping("/api/his")
public class OutpRxOrderExtController {

    private final HisPrescribeAuthService prescribeAuthService;
    private final HisChronicDiseaseService chronicDiseaseService;
    private final HisOrderFreqService orderFreqService;
    private final HisRxSplitRuleService rxSplitRuleService;
    private final HisChargeAddonRuleService chargeAddonRuleService;

    public OutpRxOrderExtController(HisPrescribeAuthService prescribeAuthService,
                                    HisChronicDiseaseService chronicDiseaseService,
                                    HisOrderFreqService orderFreqService,
                                    HisRxSplitRuleService rxSplitRuleService,
                                    HisChargeAddonRuleService chargeAddonRuleService) {
        this.prescribeAuthService = prescribeAuthService;
        this.chronicDiseaseService = chronicDiseaseService;
        this.orderFreqService = orderFreqService;
        this.rxSplitRuleService = rxSplitRuleService;
        this.chargeAddonRuleService = chargeAddonRuleService;
    }

    /* ---------- 处方权限只读校验 ---------- */

    /** 医师处方权限聚合(rx/narcotic/psych1/psych2/abx/surgery), type 可选返回 typeAllowed。 */
    @GetMapping("/staff/prescribe-auth")
    public R<Map<String, Object>> prescribeAuth(@RequestParam(required = false) Long staffId,
                                                 @RequestParam(required = false) String type) {
        LoginUser user = UserContext.get();
        if (staffId == null && user != null) {
            staffId = user.getStaffId();
        }
        return R.ok(prescribeAuthService.getAuth(staffId, type));
    }

    /* ---------- 门诊慢特病备案 ---------- */

    @PostMapping("/chronic-disease")
    public R<HisChronicDisease> saveChronic(@RequestBody HisChronicDisease row) {
        return R.ok(chronicDiseaseService.save(row));
    }

    @GetMapping("/chronic-disease/list")
    public R<List<HisChronicDisease>> listChronic(@RequestParam Long patientId) {
        return R.ok(chronicDiseaseService.listByPatient(patientId));
    }

    /* ---------- 医嘱/处方高频助手 ---------- */

    @GetMapping("/order/assistant")
    public R<Map<String, Object>> orderAssistant(@RequestParam(required = false) Long deptId,
                                                  @RequestParam(required = false) Long staffId,
                                                  @RequestParam(defaultValue = "15") int limit) {
        LoginUser user = UserContext.get();
        if (deptId == null && user != null) {
            deptId = user.getDeptId();
        }
        if (staffId == null && user != null) {
            staffId = user.getStaffId();
        }
        return R.ok(orderFreqService.assistant(deptId, staffId, limit));
    }

    /* ---------- 拆方 / 自动计费规则读取 ---------- */

    @GetMapping("/rx-split-rule/list")
    public R<List<HisRxSplitRule>> listSplitRule(@RequestParam(required = false) Long deptId) {
        return R.ok(rxSplitRuleService.listEffective(deptId));
    }

    @GetMapping("/charge-addon-rule/list")
    public R<List<HisChargeAddonRule>> listAddonRule(@RequestParam(required = false) Long deptId,
                                                      @RequestParam(required = false) Long itemId) {
        if (itemId != null) {
            return R.ok(chargeAddonRuleService.listByItem(itemId));
        }
        return R.ok(chargeAddonRuleService.listEffective(deptId));
    }
}
