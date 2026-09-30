package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.inpatient.InpOrderExecService;
import com.yb.hi.service.inpatient.InpShiftService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 住院护士站总览接口: 本病区在院患者名单 / 待办事项统计。
 * 机构隔离: 按 wardId 筛选, 病区归属机构校验在 Service 内完成(ward.org_id 对齐当前登录机构)。
 */
@RestController
@RequestMapping("/api/his/inp/nurse")
public class InpNurseController {

    private final InpShiftService shiftService;
    private final InpOrderExecService execService;

    public InpNurseController(InpShiftService shiftService, InpOrderExecService execService) {
        this.shiftService = shiftService;
        this.execService = execService;
    }

    /**
     * 本病区在院患者(visit_status=2 在院): 分页, keyword 匹配患者姓名/住院号,
     * JOIN his_patient + his_bed 取姓名/性别/年龄/床位号。
     */
    @GetMapping("/patients")
    public R<IPage<Map<String, Object>>> patients(
            @RequestParam Long wardId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(shiftService.listWardPatients(wardId, keyword, page, size));
    }

    /** 待办事项统计: {pendingAudit: 待审核医嘱数, pendingExec: 待执行医嘱数}。 */
    @GetMapping("/todo")
    public R<Map<String, Object>> todo(@RequestParam Long wardId) {
        return R.ok(execService.todoStats(wardId));
    }

    /** 当前登录护士(his_staff.id 优先, 未关联职工时回退用户ID)。 */
    static Long currentNurseId() {
        LoginUser u = UserContext.get();
        return u == null ? null : (u.getStaffId() != null ? u.getStaffId() : u.getUserId());
    }
}
