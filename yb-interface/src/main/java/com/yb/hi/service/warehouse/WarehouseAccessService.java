package com.yb.hi.service.warehouse;

import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.warehouse.HisWarehouseDef;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.pharmacy.HisPharmacyDefMapper;
import com.yb.hi.mapper.warehouse.HisWarehouseDefMapper;
import com.yb.hi.platform.service.DeptScopeResolver;
import com.yb.hi.service.pharmacy.PharmacyDefService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 药房/药库按科室授权的访问控制(四期):
 * - 药房/药库定义绑定"归属科室"(his_dept.id, 与科室管理一一对应);
 * - 用户可操作的库房 = 归属科室落在其授权科室范围内的药房/药库;
 * - 授权科室口径统一收口 DeptScopeResolver(任职 ∪ 临调授权 ∪ 存量兜底, 按会话机构过滤);
 * - 管理角色及牵头机构用户不受限, 可操作全部(仍受既有机构隔离 scopeOrgId 约束);
 * - dept_id 为空的历史库房默认放行(过渡), 待管理员补绑科室后纳入管控。
 */
@Slf4j
@Component
public class WarehouseAccessService {

    private final DeptScopeResolver deptScopeResolver;
    private final PharmacyDefService pharmacyDefService;
    private final WarehouseDefService warehouseDefService;
    private final HisPharmacyDefMapper pharmacyDefMapper;
    private final HisWarehouseDefMapper warehouseDefMapper;
    private final HisDeptMapper deptMapper;

    public WarehouseAccessService(DeptScopeResolver deptScopeResolver,
                                  PharmacyDefService pharmacyDefService, WarehouseDefService warehouseDefService,
                                  HisPharmacyDefMapper pharmacyDefMapper, HisWarehouseDefMapper warehouseDefMapper,
                                  HisDeptMapper deptMapper) {
        this.deptScopeResolver = deptScopeResolver;
        this.pharmacyDefService = pharmacyDefService;
        this.warehouseDefService = warehouseDefService;
        this.pharmacyDefMapper = pharmacyDefMapper;
        this.warehouseDefMapper = warehouseDefMapper;
        this.deptMapper = deptMapper;
    }

    /** 当前用户是否不受科室授权限制(管理员角色或牵头机构用户) */
    public boolean unrestricted() {
        return deptScopeResolver.isUnrestricted();
    }

    /** 当前用户的授权科室集合(统一委托 DeptScopeResolver) */
    public Set<Long> authDeptIds() {
        return deptScopeResolver.currentAuthDeptIds();
    }

    /** 当前用户可操作的药房列表(在机构启用药房基础上按授权科室过滤; 管理员/牵头/历史未绑定不受限) */
    public List<HisPharmacyDef> accessiblePharmacies(Long orgId) {
        List<HisPharmacyDef> all = pharmacyDefService.list(orgId);
        List<HisPharmacyDef> out;
        if (unrestricted()) {
            out = all;
        } else {
            Set<Long> depts = authDeptIds();
            out = new ArrayList<>();
            for (HisPharmacyDef d : all) {
                if (d.getDeptId() == null || depts.contains(d.getDeptId())) {
                    out.add(d);
                }
            }
        }
        fillPharmacyDeptName(out);
        return out;
    }

    /** 当前用户可操作的药库列表(在机构启用 WAREHOUSE 型药库基础上按授权科室过滤) */
    public List<HisWarehouseDef> accessibleWarehouses(Long orgId) {
        List<HisWarehouseDef> all = warehouseDefService.list(orgId);
        List<HisWarehouseDef> out;
        if (unrestricted()) {
            out = all;
        } else {
            Set<Long> depts = authDeptIds();
            out = new ArrayList<>();
            for (HisWarehouseDef d : all) {
                if (d.getDeptId() == null || depts.contains(d.getDeptId())) {
                    out.add(d);
                }
            }
        }
        fillWarehouseDeptName(out);
        return out;
    }

    /** 批量回填药房归属科室名(单次 IN 查询, 避免逐行 N+1) */
    private void fillPharmacyDeptName(List<HisPharmacyDef> list) {
        Set<Long> ids = new HashSet<>();
        for (HisPharmacyDef d : list) {
            if (d.getDeptId() != null) {
                ids.add(d.getDeptId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = deptNameMap(ids);
        for (HisPharmacyDef d : list) {
            if (d.getDeptId() != null) {
                d.setDeptName(nameMap.get(d.getDeptId()));
            }
        }
    }

    /** 批量回填药库归属科室名(单次 IN 查询, 避免逐行 N+1) */
    private void fillWarehouseDeptName(List<HisWarehouseDef> list) {
        Set<Long> ids = new HashSet<>();
        for (HisWarehouseDef d : list) {
            if (d.getDeptId() != null) {
                ids.add(d.getDeptId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = deptNameMap(ids);
        for (HisWarehouseDef d : list) {
            if (d.getDeptId() != null) {
                d.setDeptName(nameMap.get(d.getDeptId()));
            }
        }
    }

    /** deptId -> 科室名称(仅命中传入集合, 租户插件自动过滤 tenant_id) */
    private Map<Long, String> deptNameMap(Set<Long> ids) {
        Map<Long, String> m = new HashMap<>();
        for (HisDept d : deptMapper.selectBatchIds(ids)) {
            m.put(d.getId(), d.getDeptName());
        }
        return m;
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
            throw new BizException(403, "无权操作该" + label + "(需在职工管理中维护本人任职/临调科室授权)");
        }
    }
}
