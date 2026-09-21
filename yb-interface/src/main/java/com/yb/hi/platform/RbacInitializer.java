package com.yb.hi.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.platform.entity.SysMenu;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.entity.SysRole;
import com.yb.hi.platform.entity.SysRoleMenu;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.mapper.SysMenuMapper;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.platform.mapper.SysRoleMapper;
import com.yb.hi.platform.mapper.SysRoleMenuMapper;
import com.yb.hi.platform.mapper.SysUserMapper;
import com.yb.hi.platform.service.SysTenantService;
import com.yb.hi.platform.service.SysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RBAC 幂等初始化与迁移(启动执行, 在演示租户/基础数据初始化之后 @Order(3)):
 * 1) sys_menu 种子(镜像前端 MENU + 新增"系统管理"目录: 机构/角色/菜单维护);
 * 2) sys_role 全局预置角色(tenant_id=NULL, 含 ADMIN/SUPER_ADMIN all_menus=1);
 * 3) sys_role_menu 非管理员全局角色的最小菜单子集;
 * 4) 迁移: 为每个租户建默认县级机构; 回填 sys_user.role_id/org_id; 回填 his_patient/his_staff.org_id。
 * 表空才种子, 回填用 isNull 条件天然幂等; 任何异常仅告警不阻断启动。
 */
@Slf4j
@Order(3)
@Component
public class RbacInitializer implements ApplicationRunner {

    private final SysTenantService tenantService;
    private final SysUserService userService;
    private final SysMenuMapper menuMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysOrgMapper orgMapper;
    private final SysUserMapper userMapper;
    private final HisPatientMapper patientMapper;
    private final HisStaffMapper staffMapper;
    private final HisDeptMapper deptMapper;
    private final DataSource dataSource;

    @Value("${his.rbac-init.enabled:true}")
    private boolean enabled;

    public RbacInitializer(SysTenantService tenantService, SysUserService userService, SysMenuMapper menuMapper,
                           SysRoleMapper roleMapper, SysRoleMenuMapper roleMenuMapper, SysOrgMapper orgMapper,
                           SysUserMapper userMapper, HisPatientMapper patientMapper, HisStaffMapper staffMapper,
                           HisDeptMapper deptMapper, DataSource dataSource) {
        this.tenantService = tenantService;
        this.userService = userService;
        this.menuMapper = menuMapper;
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.orgMapper = orgMapper;
        this.userMapper = userMapper;
        this.patientMapper = patientMapper;
        this.staffMapper = staffMapper;
        this.deptMapper = deptMapper;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Long> menuIds = seedMenus();
            ensureHospitalManageMenu();
            ensureStdDictMaintainMenu();
            moveAreaCodeMenuToStdDict();
            ensureCommunityDictMenus();
            migrateDictMapToCatalogMap();
            Map<String, Long> roleIds = seedGlobalRoles();
            seedRoleMenus(menuIds, roleIds);
            seedPlatformAdmin(roleIds);
            migrateTenants(roleIds);
        } catch (Exception e) {
            log.warn("RBAC 初始化跳过(可能表未建): {}", e.getMessage());
        }
    }

    /* ===================== 1. 菜单种子 ===================== */

    /** 种子菜单, 返回 menu_key -> id 映射(表非空时从现有数据加载) */
    private Map<String, Long> seedMenus() {
        Map<String, Long> ids = new HashMap<>();
        List<SysMenu> existing = menuMapper.selectList(null);
        if (!existing.isEmpty()) {
            for (SysMenu m : existing) {
                ids.put(m.getMenuKey(), m.getId());
            }
            return ids;
        }
        int[] sort = {0};
        // 顶级菜单: 工作台
        ids.put("dashboard", menu("dashboard", "工作台", 2, "Dashboard", null, 0L, ++sort[0]));
        // 平台管理
        long g1 = dir("platform", "平台管理", 0L, ++sort[0]);
        ids.put("tenant-info", menuK("tenant-info", "医院信息", "TenantInfo", null, g1, ++sort[0]));
        ids.put("user-manage", menuK("user-manage", "用户管理", "UserManage", null, g1, ++sort[0]));
        // 基础数据
        long g2 = dir("basedata", "基础数据", 0L, ++sort[0]);
        ids.put("dept", menuK("dept", "科室管理", "DeptManage", null, g2, ++sort[0]));
        ids.put("staff", menuK("staff", "职工管理", "StaffManage", null, g2, ++sort[0]));
        ids.put("schedule", menuK("schedule", "排班号源", "ScheduleManage", null, g2, ++sort[0]));
        ids.put("charge-item", menuK("charge-item", "收费项目对照", "ChargeItemManage", null, g2, ++sort[0]));
        // 医保字典
        long g3 = dir("yb-dict", "医保字典", 0L, ++sort[0]);
        ids.put("dict-download", menuK("dict-download", "字典下载", "DictDownload", null, g3, ++sort[0]));
        ids.put("dict-version", menuK("dict-version", "版本状态", "DictVersion", null, g3, ++sort[0]));
        ids.put("catalog-map", menuK("catalog-map", "三目录医保对照", "CatalogMap", null, g3, ++sort[0]));
        // 标准字典
        long g4 = dir("std-dict", "标准字典", 0L, ++sort[0]);
        ids.put("std-dict-browse", menuK("std-dict-browse", "字典浏览", "StdDictBrowse", null, g4, ++sort[0]));
        ids.put("std-dict-import", menuK("std-dict-import", "提取入库", "StdDictImport", null, g4, ++sort[0]));
        // 行政区划为基础字典(全局共享 area_code_2021), 归标准字典目录, 仅平台超管可见
        ids.put("area-code", menuK("area-code", "行政区划", "AreaManage", null, g4, ++sort[0]));
        // 门诊挂号台
        long g5 = dir("outpatient", "门诊挂号台", 0L, ++sort[0]);
        ids.put("patient", menuK("patient", "患者建档/查询", "PatientManage", null, g5, ++sort[0]));
        ids.put("register", menuK("register", "门诊挂号", "RegistrationDesk", null, g5, ++sort[0]));
        ids.put("unregister", menuK("unregister", "退号", "UnregisterDesk", null, g5, ++sort[0]));
        // 医生站
        long g6 = dir("doctor", "医生站", 0L, ++sort[0]);
        ids.put("doctor-queue", menuK("doctor-queue", "候诊列表", "DoctorQueue", null, g6, ++sort[0]));
        ids.put("doctor-work", menuK("doctor-work", "接诊工作台", "DoctorWork", null, g6, ++sort[0]));
        // 药房(建设中)
        long g7 = dir("pharmacy", "药房", 0L, ++sort[0]);
        ids.put("dispense-todo", menuK("dispense-todo", "待发药", null, "P1d", g7, ++sort[0]));
        ids.put("dispense", menuK("dispense", "调剂发药", null, "P1d", g7, ++sort[0]));
        ids.put("drug-return", menuK("drug-return", "退药", null, "P1d", g7, ++sort[0]));
        // 药库(建设中)
        long g8 = dir("warehouse", "药库", 0L, ++sort[0]);
        ids.put("wh-drug", menuK("wh-drug", "药品目录", null, "P1d", g8, ++sort[0]));
        ids.put("wh-in", menuK("wh-in", "采购入库", null, "P1d", g8, ++sort[0]));
        ids.put("wh-stock", menuK("wh-stock", "库存/流水", null, "P1d", g8, ++sort[0]));
        ids.put("wh-check", menuK("wh-check", "盘点", null, "P1d", g8, ++sort[0]));
        // 收费结算台(建设中)
        long g9 = dir("charge", "收费结算台", 0L, ++sort[0]);
        ids.put("charge-todo", menuK("charge-todo", "待收费", null, "P1e", g9, ++sort[0]));
        ids.put("charge-setl", menuK("charge-setl", "医保结算", null, "P1e", g9, ++sort[0]));
        ids.put("charge-refund", menuK("charge-refund", "退费", null, "P1e", g9, ++sort[0]));
        // 查询报表(建设中)
        long g10 = dir("report", "查询报表", 0L, ++sort[0]);
        ids.put("rpt-setl", menuK("rpt-setl", "结算记录", null, "P1f", g10, ++sort[0]));
        ids.put("rpt-daily", menuK("rpt-daily", "门诊日结", null, "P1f", g10, ++sort[0]));
        // 系统管理(新增): 机构/角色/菜单维护
        long g11 = dir("system", "系统管理", 0L, ++sort[0]);
        ids.put("org-manage", menuK("org-manage", "机构管理", "OrgManage", null, g11, ++sort[0]));
        ids.put("role-manage", menuK("role-manage", "角色权限", "RoleManage", null, g11, ++sort[0]));
        ids.put("menu-manage", menuK("menu-manage", "菜单管理", "MenuManage", null, g11, ++sort[0]));
        log.info("RBAC 菜单种子完成, 共 {} 项", ids.size());
        return ids;
    }

    /** 插入目录(menu_type=1, 无 comp), 返回自增 id */
    private long dir(String key, String name, long parentId, int sortNo) {
        SysMenu m = new SysMenu();
        m.setParentId(parentId);
        m.setMenuKey(key);
        m.setMenuName(name);
        m.setMenuType(1);
        m.setSortNo(sortNo);
        m.setVisible(1);
        m.setStatus(1);
        menuMapper.insert(m);
        return m.getId();
    }

    /** 插入菜单节点, 返回自增 id */
    private long menuK(String key, String name, String comp, String phase, long parentId, int sortNo) {
        return menu(key, name, 2, comp, phase, parentId, sortNo);
    }

    private long menu(String key, String name, int type, String comp, String phase, long parentId, int sortNo) {
        SysMenu m = new SysMenu();
        m.setParentId(parentId);
        m.setMenuKey(key);
        m.setMenuName(name);
        m.setMenuType(type);
        m.setComp(comp);
        m.setPhase(phase);
        m.setSortNo(sortNo);
        m.setVisible(1);
        m.setStatus(1);
        menuMapper.insert(m);
        return m.getId();
    }

    /* ===================== 2. 全局角色种子 ===================== */

    /** 种子全局预置角色(tenant_id=NULL), 返回 role_code -> id(表已有全局角色时从现有加载) */
    private Map<String, Long> seedGlobalRoles() {
        Map<String, Long> ids = new HashMap<>();
        List<SysRole> globals = roleMapper.selectList(new QueryWrapper<SysRole>().isNull("tenant_id"));
        if (!globals.isEmpty()) {
            for (SysRole r : globals) {
                ids.put(r.getRoleCode(), r.getId());
            }
            return ids;
        }
        ids.put(Roles.ADMIN, role(Roles.ADMIN, "系统管理员", 1, "医共体牵头机构管理员, 拥有全部菜单"));
        ids.put(Roles.REGISTRAR, role(Roles.REGISTRAR, "挂号员", 0, "门诊挂号/退号"));
        ids.put(Roles.DOCTOR, role(Roles.DOCTOR, "医生", 0, "接诊/处方"));
        ids.put(Roles.PHARMACIST, role(Roles.PHARMACIST, "药师", 0, "调剂发药"));
        ids.put(Roles.CASHIER, role(Roles.CASHIER, "收费员", 0, "收费结算"));
        ids.put(Roles.NURSE, role(Roles.NURSE, "护士", 0, "护理执行"));
        ids.put(Roles.SUPER_ADMIN, role(Roles.SUPER_ADMIN, "超级管理员", 1, "平台级超级管理员, 拥有全部菜单"));
        log.info("RBAC 全局角色种子完成, 共 {} 个", ids.size());
        return ids;
    }

    private long role(String code, String name, int allMenus, String remark) {
        SysRole r = new SysRole();
        r.setTenantId(null);
        r.setRoleCode(code);
        r.setRoleName(name);
        r.setRoleType(1);
        r.setAllMenus(allMenus);
        r.setRemark(remark);
        r.setStatus(1);
        roleMapper.insert(r);
        return r.getId();
    }

    /* ===================== 3. 角色-菜单种子 ===================== */

    private void seedRoleMenus(Map<String, Long> menuIds, Map<String, Long> roleIds) {
        if (roleMenuMapper.selectCount(null) > 0) {
            return;
        }
        // ADMIN/SUPER_ADMIN 走 all_menus 免配置; 其余角色给"工作台+本职能相关菜单"最小子集
        Map<String, String[]> grants = new HashMap<>();
        grants.put(Roles.REGISTRAR, new String[]{"dashboard", "patient", "register", "unregister"});
        grants.put(Roles.DOCTOR, new String[]{"dashboard", "doctor-queue", "doctor-work", "patient"});
        grants.put(Roles.PHARMACIST, new String[]{"dashboard", "dispense-todo", "dispense", "drug-return"});
        grants.put(Roles.CASHIER, new String[]{"dashboard", "charge-todo", "charge-setl", "charge-refund"});
        grants.put(Roles.NURSE, new String[]{"dashboard", "patient", "doctor-queue"});
        for (Map.Entry<String, String[]> e : grants.entrySet()) {
            Long roleId = roleIds.get(e.getKey());
            if (roleId == null) {
                continue;
            }
            for (String key : e.getValue()) {
                Long menuId = menuIds.get(key);
                if (menuId != null) {
                    roleMenuMapper.insert(new SysRoleMenu(roleId, menuId, null));
                }
            }
        }
        log.info("RBAC 角色-菜单种子完成");
    }

    /* ===================== 3b. 平台超管 + 医院管理菜单 ===================== */

    /**
     * 幂等确保"医院管理"顶级菜单存在(仅平台超级管理员可见, 见 SysRoleService.resolveMenuTree)。
     * 独立于 seedMenus(表非空即跳过), 保证既有库也能补上该菜单。
     */
    private void ensureHospitalManageMenu() {
        Long cnt = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("menu_key", "hospital-manage"));
        if (cnt != null && cnt > 0) {
            return;
        }
        SysMenu m = new SysMenu();
        m.setParentId(0L);
        m.setMenuKey("hospital-manage");
        m.setMenuName("医院管理");
        m.setMenuType(2);
        m.setComp("HospitalManage");
        m.setSortNo(1);
        m.setVisible(1);
        m.setStatus(1);
        menuMapper.insert(m);
        log.info("医院管理菜单已补充(平台超级管理员专属)");
    }

    /**
     * 幂等确保"字典维护"菜单存在(挂在"标准字典"目录下, 仅平台超级管理员可见,
     * 见 SysRoleService.resolveMenuTree: 医院端排除 std-dict-maintain)。
     * 独立于 seedMenus(表非空即跳过), 保证既有库也能补上该菜单。
     */
    private void ensureStdDictMaintainMenu() {
        Long cnt = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("menu_key", "std-dict-maintain"));
        if (cnt != null && cnt > 0) {
            return;
        }
        // 找到"标准字典"目录作为父节点; 找不到则退化为顶级
        long parentId = 0L;
        int sortNo = 99;
        SysMenu stdDir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "std-dict").last("LIMIT 1"));
        if (stdDir != null) {
            parentId = stdDir.getId();
            sortNo = 3;
        }
        SysMenu m = new SysMenu();
        m.setParentId(parentId);
        m.setMenuKey("std-dict-maintain");
        m.setMenuName("字典维护");
        m.setMenuType(2);
        m.setComp("StdDictMaintain");
        m.setSortNo(sortNo);
        m.setVisible(1);
        m.setStatus(1);
        menuMapper.insert(m);
        log.info("字典维护菜单已补充(平台超级管理员专属)");
    }

    /**
     * 幂等将"行政区划"菜单移至"标准字典"目录下: 行政区划(area_code_2021)为全局共享基础字典,
     * 仅平台超级管理员可见/可维护(见 SysRoleService: 医院端排除 area-code)。
     * 兼容既有库(seedMenus 表非空即跳过时, 老库 area-code 仍挂在 basedata 下)。
     */
    private void moveAreaCodeMenuToStdDict() {
        SysMenu stdDir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "std-dict").last("LIMIT 1"));
        SysMenu area = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "area-code").last("LIMIT 1"));
        if (stdDir == null || area == null || stdDir.getId().equals(area.getParentId())) {
            return;
        }
        area.setParentId(stdDir.getId());
        menuMapper.updateById(area);
        log.info("行政区划菜单已移至标准字典目录(平台超级管理员专属)");
    }

    /**
     * 幂等确保医共体统一字典相关顶级菜单存在(既有库 seedMenus 表非空即跳过时补种):
     * - community-dict(医共体字典): 牵头机构维护三目录+调价+标准字典导入, 非牵头 ADMIN 在 SysRoleService 排除;
     * - org-catalog(机构目录选用): 各机构勾选本院开展的项目(L3)。
     */
    private void ensureCommunityDictMenus() {
        ensureTopMenu("community-dict", "医共体字典", "CommunityDict", 12);
        ensureTopMenu("org-catalog", "机构目录选用", "OrgCatalog", 13);
    }

    /**
     * 幂等迁移: 旧"目录对照"(dict-map/DictMap, 仅收费项目单条对照)升级为"三目录医保对照"(catalog-map/CatalogMap)。
     * 既有库 seedMenus 表非空即跳过, 故在此按 menu_key 原地改名+换组件, 保证老库菜单同步。
     */
    private void migrateDictMapToCatalogMap() {
        SysMenu old = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "dict-map").last("LIMIT 1"));
        if (old == null) {
            return;
        }
        old.setMenuKey("catalog-map");
        old.setMenuName("三目录医保对照");
        old.setComp("CatalogMap");
        menuMapper.updateById(old);
        log.info("目录对照菜单已迁移为三目录医保对照(catalog-map/CatalogMap)");
    }

    /** 幂等插入一个顶级菜单节点(menu_type=2, parent=0), 已存在则跳过 */
    private void ensureTopMenu(String key, String name, String comp, int sortNo) {
        Long cnt = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("menu_key", key));
        if (cnt != null && cnt > 0) {
            return;
        }
        SysMenu m = new SysMenu();
        m.setParentId(0L);
        m.setMenuKey(key);
        m.setMenuName(name);
        m.setMenuType(2);
        m.setComp(comp);
        m.setSortNo(sortNo);
        m.setVisible(1);
        m.setStatus(1);
        menuMapper.insert(m);
        log.info("医共体字典菜单已补充: {}({})", name, key);
    }

    /**
     * 幂等种子平台运营方租户(PLATFORM) + 超级管理员账号(superadmin/admin123)。
     * 超管登录 PLATFORM 租户, 专做跨租户医院开通与管理, 与任何医院数据隔离。
     * 在全局角色种子之后调用, 直接绑定 SUPER_ADMIN 角色ID。
     */
    private void seedPlatformAdmin(Map<String, Long> globalRoleIds) {
        String code = SysTenantService.PLATFORM_TENANT_CODE;
        if (tenantService.getByCode(code) != null) {
            return;
        }
        SysTenant t = new SysTenant();
        t.setTenantCode(code);
        t.setTenantName("平台运营方");
        t.setRecerSysCode("HIS");
        t.setInfver("V1.0");
        t.setOpterType("2");
        t.setMockEnabled(1);
        t.setStatus(1);
        tenantService.insert(t);
        Long superRoleId = globalRoleIds.get(Roles.SUPER_ADMIN);
        userService.createUser(t.getId(), "superadmin", "admin123", "超级管理员",
                Roles.SUPER_ADMIN, null, null, null, superRoleId, null, null);
        log.info("平台超级管理员已创建: {} / superadmin / admin123", code);
    }

    /* ===================== 4. 租户迁移与回填 ===================== */

    private void migrateTenants(Map<String, Long> globalRoleIds) {
        ensureDeptOrgColumn();
        List<SysTenant> tenants = tenantService.listAll();
        Long prev = TenantContext.get();
        for (SysTenant t : tenants) {
            // 平台运营方租户非真实医院, 不建默认机构/不回填业务归属
            if (SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                continue;
            }
            try {
                TenantContext.set(t.getId());
                Long defaultOrgId = ensureDefaultOrg(t);
                backfillUsers(globalRoleIds, defaultOrgId);
                backfillBizOrg(defaultOrgId);
            } catch (Exception e) {
                log.warn("租户[{}] RBAC 迁移跳过: {}", t.getId(), e.getMessage());
            } finally {
                if (prev != null) {
                    TenantContext.set(prev);
                } else {
                    TenantContext.clear();
                }
            }
        }
    }

    /** 确保租户有默认县级(牵头)机构, 返回其 id */
    private Long ensureDefaultOrg(SysTenant t) {
        List<SysOrg> orgs = orgMapper.selectList(new QueryWrapper<SysOrg>().orderByAsc("id"));
        if (!orgs.isEmpty()) {
            // 优先取县级机构作为默认归属
            for (SysOrg o : orgs) {
                if (o.getOrgLevel() != null && o.getOrgLevel() == 1) {
                    return o.getId();
                }
            }
            return orgs.get(0).getId();
        }
        SysOrg o = new SysOrg();
        o.setOrgCode(t.getTenantCode());
        o.setOrgName(t.getTenantName());
        o.setOrgLevel(1);
        o.setParentId(0L);
        o.setOrgType("A100");
        o.setOrgTypeName("综合医院");
        o.setOrgTypeSrc("cv_code:MEDINS_TYPE");
        o.setFixmedinsCode(t.getFixmedinsCode());
        o.setAdmvsCode(t.getMdtrtareaAdmvs());
        o.setLeader(t.getContact());
        o.setPhone(t.getPhone());
        o.setAddress(t.getAddress());
        o.setSortNo(0);
        o.setStatus(1);
        orgMapper.insert(o);
        log.info("租户[{}] 默认县级机构已创建: {}", t.getId(), o.getOrgName());
        return o.getId();
    }

    /** 回填 sys_user: role_id(按 role 字符串匹配全局角色) + org_id(默认机构) */
    private void backfillUsers(Map<String, Long> globalRoleIds, Long defaultOrgId) {
        for (Map.Entry<String, Long> e : globalRoleIds.entrySet()) {
            userMapper.update(null, new UpdateWrapper<SysUser>()
                    .set("role_id", e.getValue())
                    .eq("role", e.getKey())
                    .isNull("role_id"));
        }
        if (defaultOrgId != null) {
            userMapper.update(null, new UpdateWrapper<SysUser>()
                    .set("org_id", defaultOrgId)
                    .isNull("org_id"));
        }
    }

    /** 回填 his_patient / his_staff / his_dept 的 org_id 为默认机构 */
    private void backfillBizOrg(Long defaultOrgId) {
        if (defaultOrgId == null) {
            return;
        }
        patientMapper.update(null, new UpdateWrapper<HisPatient>()
                .set("org_id", defaultOrgId).isNull("org_id"));
        staffMapper.update(null, new UpdateWrapper<HisStaff>()
                .set("org_id", defaultOrgId).isNull("org_id"));
        deptMapper.update(null, new UpdateWrapper<HisDept>()
                .set("org_id", defaultOrgId).isNull("org_id"));
    }

    /**
     * 幂等保证 his_dept.org_id 列存在(旧库无此列)。
     * 必须在任何 MyBatis-Plus 查询/回填 his_dept 之前执行, 否则实体映射列缺失会报错。
     */
    private void ensureDeptOrgColumn() {
        String check = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'his_dept' AND column_name = 'org_id'";
        try (Connection conn = dataSource.getConnection()) {
            boolean exists;
            try (PreparedStatement ps = conn.prepareStatement(check); ResultSet rs = ps.executeQuery()) {
                exists = rs.next() && rs.getInt(1) > 0;
            }
            if (!exists) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE his_dept ADD COLUMN org_id BIGINT DEFAULT NULL "
                            + "COMMENT '归属机构ID(sys_org)' AFTER tenant_id");
                    log.info("his_dept.org_id 列已补充(机构归属科室)");
                }
            }
        } catch (Exception e) {
            log.warn("his_dept.org_id 建列跳过: {}", e.getMessage());
        }
    }
}
