package com.yb.hi.controller.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.inpatient.InpAdmitDTO;
import com.yb.hi.dto.inpatient.InpTransferDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.InpVisitService;
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
 * 住院就诊接口: 入院登记 / 预入院登记与确认入院 / 在院患者列表 / 就诊详情 / 转科转床 / 出院申请 / 取消入院。
 * 机构隔离: 读走 scopeOrgId(牵头可跨机构汇总, 非牵头锁定本机构), 写以 currentOrgId 归属。
 */
@RestController
@RequestMapping("/api/his/inp")
public class InpVisitController {

    private final InpVisitService inpVisitService;
    private final OrgAccessGuard guard;

    public InpVisitController(InpVisitService inpVisitService, OrgAccessGuard guard) {
        this.inpVisitService = inpVisitService;
        this.guard = guard;
    }

    /** 入院登记(生成住院号+占床+创建入院诊断) */
    @PostMapping("/admit")
    public R<HisInpVisit> admit(@RequestBody InpAdmitDTO dto) {
        return R.ok(inpVisitService.admit(dto, guard.currentOrgId()));
    }

    /** 预入院登记(创建待入院记录 status=1, 不分配床位, 可带预检项/拟入科室) */
    @PostMapping("/pre-admit")
    public R<HisInpVisit> preAdmit(@RequestBody InpAdmitDTO dto) {
        return R.ok(inpVisitService.preAdmit(dto, guard.currentOrgId()));
    }

    /** 预入院确认入院(1待入院 -> 2在院, 分配指定床位, 补落入院诊断) */
    @PostMapping("/pre-admit/{id}/confirm")
    public R<HisInpVisit> confirmAdmit(@PathVariable Long id, @RequestParam Long bedId) {
        return R.ok(inpVisitService.confirmAdmit(id, bedId));
    }

    /** 取消预入院(1待入院 -> 5已取消; 未占床无需释放床位) */
    @PostMapping("/pre-admit/{id}/cancel")
    public R<HisInpVisit> cancelPreAdmit(@PathVariable Long id) {
        return R.ok(inpVisitService.cancelPreAdmit(id));
    }

    /** 待入院(预入院)列表(deptId 可选过滤拟入科室; 按预入院时间倒序) */
    @GetMapping("/pre-admissions")
    public R<List<Map<String, Object>>> listPreAdmissions(
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Long orgId) {
        return R.ok(inpVisitService.listPreAdmissions(deptId, guard.scopeOrgId(orgId)));
    }

    /** 在院患者列表(分页; 支持机构/病区/科室/状态筛选与关键字检索) */
    @GetMapping("/patients")
    public R<IPage<Map<String, Object>>> patients(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long wardId,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Integer visitStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(inpVisitService.listPatients(guard.scopeOrgId(orgId), wardId, deptId,
                visitStatus, keyword, page, size));
    }

    /** 就诊详情(含患者信息、床位/病区信息、诊断列表) */
    @GetMapping("/visit/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(inpVisitService.getDetail(id));
    }

    /** 转科/转床(释放旧床->占用新床->回写就诊, 换床原子链路) */
    @PutMapping("/visit/{id}/transfer")
    public R<HisInpVisit> transfer(@PathVariable Long id, @RequestBody InpTransferDTO dto) {
        return R.ok(inpVisitService.transfer(id, dto));
    }

    /** 出院申请(visit_status 2在院 -> 3出院办理中) */
    @PutMapping("/visit/{id}/discharge-apply")
    public R<HisInpVisit> dischargeApply(@PathVariable Long id) {
        return R.ok(inpVisitService.dischargeApply(id));
    }

    /** 取消入院(visit_status -> 5已取消, 释放床位) */
    @PutMapping("/visit/{id}/cancel")
    public R<HisInpVisit> cancel(@PathVariable Long id) {
        return R.ok(inpVisitService.cancel(id));
    }
}
