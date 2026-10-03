package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.NursingPipeDTO;
import com.yb.hi.entity.inpatient.HisNursingPipe;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingPipeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 护理管道接口(护士站): 置管登记 / 巡视评估 / 更换 / 拔管(意外脱出) / 在管清单 / 到期拔管预警。
 * 生命周期状态机与归属机构校验均在 Service 内完成(status: 1在管 2已拔 3意外脱出)。
 */
@RestController
@RequestMapping("/api/his/inp/nursing/pipe")
public class NursingPipeController {

    private final NursingPipeService pipeService;

    public NursingPipeController(NursingPipeService pipeService) {
        this.pipeService = pipeService;
    }

    /** 置管登记: pipeType 限固定枚举集, 落库后 status=1 在管。 */
    @PostMapping({"", "/"})
    public R<HisNursingPipe> insert(@RequestBody NursingPipeDTO dto) {
        return R.ok(pipeService.insert(dto));
    }

    /** 巡视评估: 回填风险等级(1低 2中 3高)与最后评估时间。 */
    @PutMapping("/{id}/assess")
    public R<HisNursingPipe> assess(@PathVariable Long id,
                                    @RequestParam(required = false) Integer riskLevel,
                                    @RequestParam(required = false) String note) {
        return R.ok(pipeService.assess(id, riskLevel, note));
    }

    /** 更换管道: 回填最后更换时间(敷贴/接头/导管更换留痕)。 */
    @PutMapping("/{id}/replace")
    public R<HisNursingPipe> replace(@PathVariable Long id,
                                     @RequestParam(required = false) String note) {
        return R.ok(pipeService.replace(id, note));
    }

    /** 拔管: removeType=2 正常拔 / 3 意外脱出, 回填实际拔管时间。 */
    @PutMapping("/{id}/remove")
    public R<HisNursingPipe> remove(@PathVariable Long id, @RequestParam Integer removeType) {
        return R.ok(pipeService.remove(id, removeType));
    }

    /** 管道列表(按就诊): 在管置顶, 按置管时间倒序。 */
    @GetMapping("/list")
    public R<List<HisNursingPipe>> list(@RequestParam Long inpVisitId) {
        return R.ok(pipeService.listByVisit(inpVisitId));
    }

    /** 仅在管管道(status=1): 交接班/术前核查用。 */
    @GetMapping("/active")
    public R<List<HisNursingPipe>> active(@RequestParam Long inpVisitId) {
        return R.ok(pipeService.listActive(inpVisitId));
    }

    /** 到期拔管预警(按病区): 预计拔管日 <= 今日+1 的在管管道清单(含患者姓名/床位/逾期天数)。 */
    @GetMapping("/overdue-alerts")
    public R<List<Map<String, Object>>> overdueAlerts(@RequestParam Long wardId) {
        return R.ok(pipeService.overdueAlerts(wardId));
    }
}
