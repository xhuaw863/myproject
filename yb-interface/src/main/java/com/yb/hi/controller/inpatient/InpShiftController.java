package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.inpatient.InpShiftDTO;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpShiftService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 病区交接班接口(护士站): 当前班次信息 / 发起交班 / 接班确认 / 交接班历史 / 按患者 SBAR 预填。
 * 班次划分: 8-16 白班(1) / 16-0 小夜(2) / 0-8 大夜(3);
 * 交班自动统计 total_patients/new_admit/discharged, critical_count 经 content JSON 的 criticalCount 传入;
 * SBAR 四段(情景/背景/评估/建议)随 handover 请求体落库, patient-detail 聚合单患者 SBAR 预填。
 */
@RestController
@RequestMapping("/api/his/inp/shift")
public class InpShiftController {

    private final InpShiftService shiftService;

    public InpShiftController(InpShiftService shiftService) {
        this.shiftService = shiftService;
    }

    /** 当前班次信息: 班次判定 + 在院统计(在院总数/本班次新入院/本班次出院/待接班记录)。 */
    @GetMapping("/current")
    public R<Map<String, Object>> current(@RequestParam Long wardId) {
        return R.ok(shiftService.getCurrentShift(wardId));
    }

    /** 发起交班: 自动统计在院/新入院/出院, status=1 待接班; 病区×日期×班次防重。 */
    @PostMapping("/handover")
    public R<Map<String, Object>> handover(@RequestBody InpShiftDTO dto) {
        return R.ok(shiftService.handover(dto, InpNurseController.currentNurseId()));
    }

    /** 接班确认: 记录接班护士, status 1->2(乐观更新, 并发冲突 409)。 */
    @PutMapping("/{id}/takeover")
    public R<Map<String, Object>> takeover(@PathVariable Long id) {
        return R.ok(shiftService.takeover(id, InpNurseController.currentNurseId()));
    }

    /** 交接班历史(按病区): 分页, 交班日期倒序, 附交/接班护士姓名。 */
    @GetMapping("/history")
    public R<IPage<Map<String, Object>>> history(
            @RequestParam Long wardId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(shiftService.history(wardId, page, size));
    }

    /** 单患者 SBAR 交接预填: 聚合主诉/诊断(S) + 病史摘要与近期医嘱(B) + 最近体征与量表评估(A) + 默认建议(R)。 */
    @GetMapping("/patient-detail/{visitId}")
    public R<?> getPatientHandoverDetail(@PathVariable Long visitId) {
        return R.ok(shiftService.getPatientHandoverDetail(visitId));
    }
}
