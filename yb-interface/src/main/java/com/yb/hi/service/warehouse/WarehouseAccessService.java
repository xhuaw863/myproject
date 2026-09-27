package com.yb.hi.service.warehouse;

import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.warehouse.HisWarehouseDef;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.pharmacy.HisPharmacyDefMapper;
import com.yb.hi.mapper.warehouse.HisWarehouseDefMapper;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.mapper.SysUserMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.pharmacy.PharmacyDefService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 药房/药库按科室授权的访问控制(四期):
 * - 药房/药库定义绑定"归属科室"(his_dept.id, 与科室管理一一对应);
 * - 用户可操作的库房 = 归属科室落在其授权科室范围(sys_user.dept_scope ∪ 主属 dept_id)内的药房/药库;
 * - ADMIN/ORG_ADMIN/SUPER_ADMIN 及牵头机构用户不受限, 可操作全部(仍受既有机构隔离 scopeOrgId 约束);
 * - dept_id 为空的历史库房默认放行(过渡), 待管理员补绑科室后纳入管控。
 * 授权科室范围按 userId 现查 sys_user(不入 JWT), 使管理员调整授权即时生效、不撑大令牌。
 */
@Slf4j
@Component
public class WarehouseAccessService {

    private final OrgAccessGuard guard;
    private final SysUserMapper sysUserMapper;
    private final PharmacyDefService pharmacyDefService;
    private final WarehouseDefService warehouseDefService;
    private final HisPharmacyDefMapper pharmacyDefMapper;
    private final HisWarehouseDefMapper warehouseDefMapper;

    public WarehouseAccessService(OrgAccessGuard guard, SysUserMapper sysUserMapper,
                                  PharmacyDefService pharmacyDefService, WarehouseDefService warehouseDefService,
                                  HisPharmacyDefMapper pharmacyDefMapper, HisWarehouseDefMapper warehouseDefMapper) {
        this.guard = guard;
        this.sysUserMapper = sysUserMapper;
        this.pharmacyDefService = pharmacyDefService;
        this.warehouseDefService = warehouseDefService;
        this.pharmacyDefMapper = pharmacyDefMapper;
        this.warehouseDefMapper = warehouseDefMapper;
    }

    /** 当前用户是否不受科室授权限制(管理员角色或牵头机构用户) */
    public boolean unrestricted() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return false;
        }
        String r = lu.getRole();
        if (Roles.ADMIN.equals(r) || Roles.ORG_ADMIN.equals(r) || Roles.SUPER_ADMIN.equals(r)) {
            return true;
        }
        return guard.isLead(lu);
    }

    /** 当前用户的授权科室集合: sys_user.dept_scope ∪ 主属 dept_id */
    public Set<Long> authDeptIds() {
        Set<Long> set = new HashSet<>();
        LoginUser lu = UserContext.get();
        if (lu == null || lu.getUserId() == null) {
            return set;
        }
        SysUser u = sysUserMapper.selectById(lu.getUserId());
        if (u == null) {
            return set;
        }
        if (u.getDeptId() != null) {
            set.add(u.getDeptId());
        }
        if (StringUtils.hasText(u.getDeptScope())) {
            for (String s : u.getDeptScope().split(",")) {
                String t = s.trim();
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    set.add(Long.parseLong(t));
                } catch (NumberFormatException ignore) {
                    // 非法片段跳过
                }
            }
        }
        return set;
    }

    /** 当前用户可操作的药房列表(在机构启用药房基础上按授权科室过滤; 管理员/牵头/历史未绑定不受限) */
    public List<HisPharmacyDef> accessiblePharmacies(Long orgId) {
        List<HisPharmacyDef> all = pharmacyDefService.list(orgId);
        if (unrestricted()) {
            return all;
        }
        Set<Long> depts = authDeptIds();
        List<HisPharmacyDef> out = new ArrayList<>();
        for (HisPharmacyDef d : all) {
            if (d.getDeptId() == null || depts.contains(d.getDeptId())) {
                out.add(d);
            }
        }
        return out;
    }

    /** 当前用户可操作的药库列表(在机构启用 WAREHOUSE 型药库基础上按授权科室过滤) */
    public List<HisWarehouseDef> accessibleWarehouses(Long orgId) {
        List<HisWarehouseDef> all = warehouseDefService.list(orgId);
        if (unrestricted()) {
            return all;
        }
        Set<Long> depts = authDeptIds();
        List<HisWarehouseDef> out = new ArrayList<>();
        for (HisWarehouseDef d : all) {
            if (d.getDeptId() == null || depts.contains(d.getDeptId())) {
                out.add(d);
            }
        }
        return out;
    }

    /** 药房操作越权守卫: 管理员/牵头或历史未绑定(dept_id 空)放行; 否则要求归属科室在授权范围内 */
    public void requirePharmacyAccess(Long pharmacyId) {
        if (pharmacyId == null) {
            throw new BizException(400, "药房不能为空");
        }
        if (unrestricted()) {
            return;
        }
        HisPharmacyDef def = pharmacyDefMapper.selectById(pharmacyId);
        // def 为空交由下游"药房不存在/停用"校验, 此处仅拦截越权
        assertDeptAccess(def == null ? null : def.getDeptId(), "药房");
    }

    /** 药库操作越权守卫: 管理员/牵头或历史未绑定(dept_id 空)放行; 否则要求归属科室在授权范围内 */
    public void requireWarehouseAccess(Long warehouseId) {
        if (warehouseId == null) {
            throw new BizException(400, "药库不能为空");
        }
        if (unrestricted()) {
            return;
        }
        HisWarehouseDef def = warehouseDefMapper.selectById(warehouseId);
        assertDeptAccess(def == null ? null : def.getDeptId(), "药库");
    }

    private void assertDeptAccess(Long deptId, String label) {
        if (deptId == null) {
            // 历史未绑定库房: 过渡期放行
            return;
        }
        if (!authDeptIds().contains(deptId)) {
            throw new BizException(403, "无权操作该" + label + "(需在用户管理中授权其归属科室)");
        }
    }
}
