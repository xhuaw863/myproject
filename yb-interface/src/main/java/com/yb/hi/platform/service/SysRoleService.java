package com.yb.hi.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.platform.dto.MenuNode;
import com.yb.hi.platform.dto.RoleSaveReq;
import com.yb.hi.platform.entity.SysRole;
import com.yb.hi.platform.entity.SysRoleMenu;
import com.yb.hi.platform.mapper.SysRoleMapper;
import com.yb.hi.platform.mapper.SysRoleMenuMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色服务: 全局预置角色(tenant_id=NULL, 只读) + 租户自定义角色(可增改删/授权)。
 * sys_role/sys_role_menu 已入 IGNORE_TABLES, 由本服务显式按 tenant_id 过滤。
 */
@Service
public class SysRoleService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuService menuService;
    private final OrgAccessGuard orgAccessGuard;

    /** 医院端不可见菜单: 平台级"医院管理"(顶级) + 标准字典"提取入库"/"字典维护" + "行政区划"(基础字典, 仅超管可见) */
    private static final List<String> HOSPITAL_EXCLUDED_MENUS = Arrays.asList("hospital-manage", "std-dict-import", "std-dict-maintain", "area-code");
    /** 超级管理员精简菜单: 工作台 + 医院管理 + 标准字典(浏览+维护) */
    private static final List<String> SUPER_ADMIN_MENUS = Arrays.asList("dashboard", "hospital-manage", "std-dict");
    /** 机构系统管理员(ORG_ADMIN)额外排除: "系统管理"目录(机构管理/角色权限/菜单管理) */
    private static final List<String> SYSTEM_MENUS = Arrays.asList("system", "org-manage", "role-manage", "menu-manage");

    public SysRoleService(SysRoleMapper roleMapper, SysRoleMenuMapper roleMenuMapper, SysMenuService menuService,
                          OrgAccessGuard orgAccessGuard) {
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.menuService = menuService;
        this.orgAccessGuard = orgAccessGuard;
    }

    private Long currentTenant() {
        return TenantContext.get();
    }

    /** 可见角色: 全局预置(tenant_id IS NULL) + 本租户自定义 */
    public List<SysRole> listVisible() {
        Long tid = currentTenant();
        QueryWrapper<SysRole> q = new QueryWrapper<SysRole>()
                .and(w -> {
                    w.isNull("tenant_id");
                    if (tid != null) {
                        w.or().eq("tenant_id", tid);
                    }
                })
                .orderByAsc("role_type", "id");
        return roleMapper.selectList(q);
    }

    public SysRole getById(Long id) {
        return roleMapper.selectById(id);
    }

    /** 导出角色列表(xlsx 行集): 与页面查询同口径(编码/名称/备注关键字 + 类型 1全局预置/2租户自定义 + 状态) */
    public Map<String, Object> exportRows(String keyword, Integer roleType, Integer status) {
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"角色编码", "角色名称", "类型", "菜单范围", "备注", "状态"}) {
            head.add(Collections.singletonList(h));
        }
        String kw = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;
        List<List<Object>> rows = new ArrayList<>();
        for (SysRole r : listVisible()) {
            boolean global = r.getTenantId() == null || (r.getRoleType() != null && r.getRoleType() == 1);
            if (kw != null && !nz(r.getRoleCode()).toLowerCase().contains(kw)
                    && !nz(r.getRoleName()).toLowerCase().contains(kw)
                    && !nz(r.getRemark()).toLowerCase().contains(kw)) {
                continue;
            }
            if (roleType != null && (roleType == 1) != global) {
                continue;
            }
            if (status != null && !status.equals(r.getStatus())) {
                continue;
            }
            rows.add(Arrays.asList(nz(r.getRoleCode()), nz(r.getRoleName()),
                    global ? "全局预置" : "租户自定义",
                    r.getAllMenus() != null && r.getAllMenus() == 1 ? "全部菜单" : "按授权",
                    nz(r.getRemark()),
                    r.getStatus() != null && r.getStatus() == 1 ? "启用" : "停用"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 按角色编码在可见范围内查找(优先本租户, 再全局) */
    public SysRole findByCode(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return null;
        }
        Long tid = currentTenant();
        List<SysRole> list = roleMapper.selectList(new QueryWrapper<SysRole>()
                .eq("role_code", roleCode)
                .and(w -> {
                    w.isNull("tenant_id");
                    if (tid != null) {
                        w.or().eq("tenant_id", tid);
                    }
                })
                .orderByDesc("tenant_id"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 新增租户自定义角色 */
    public Long create(RoleSaveReq req) {
        Long tid = currentTenant();
        if (tid == null) {
            throw new BizException(400, "无法确定当前租户");
        }
        if (!StringUtils.hasText(req.getRoleCode()) || !StringUtils.hasText(req.getRoleName())) {
            throw new BizException(400, "角色编码与名称不能为空");
        }
        long dup = roleMapper.selectCount(new QueryWrapper<SysRole>()
                .eq("tenant_id", tid).eq("role_code", req.getRoleCode()));
        if (dup > 0) {
            throw new BizException("角色编码已存在: " + req.getRoleCode());
        }
        SysRole r = new SysRole();
        r.setTenantId(tid);
        r.setRoleCode(req.getRoleCode().trim());
        r.setRoleName(req.getRoleName().trim());
        r.setRoleType(2);
        r.setAllMenus(0);
        r.setRemark(req.getRemark());
        r.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        roleMapper.insert(r);
        return r.getId();
    }

    /** 修改角色(仅本租户自定义角色) */
    public void update(RoleSaveReq req) {
        if (req.getId() == null) {
            throw new BizException(400, "角色ID不能为空");
        }
        SysRole r = requireTenantRole(req.getId());
        if (StringUtils.hasText(req.getRoleName())) {
            r.setRoleName(req.getRoleName().trim());
        }
        if (StringUtils.hasText(req.getRoleCode())) {
            r.setRoleCode(req.getRoleCode().trim());
        }
        r.setRemark(req.getRemark());
        if (req.getStatus() != null) {
            r.setStatus(req.getStatus());
        }
        roleMapper.updateById(r);
    }

    /** 删除角色(仅本租户自定义角色), 同时清理授权 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireTenantRole(id);
        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("role_id", id));
        roleMapper.deleteById(id);
    }

    private SysRole requireTenantRole(Long id) {
        SysRole r = roleMapper.selectById(id);
        if (r == null) {
            throw new BizException("角色不存在");
        }
        if (r.getTenantId() == null) {
            throw new BizException(403, "全局预置角色不可修改");
        }
        Long tid = currentTenant();
        if (tid != null && !tid.equals(r.getTenantId())) {
            throw new BizException(403, "无权操作其他租户的角色");
        }
        return r;
    }

    /** 角色已授权菜单ID集合 */
    public List<Long> getMenuIds(Long roleId) {
        List<SysRoleMenu> list = roleMenuMapper.selectList(
                new QueryWrapper<SysRoleMenu>().eq("role_id", roleId));
        List<Long> ids = new ArrayList<>();
        for (SysRoleMenu rm : list) {
            ids.add(rm.getMenuId());
        }
        return ids;
    }

    /** 为角色分配菜单(仅本租户自定义角色): 物理删除重建 */
    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        SysRole r = requireTenantRole(roleId);
        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("role_id", roleId));
        if (menuIds == null || menuIds.isEmpty()) {
            return;
        }
        for (Long mid : menuIds) {
            if (mid == null) {
                continue;
            }
            roleMenuMapper.insert(new SysRoleMenu(roleId, mid, r.getTenantId()));
        }
    }

    /**
     * 解析当前登录用户的菜单树:
     * - SUPER_ADMIN(平台超管): 工作台 + 医院管理(跨租户开通) + 标准字典(浏览+维护), 不下发医院业务菜单;
     * - ADMIN / all_menus=1 角色(医院端): 全量菜单, 但排除平台级"医院管理"与标准字典"提取入库"(维护);
     * - 其余角色: 按 sys_role_menu 授权过滤。
     */
    public List<MenuNode> resolveMenuTree(LoginUser lu) {
        if (lu == null) {
            return new ArrayList<>();
        }
        String code = lu.getRole();
        if (Roles.SUPER_ADMIN.equals(code)) {
            return menuService.treeOnlyTopKeys(SUPER_ADMIN_MENUS);
        }
        if (Roles.ADMIN.equals(code)) {
            return menuService.treeExcludingKeys(adminExcludedMenus(lu));
        }
        if (Roles.ORG_ADMIN.equals(code)) {
            return menuService.treeExcludingKeys(orgAdminExcludedMenus(lu));
        }
        SysRole role = null;
        if (lu.getRoleId() != null) {
            role = roleMapper.selectById(lu.getRoleId());
        }
        if (role == null) {
            role = findByCode(code);
        }
        if (role == null) {
            return new ArrayList<>();
        }
        if (role.getAllMenus() != null && role.getAllMenus() == 1) {
            if (Roles.ORG_ADMIN.equals(role.getRoleCode())) {
                return menuService.treeExcludingKeys(orgAdminExcludedMenus(lu));
            }
            return menuService.treeExcludingKeys(adminExcludedMenus(lu));
        }
        return menuService.treeByIds(getMenuIds(role.getId()));
    }

    /**
     * 医院端全菜单角色的排除集: 基础排除项 + 非牵头机构追加排除"医共体字典"(community-dict)。
     * 医共体字典仅牵头机构(租户根机构 org_level=1)可见/可维护, 乡镇院长(ADMIN)不下发该菜单。
     */
    private List<String> adminExcludedMenus(LoginUser lu) {
        List<String> ex = new ArrayList<>(HOSPITAL_EXCLUDED_MENUS);
        if (!isLeadOrg(lu)) {
            ex.add("community-dict");
        }
        return ex;
    }

    /** 当前登录用户是否归属牵头机构(org_level=1) */
    private boolean isLeadOrg(LoginUser lu) {
        return orgAccessGuard.isLead(lu);
    }

    /**
     * 机构系统管理员(ORG_ADMIN, 非牵头医疗机构)排除集: 在 ADMIN 排除基础上追加"系统管理"目录
     * 及其三子菜单(机构管理/角色权限/菜单管理), 即非牵头机构管理员不掌握全局机构/角色/菜单维护权。
     */
    private List<String> orgAdminExcludedMenus(LoginUser lu) {
        List<String> ex = new ArrayList<>(adminExcludedMenus(lu));
        ex.addAll(SYSTEM_MENUS);
        return ex;
    }
}
