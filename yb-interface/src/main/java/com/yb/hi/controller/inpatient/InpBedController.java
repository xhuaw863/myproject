package com.yb.hi.controller.inpatient;

import com.yb.hi.entity.inpatient.HisBed;
import com.yb.hi.entity.inpatient.HisWard;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.InpBedService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 住院病区/床位接口: 病区CRUD、床位CRUD、床位启停、床位一览统计。
 * 守卫: 读走 scopeOrgId; 写(新增/编辑/删除/启停)走 requireSelfOrgWrite, 本机构管理员自治。
 */
@RestController
@RequestMapping("/api/his/inp/bed")
public class InpBedController {

    private final InpBedService inpBedService;
    private final OrgAccessGuard guard;

    public InpBedController(InpBedService inpBedService, OrgAccessGuard guard) {
        this.inpBedService = inpBedService;
        this.guard = guard;
    }

    /** 病区列表(机构隔离) */
    @GetMapping("/ward/list")
    public R<List<HisWard>> wardList(@RequestParam(required = false) Long orgId) {
        return R.ok(inpBedService.wardList(guard.scopeOrgId(orgId)));
    }

    /** 新增/编辑病区(id=null 新增) */
    @PostMapping("/ward")
    public R<HisWard> saveWard(@RequestBody HisWard ward) {
        guard.requireSelfOrgWrite();
        return R.ok(inpBedService.saveWard(ward, guard.currentOrgId()));
    }

    /** 删除病区(病区下有床位时禁止) */
    @DeleteMapping("/ward/{id}")
    public R<Void> deleteWard(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        inpBedService.deleteWard(id);
        return R.ok();
    }

    /** 床位列表(wardId 筛选, 机构隔离) */
    @GetMapping("/list")
    public R<List<HisBed>> bedList(@RequestParam(required = false) Long wardId,
                                   @RequestParam(required = false) Long orgId) {
        return R.ok(inpBedService.bedList(wardId, guard.scopeOrgId(orgId)));
    }

    /** 新增/编辑床位(id=null 新增) */
    @PostMapping
    public R<HisBed> saveBed(@RequestBody HisBed bed) {
        guard.requireSelfOrgWrite();
        return R.ok(inpBedService.saveBed(bed, guard.currentOrgId()));
    }

    /** 启用/停用床位(空床0 <-> 停用2 翻转, 占用中禁止) */
    @PutMapping("/{id}/status")
    public R<HisBed> toggleStatus(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        return R.ok(inpBedService.toggleBedStatus(id));
    }

    /** 床位一览(按病区统计 空床/占用/停用 数量) */
    @GetMapping("/overview")
    public R<List<Map<String, Object>>> overview(@RequestParam(required = false) Long orgId) {
        return R.ok(inpBedService.bedOverview(guard.scopeOrgId(orgId)));
    }
}
