package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.entity.StaffDeptGrant;
import com.yb.hi.platform.entity.StaffEmployment;
import com.yb.hi.platform.service.DeptScopeResolver;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.StaffEmploymentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 员工任职与科室临调授权维护接口:
 * - 读: requireSelfRead —— 管理员/牵头不限范围, 其他账号仅可查看自己名下的任职与授权;
 * - 写: requireLeadWrite —— 与职工管理同档(牵头机构管理员统一维护基础数据);
 * - 任职保存后由服务按主任职单向回写 his_staff 归属机构/科室展示列。
 */
@RestController
@RequestMapping("/api/his/staff-employment")
public class StaffEmploymentController {

    private final StaffEmploymentService employmentService;
    private final OrgAccessGuard guard;
    private final DeptScopeResolver deptScopeResolver;

    public StaffEmploymentController(StaffEmploymentService employmentService, OrgAccessGuard guard,
                                     DeptScopeResolver deptScopeResolver) {
        this.employmentService = employmentService;
        this.guard = guard;
        this.deptScopeResolver = deptScopeResolver;
    }

    @GetMapping("/{staffId}/employments")
    public R<List<StaffEmployment>> employments(@PathVariable Long staffId) {
        requireSelfRead(staffId);
        return R.ok(employmentService.listByStaff(staffId));
    }

    @PostMapping("/{staffId}/employments")
    public R<Void> saveEmployments(@PathVariable Long staffId, @RequestBody List<StaffEmployment> rows) {
        guard.requireLeadWrite();
        employmentService.setEmployments(staffId, rows);
        return R.ok();
    }

    @GetMapping("/{staffId}/grants")
    public R<List<StaffDeptGrant>> grants(@PathVariable Long staffId) {
        requireSelfRead(staffId);
        return R.ok(employmentService.listGrants(staffId));
    }

    @PostMapping("/{staffId}/grants")
    public R<Void> addGrant(@PathVariable Long staffId, @RequestBody StaffDeptGrant g) {
        guard.requireLeadWrite();
        g.setStaffId(staffId);
        employmentService.addGrant(g);
        return R.ok();
    }

    @DeleteMapping("/grants/{id}")
    public R<Void> removeGrant(@PathVariable Long id) {
        guard.requireLeadWrite();
        employmentService.removeGrant(id);
        return R.ok();
    }

    /** 读档位: 不受科室限制的管理身份(管理员/牵头)可查任意职工; 其余仅可查本人绑定职工 */
    private void requireSelfRead(Long staffId) {
        if (staffId == null) {
            throw new BizException(400, "职工不能为空");
        }
        if (deptScopeResolver.isUnrestricted()) {
            return;
        }
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (!staffId.equals(lu.getStaffId())) {
            throw new BizException(403, "仅可查看本人名下的任职与授权");
        }
    }
}
