package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.SurgeryFeeDTO;
import com.yb.hi.entity.inpatient.HisSurgeryFee;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.SurgeryFeeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 手术费用接口: 明细列表 / 记账(双写住院费用明细) / 批量记账 / 退费 / 按类别汇总 / 自动计时计费。
 * 机构隔离: 经手术归属校验(Service 内), 写以 currentOrgId 归属。
 */
@RestController
@RequestMapping("/api/his/surgery-fee")
public class SurgeryFeeController {

    private final SurgeryFeeService surgeryFeeService;
    private final OrgAccessGuard guard;

    public SurgeryFeeController(SurgeryFeeService surgeryFeeService, OrgAccessGuard guard) {
        this.surgeryFeeService = surgeryFeeService;
        this.guard = guard;
    }

    /** 手术费用明细列表 */
    @GetMapping("/list/{surgeryId}")
    public R<List<HisSurgeryFee>> list(@PathVariable Long surgeryId) {
        return R.ok(surgeryFeeService.listFees(surgeryId));
    }

    /** 添加费用项(his_surgery_fee + his_inp_charge_detail 双写, 回写 surgery_id) */
    @PostMapping
    public R<HisSurgeryFee> add(@RequestBody SurgeryFeeDTO dto) {
        return R.ok(surgeryFeeService.addFee(dto, guard.currentOrgId()));
    }

    /** 批量添加费用(整批一个事务) */
    @PostMapping("/batch")
    public R<List<HisSurgeryFee>> batch(@RequestBody List<SurgeryFeeDTO> dtos) {
        return R.ok(surgeryFeeService.batchAddFee(dtos, guard.currentOrgId()));
    }

    /** 删除费用项(逻辑删除 + 费用明细退费 + 回减就诊总费用) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        surgeryFeeService.deleteFee(id);
        return R.ok();
    }

    /** 费用汇总(按 fee_category 分组 SUM) */
    @GetMapping("/summary/{surgeryId}")
    public R<Map<String, Object>> summary(@PathVariable Long surgeryId) {
        return R.ok(surgeryFeeService.summary(surgeryId));
    }

    /** 自动计费(RequestBody: {surgeryId}; 麻醉费按30分钟/单位, 不足30分按30分计) */
    @PostMapping("/auto-calc")
    public R<Map<String, Object>> autoCalc(@RequestBody Map<String, Object> body) {
        Object sid = body == null ? null : body.get("surgeryId");
        if (sid == null) {
            throw new BizException(400, "手术ID不能为空");
        }
        Long surgeryId = sid instanceof Number
                ? ((Number) sid).longValue()
                : Long.valueOf(String.valueOf(sid).trim());
        return R.ok(surgeryFeeService.autoCalcTimeFee(surgeryId, guard.currentOrgId()));
    }
}
