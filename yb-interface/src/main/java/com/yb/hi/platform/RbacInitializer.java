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
            ensureOrgLeadColumn();
            Map<String, Long> menuIds = seedMenus();
            ensureHospitalManageMenu();
            ensureStdDictMaintainMenu();
            moveAreaCodeMenuToStdDict();
            mergeBasedataIntoPlatform();
            moveScheduleToOutpatient();
            ensureRegStatsMenu(menuIds);
            ensureRegDetailMenu(menuIds);
            renameUnregisterMenu();
            ensureCommunityDictMenus();
            bindWarehouseMenus();
            bindPharmacyMenus();
            bindChargeMenus();
            ensureNewPharmacyMenus(menuIds);
            ensureNewWarehouseMenus(menuIds);
            ensureNewChargeMenus(menuIds);
            ensureStockChainMenus(menuIds);
            migrateDictMapToCatalogMap();
            activateReportMenus();
            mergeDoctorMenus(menuIds);
            ensureDoctorWorklogMenu(menuIds);
            Map<String, Long> roleIds = seedGlobalRoles();
            ensureOrgAdminRole();
            seedRoleMenus(menuIds, roleIds);
            ensureBizRoleGrants(menuIds, roleIds);
            seedPlatformAdmin(roleIds);
            migrateTenants(roleIds);
        } catch (Exception e) {
            log.warn("RBAC 初始化跳过(可能表未建): {}", e.getMessage());
        }
    }

    /* ===================== 1. 菜单种子 ===================== */

    /** 种子菜单, 返回 menu_key -> id 映射(表非空时从现有数据加载) */
    private Map<String, Long> seedMenus() {
        pruneRetiredMenus();
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
        // 平台管理(含基础数据: 医院信息/用户管理 + 科室/职工, 2026-09 两组合并; 收费项目对照已并入三目录医保对照下线; 排班号源 2026-09 移至门诊挂号台)
        long g1 = dir("platform", "平台管理", 0L, ++sort[0]);
        ids.put("tenant-info", menuK("tenant-info", "医院信息", "TenantInfo", null, g1, ++sort[0]));
        ids.put("user-manage", menuK("user-manage", "用户管理", "UserManage", null, g1, ++sort[0]));
        ids.put("dept", menuK("dept", "科室管理", "DeptManage", null, g1, ++sort[0]));
        ids.put("staff", menuK("staff", "职工管理", "StaffManage", null, g1, ++sort[0]));
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
        ids.put("unregister", menuK("unregister", "退号换号", "UnregisterDesk", null, g5, ++sort[0]));
        ids.put("schedule", menuK("schedule", "排班号源", "ScheduleManage", null, g5, ++sort[0]));
        ids.put("reg_stats", menuK("reg_stats", "挂号统计", "RegStatistics", null, g5, ++sort[0]));
        ids.put("reg_detail", menuK("reg_detail", "挂号明细", "RegDetailQuery", null, g5, ++sort[0]));
        // 医生站(候诊列表+接诊工作台合并为单一门诊医生站)
        long g6 = dir("doctor", "医生站", 0L, ++sort[0]);
        ids.put("doctor-ws", menuK("doctor-ws", "门诊医生站", "DoctorWorkstation", null, g6, ++sort[0]));
        ids.put("doctor-worklog", menuK("doctor-worklog", "医生工作日志", "DoctorWorklog", null, g6, ++sort[0]));
        // 药房(2026-09 P1d 交付: 待发药/调剂发药/退药前端已上线; P2 药房管理/药房统计上线)
        long g7 = dir("pharmacy", "药房", 0L, ++sort[0]);
        ids.put("dispense-todo", menuK("dispense-todo", "待发药", "DispenseTodo", null, g7, ++sort[0]));
        ids.put("dispense", menuK("dispense", "调剂发药", "DispenseRecord", null, g7, ++sort[0]));
        ids.put("drug-return", menuK("drug-return", "退药", "DrugReturn", null, g7, ++sort[0]));
        ids.put("pharmacy-def", menuK("pharmacy-def", "药房管理", "PharmacyDef", null, g7, ++sort[0]));
        ids.put("pharmacy-rpt", menuK("pharmacy-rpt", "药房统计", "PharmacyReport", null, g7, ++sort[0]));
        // 药房药库协同二期(2026-09): 请领/调拨/调价/进销存台账/医保追溯码
        ids.put("req-mgr", menuK("req-mgr", "药品请领", "RequisitionManage", null, g7, ++sort[0]));
        ids.put("trace-code", menuK("trace-code", "药品追溯码", "TraceCodeManage", null, g7, ++sort[0]));
        // 药库(采购入库/出库管理/库存流水已上线; P2 药品目录/盘点绑定组件, 药库管理/药库统计上线)
        long g8 = dir("warehouse", "药库", 0L, ++sort[0]);
        ids.put("wh-drug", menuK("wh-drug", "药品目录", "DrugCatalogView", null, g8, ++sort[0]));
        ids.put("wh-in", menuK("wh-in", "采购入库", "StockInManage", null, g8, ++sort[0]));
        ids.put("wh-out", menuK("wh-out", "出库管理", "StockOutManage", null, g8, ++sort[0]));
        ids.put("wh-stock", menuK("wh-stock", "库存/流水", "DrugStock", null, g8, ++sort[0]));
        ids.put("wh-check", menuK("wh-check", "盘点", "StockCheck", null, g8, ++sort[0]));
        ids.put("warehouse-def", menuK("warehouse-def", "药库管理", "WarehouseDef", null, g8, ++sort[0]));
        ids.put("warehouse-rpt", menuK("warehouse-rpt", "药库统计", "WarehouseReport", null, g8, ++sort[0]));
        ids.put("trf-mgr", menuK("trf-mgr", "库存调拨", "TransferManage", null, g8, ++sort[0]));
        ids.put("price-adjust", menuK("price-adjust", "药品调价", "PriceAdjust", null, g8, ++sort[0]));
        ids.put("stock-ledger", menuK("stock-ledger", "进销存台账", "DrugLedger", null, g8, ++sort[0]));
        // 收费结算台(2026-09 P1e 交付: 待收费/医保结算/退费前端已上线; P2 发票管理/收费统计上线)
        long g9 = dir("charge", "收费结算台", 0L, ++sort[0]);
        ids.put("charge-todo", menuK("charge-todo", "待收费", "ChargeTodo", null, g9, ++sort[0]));
        ids.put("charge-setl", menuK("charge-setl", "医保结算", "ChargeSetl", null, g9, ++sort[0]));
        ids.put("charge-refund", menuK("charge-refund", "退费", "ChargeRefund", null, g9, ++sort[0]));
        ids.put("invoice-mgr", menuK("invoice-mgr", "发票管理", "InvoiceManage", null, g9, ++sort[0]));
        ids.put("charge-rpt", menuK("charge-rpt", "收费统计", "ChargeReport", null, g9, ++sort[0]));
        // 查询报表(2026-09 P1f 交付: 结算记录/门诊日结前端已上线)
        long g10 = dir("report", "查询报表", 0L, ++sort[0]);
        ids.put("rpt-setl", menuK("rpt-setl", "结算记录", "SettleRecords", null, g10, ++sort[0]));
        ids.put("rpt-daily", menuK("rpt-daily", "门诊日结", "DailySettle", null, g10, ++sort[0]));
        // 系统管理(新增): 机构/角色/菜单维护
        long g11 = dir("system", "系统管理", 0L, ++sort[0]);
        ids.put("org-manage", menuK("org-manage", "机构管理", "OrgManage", null, g11, ++sort[0]));
        ids.put("role-manage", menuK("role-manage", "角色权限", "RoleManage", null, g11, ++sort[0]));
        ids.put("menu-manage", menuK("menu-manage", "菜单管理", "MenuManage", null, g11, ++sort[0]));
        log.info("RBAC 菜单种子完成, 共 {} 项", ids.size());
        return ids;
    }

    /** 已下线菜单(功能移除或合并, 不再种子): 清理其角色授权与菜单行, 幂等;
     *  charge-item=收费项目对照(与三目录医保对照重复, 2026-09 下线);
     *  doctor-queue/doctor-work=候诊列表/接诊工作台(合并为门诊医生站 doctor-ws, 2026-09 合并) */
    private static final String[] RETIRED_MENU_KEYS = {"charge-item", "doctor-queue", "doctor-work"};

    private void pruneRetiredMenus() {
        for (String key : RETIRED_MENU_KEYS) {
            SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", key));
            if (m == null) {
                continue;
            }
            roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("menu_id", m.getId()));
            menuMapper.deleteById(m.getId());
            log.info("已下线菜单清理: {} (id={})", key, m.getId());
        }
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
        ids.put(Roles.ORG_ADMIN, role(Roles.ORG_ADMIN, "机构系统管理员", 1, "非牵头医疗机构管理员, 无系统管理(机构/角色/菜单)权限"));
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

    /**
     * 幂等确保全局预置"机构系统管理员"(ORG_ADMIN)角色存在: 既有库 seedGlobalRoles 表非空即跳过,
     * 在此按 role_code 判存补种, 保证老库也能下发该角色(all_menus=1, 菜单排除见 SysRoleService)。
     */
    private void ensureOrgAdminRole() {
        Long cnt = roleMapper.selectCount(new QueryWrapper<SysRole>()
                .isNull("tenant_id").eq("role_code", Roles.ORG_ADMIN));
        if (cnt != null && cnt > 0) {
            return;
        }
        role(Roles.ORG_ADMIN, "机构系统管理员", 1, "非牵头医疗机构管理员, 无系统管理(机构/角色/菜单)权限");
        log.info("机构系统管理员角色已补充(ORG_ADMIN, 全局预置)");
    }

    /* ===================== 3. 角色-菜单种子 ===================== */

    private void seedRoleMenus(Map<String, Long> menuIds, Map<String, Long> roleIds) {
        if (roleMenuMapper.selectCount(null) > 0) {
            return;
        }
        // ADMIN/SUPER_ADMIN 走 all_menus 免配置; 其余角色给"工作台+本职能相关菜单"最小子集
        Map<String, String[]> grants = new HashMap<>();
        grants.put(Roles.REGISTRAR, new String[]{"dashboard", "patient", "register", "unregister", "reg_stats", "reg_detail"});
        grants.put(Roles.DOCTOR, new String[]{"dashboard", "doctor-ws", "patient", "doctor-worklog"});
        grants.put(Roles.PHARMACIST, new String[]{"dashboard", "dispense-todo", "dispense", "drug-return", "pharmacy-def", "pharmacy-rpt", "wh-stock", "wh-in", "wh-out", "warehouse-def", "warehouse-rpt", "wh-check", "req-mgr", "trf-mgr", "price-adjust", "stock-ledger", "trace-code"});
        grants.put(Roles.CASHIER, new String[]{"dashboard", "charge-todo", "charge-setl", "charge-refund", "invoice-mgr", "charge-rpt", "rpt-setl", "rpt-daily"});
        grants.put(Roles.NURSE, new String[]{"dashboard", "patient", "doctor-ws", "doctor-worklog"});
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

    /**
     * 幂等补充业务角色新菜单授权(2026-09 药库/收费/报表 + 药房药库管理统计/盘点/发票管理上线):
     * 既有库 seedRoleMenus 表非空即跳过, 老库药师(PHARMACIST)缺药库菜单、收费员(CASHIER)缺报表菜单,
     * 在此按 role+menu 判存补授; ADMIN/ORG_ADMIN/SUPER_ADMIN 走 all_menus 免配置, 新菜单自动可见。
     */
    private void ensureBizRoleGrants(Map<String, Long> menuIds, Map<String, Long> roleIds) {
        Map<String, String[]> grants = new HashMap<>();
        grants.put(Roles.PHARMACIST, new String[]{"wh-stock", "wh-in", "wh-out", "wh-check", "pharmacy-def", "pharmacy-rpt", "warehouse-def", "warehouse-rpt", "req-mgr", "trf-mgr", "price-adjust", "stock-ledger", "trace-code"});
        grants.put(Roles.CASHIER, new String[]{"rpt-setl", "rpt-daily", "invoice-mgr", "charge-rpt"});
        grants.put(Roles.REGISTRAR, new String[]{"reg_stats", "reg_detail"});
        grants.put(Roles.DOCTOR, new String[]{"doctor-ws", "doctor-worklog"});
        grants.put(Roles.NURSE, new String[]{"doctor-ws", "doctor-worklog"});
        int added = 0;
        for (Map.Entry<String, String[]> e : grants.entrySet()) {
            Long roleId = roleIds.get(e.getKey());
            if (roleId == null) {
                continue;
            }
            for (String key : e.getValue()) {
                Long menuId = menuIds.get(key);
                if (menuId == null) {
                    continue;
                }
                Long cnt = roleMenuMapper.selectCount(new QueryWrapper<SysRoleMenu>()
                        .eq("role_id", roleId).eq("menu_id", menuId));
                if (cnt == null || cnt == 0) {
                    roleMenuMapper.insert(new SysRoleMenu(roleId, menuId, null));
                    added++;
                }
            }
        }
        if (added > 0) {
            log.info("业务角色新菜单授权已补充: 药师(药库+管理统计+盘点+请领调拨调价台账追溯码)/收费员(报表+发票管理收费统计)/挂号员(挂号统计与明细)/医生护士(门诊医生站+医生工作日志) 共 {} 条", added);
        }
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
     * 幂等将"排班号源"菜单移至"门诊挂号台"目录(2026-09 分组调整, 排班与挂号业务同域):
     * 重挂 outpatient 目录且 sort_no 续接该目录现有子项之后; 菜单 id 不变, 角色授权不受影响。
     */
    private void moveScheduleToOutpatient() {
        SysMenu outpatient = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "outpatient").last("LIMIT 1"));
        SysMenu schedule = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "schedule").last("LIMIT 1"));
        if (outpatient == null || schedule == null || outpatient.getId().equals(schedule.getParentId())) {
            return;
        }
        int maxSort = 0;
        for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", outpatient.getId()))) {
            maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
        }
        schedule.setParentId(outpatient.getId());
        schedule.setSortNo(maxSort + 1);
        menuMapper.updateById(schedule);
        log.info("排班号源菜单已移至门诊挂号台目录");
    }

    /**
     * 幂等将"退号"菜单更名为"退号换号"(2026-09 换号能力同时提供于挂号工作站与本页, 菜单名随之修订):
     * 仅当现名仍为旧名"退号"时更新, 避免误写自定义名称; 菜单 id 不变, 角色授权不受影响。
     */
    private void renameUnregisterMenu() {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "unregister").last("LIMIT 1"));
        if (m == null || !"退号".equals(m.getMenuName())) {
            return;
        }
        m.setMenuName("退号换号");
        menuMapper.updateById(m);
        log.info("退号菜单已更名为退号换号");
    }

    /**
     * 幂等确保"挂号统计"菜单存在(2026-09 挂号统计查询页上线): 挂门诊挂号台目录,
     * sort_no 续接目录现有子项最大值(即排在排班号源之后)。既有库 seedMenus 表非空即跳过,
     * 故在此按 menu_key 判存补种, 并将 id 回填 menuIds 供角色补授权(ensureBizRoleGrants 给挂号员)。
     */
    private void ensureRegStatsMenu(Map<String, Long> menuIds) {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "reg_stats").last("LIMIT 1"));
        if (m == null) {
            SysMenu outpatient = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "outpatient").last("LIMIT 1"));
            if (outpatient == null) {
                return;
            }
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", outpatient.getId()))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            m = new SysMenu();
            m.setParentId(outpatient.getId());
            m.setMenuKey("reg_stats");
            m.setMenuName("挂号统计");
            m.setMenuType(2);
            m.setComp("RegStatistics");
            m.setSortNo(maxSort + 1);
            m.setVisible(1);
            m.setStatus(1);
            menuMapper.insert(m);
            log.info("挂号统计菜单已补充(reg_stats/RegStatistics)");
        }
        menuIds.put("reg_stats", m.getId());
    }

    /**
     * 幂等确保“挂号明细”菜单存在(挂号明细只读查询页, 排在挂号统计之后): 挂门诊挂号台目录,
     * sort_no 续接目录现有子项最大值。既有库 seedMenus 表非空即跳过, 故在此按 menu_key 判存补种,
     * 并将 id 回填 menuIds 供角色补授权(ensureBizRoleGrants 给挂号员)。
     */
    private void ensureRegDetailMenu(Map<String, Long> menuIds) {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "reg_detail").last("LIMIT 1"));
        if (m == null) {
            SysMenu outpatient = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "outpatient").last("LIMIT 1"));
            if (outpatient == null) {
                return;
            }
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", outpatient.getId()))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            m = new SysMenu();
            m.setParentId(outpatient.getId());
            m.setMenuKey("reg_detail");
            m.setMenuName("挂号明细");
            m.setMenuType(2);
            m.setComp("RegDetailQuery");
            m.setSortNo(maxSort + 1);
            m.setVisible(1);
            m.setStatus(1);
            menuMapper.insert(m);
            log.info("挂号明细菜单已补充(reg_detail/RegDetailQuery)");
        }
        menuIds.put("reg_detail", m.getId());
    }
    
    /**
     * 幂等将“基础数据”目录并入“平台管理”(两组菜单合并):
     * 子菜单(科室/职工/排班/收费项目对照)重挂到 platform 目录、排序续接原平台子项之后,
     * 随后删除 basedata 目录及其角色授权残留(旧 role_menu 指向已删 id 会被 treeByIds 自动忽略, 清理为卫生)。
     * 子集角色无需补授权: SysMenuService.treeByIds 会自动补全命中菜单的祖先目录。
     * 需在 moveAreaCodeMenuToStdDict 之后执行(老库 area-code 曾挂 basedata 下, 先移走再合并)。
     */
    private void mergeBasedataIntoPlatform() {
        SysMenu basedata = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "basedata").last("LIMIT 1"));
        SysMenu platform = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "platform").last("LIMIT 1"));
        if (basedata == null || platform == null) {
            return;
        }
        List<SysMenu> children = menuMapper.selectList(new QueryWrapper<SysMenu>()
                .eq("parent_id", basedata.getId()).orderByAsc("sort_no", "id"));
        int maxSort = 0;
        for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", platform.getId()))) {
            maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
        }
        for (SysMenu c : children) {
            c.setParentId(platform.getId());
            c.setSortNo(++maxSort);
            menuMapper.updateById(c);
        }
        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("menu_id", basedata.getId()));
        menuMapper.deleteById(basedata.getId());
        log.info("「基础数据」目录已并入「平台管理」(迁移子菜单 {} 个)", children.size());
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
     * 幂等绑定药库菜单组件(2026-09 药库前端上线): 采购入库/出库管理/库存流水绑定组件并清除建设中标记;
     * 既有库 seedMenus 表非空即跳过, 故在此按 menu_key 原地更新; 出库管理(wh-out)为新菜单补种
     * (挂 warehouse 目录, sort_no 续接), ADMIN/SUPER_ADMIN 走 all_menus 免配置即可见。
     */
    private void bindWarehouseMenus() {
        bindMenuComp("wh-in", "StockInManage");
        bindMenuComp("wh-stock", "DrugStock");
        if (bindMenuComp("wh-out", "StockOutManage")) {
            return;
        }
        SysMenu whDir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "warehouse").last("LIMIT 1"));
        if (whDir == null) {
            return;
        }
        int maxSort = 0;
        for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", whDir.getId()))) {
            maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
        }
        SysMenu m = new SysMenu();
        m.setParentId(whDir.getId());
        m.setMenuKey("wh-out");
        m.setMenuName("出库管理");
        m.setMenuType(2);
        m.setComp("StockOutManage");
        m.setSortNo(maxSort + 1);
        m.setVisible(1);
        m.setStatus(1);
        menuMapper.insert(m);
        log.info("出库管理菜单已补充(wh-out/StockOutManage)");
    }

    /**
     * 幂等绑定药房菜单组件(2026-09 药房前端上线): 待发药/调剂发药/退药绑定组件并清除建设中标记;
     * 既有库 seedMenus 表非空即跳过, 故在此按 menu_key 原地更新(见 bindMenuComp)。
     */
    private void bindPharmacyMenus() {
        bindMenuComp("dispense-todo", "DispenseTodo");
        bindMenuComp("dispense", "DispenseRecord");
        bindMenuComp("drug-return", "DrugReturn");
    }

    /**
     * 幂等绑定收费结算菜单组件(2026-09 收费前端上线): 待收费/医保结算/退费绑定组件并清除建设中标记。
     */
    private void bindChargeMenus() {
        bindMenuComp("charge-todo", "ChargeTodo");
        bindMenuComp("charge-setl", "ChargeSetl");
        bindMenuComp("charge-refund", "ChargeRefund");
    }

    /** 幂等绑定菜单组件并清除建设中标记(phase); 已绑定返回 true 表示无需补种。
     * 注意: updateById 忽略 null 字段, 清 phase 须用 UpdateWrapper 显式 set null。 */
    private boolean bindMenuComp(String menuKey, String comp) {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", menuKey).last("LIMIT 1"));
        if (m == null) {
            return false;
        }
        if (comp.equals(m.getComp())) {
            if (m.getPhase() == null) {
                return true;
            }
            /* comp 已绑定但建设中标记残留(旧版迁移未清 phase): 仅补清 phase */
            menuMapper.update(null, new UpdateWrapper<SysMenu>().eq("id", m.getId()).set("phase", null));
            return true;
        }
        menuMapper.update(null, new UpdateWrapper<SysMenu>()
                .eq("id", m.getId())
                .set("comp", comp)
                .set("phase", null));
        log.info("菜单组件已绑定: {} -> {}", menuKey, comp);
        return true;
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

    /**
     * 幂等激活查询报表菜单(P1f 交付): 老库 rpt-setl/rpt-daily 仍为占位(comp=null/phase=P1f),
     * 在此回填前端组件并清除 phase; 新库种子已直接携带 comp, 本方法检测到一致则跳过。
     */
    private void activateReportMenus() {
        Map<String, String> comps = new HashMap<>();
        comps.put("rpt-setl", "SettleRecords");
        comps.put("rpt-daily", "DailySettle");
        for (Map.Entry<String, String> e : comps.entrySet()) {
            SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", e.getKey()).last("LIMIT 1"));
            if (m == null || e.getValue().equals(m.getComp())) {
                continue;
            }
            menuMapper.update(null, new UpdateWrapper<SysMenu>()
                    .eq("id", m.getId())
                    .set("comp", e.getValue())
                    .set("phase", null));
            log.info("查询报表菜单已激活: {} -> {}", e.getKey(), e.getValue());
        }
    }

    /**
     * 幂等合并医生站菜单(候诊列表+接诊工作台 -> 单一门诊医生站 DoctorWorkstation):
     * 新库种子已直接种 doctor-ws; 既有库 seedMenus 表非空即跳过, 在此按 menu_key 判存补种
     * (挂 doctor 目录, sort_no 续接), 并将 id 回填 menuIds 供角色补授权(ensureBizRoleGrants)。
     * 旧 doctor-queue/doctor-work 菜单及授权由 RETIRED_MENU_KEYS 清理(幂等)。
     */
    private void mergeDoctorMenus(Map<String, Long> menuIds) {
        SysMenu ws = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "doctor-ws").last("LIMIT 1"));
        if (ws == null) {
            SysMenu doctorDir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "doctor").last("LIMIT 1"));
            if (doctorDir == null) {
                return;
            }
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", doctorDir.getId()))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            ws = new SysMenu();
            ws.setParentId(doctorDir.getId());
            ws.setMenuKey("doctor-ws");
            ws.setMenuName("门诊医生站");
            ws.setMenuType(2);
            ws.setComp("DoctorWorkstation");
            ws.setSortNo(maxSort + 1);
            ws.setVisible(1);
            ws.setStatus(1);
            menuMapper.insert(ws);
            log.info("门诊医生站菜单已补充(doctor-ws/DoctorWorkstation)");
        }
        menuIds.put("doctor-ws", ws.getId());
    }

    /**
     * 幂等确保“医生工作日志”菜单存在(医生站工作量查询页, 排在门诊医生站之后): 挂 doctor 目录,
     * sort_no 续接目录现有子项最大值。既有库 seedMenus 表非空即跳过, 故在此按 menu_key 判存补种,
     * 并将 id 回填 menuIds 供角色补授权(ensureBizRoleGrants 给医生/护士)。
     */
    private void ensureDoctorWorklogMenu(Map<String, Long> menuIds) {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "doctor-worklog").last("LIMIT 1"));
        if (m == null) {
            SysMenu doctorDir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "doctor").last("LIMIT 1"));
            if (doctorDir == null) {
                return;
            }
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", doctorDir.getId()))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            m = new SysMenu();
            m.setParentId(doctorDir.getId());
            m.setMenuKey("doctor-worklog");
            m.setMenuName("医生工作日志");
            m.setMenuType(2);
            m.setComp("DoctorWorklog");
            m.setSortNo(maxSort + 1);
            m.setVisible(1);
            m.setStatus(1);
            menuMapper.insert(m);
            log.info("医生工作日志菜单已补充(doctor-worklog/DoctorWorklog)");
        }
        menuIds.put("doctor-worklog", m.getId());
    }

    /**
     * 幂等补种药房目录新菜单(2026-09 药房管理/药房统计上线): 药房管理(PharmacyDef, 药房定义维护)与
     * 药房统计(PharmacyReport)。既有库 seedMenus 表非空即跳过, 故在此按 menu_key 判存补种
     * (挂 pharmacy 目录, sort_no 续接), 并将 id 回填 menuIds 供角色补授权(ensureBizRoleGrants 给药师)。
     */
    private void ensureNewPharmacyMenus(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "pharmacy", "pharmacy-def", "药房管理", "PharmacyDef");
        ensureChildMenu(menuIds, "pharmacy", "pharmacy-rpt", "药房统计", "PharmacyReport");
    }

    /**
     * 幂等补种药库目录新菜单(2026-09 药库管理/药库统计上线)并激活既有占位菜单: 药库管理
     * (WarehouseDef, 药库定义维护)与药库统计(WarehouseReport); wh-drug/wh-check 为既有占位菜单
     * (comp=null/phase=P1d), 在此绑定 DrugCatalogView/StockCheck 并清除建设中标记(见 bindMenuComp)。
     */
    private void ensureNewWarehouseMenus(Map<String, Long> menuIds) {
        bindMenuComp("wh-drug", "DrugCatalogView");
        bindMenuComp("wh-check", "StockCheck");
        ensureChildMenu(menuIds, "warehouse", "warehouse-def", "药库管理", "WarehouseDef");
        ensureChildMenu(menuIds, "warehouse", "warehouse-rpt", "药库统计", "WarehouseReport");
    }

    /**
     * 幂等补种收费结算台目录新菜单(2026-09 发票管理/收费统计上线): 发票管理(InvoiceManage)与
     * 收费统计(ChargeReport)。既有库 seedMenus 表非空即跳过, 故在此按 menu_key 判存补种
     * (挂 charge 目录, sort_no 续接), 并将 id 回填 menuIds 供角色补授权(ensureBizRoleGrants 给收费员)。
     */
    private void ensureNewChargeMenus(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "charge", "invoice-mgr", "发票管理", "InvoiceManage");
        ensureChildMenu(menuIds, "charge", "charge-rpt", "收费统计", "ChargeReport");
    }

    /**
     * 幂等补种药房药库协同二期菜单(2026-09: 请领/调拨/调价/进销存台账/医保追溯码)。既有库 seedMenus
     * 表非空即跳过, 故在此按 menu_key 判存补种: 请领/追溯码挂 pharmacy 目录, 调拨/调价/台账挂
     * warehouse 目录; 并将 id 回填 menuIds 供角色补授权(ensureBizRoleGrants 给药师)。
     */
    private void ensureStockChainMenus(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "pharmacy", "req-mgr", "药品请领", "RequisitionManage");
        ensureChildMenu(menuIds, "pharmacy", "trace-code", "药品追溯码", "TraceCodeManage");
        ensureChildMenu(menuIds, "warehouse", "trf-mgr", "库存调拨", "TransferManage");
        ensureChildMenu(menuIds, "warehouse", "price-adjust", "药品调价", "PriceAdjust");
        ensureChildMenu(menuIds, "warehouse", "stock-ledger", "进销存台账", "DrugLedger");
    }

    /**
     * 幂等确保目录下叶子菜单存在(新库种子已含, 老库补种): 按 menu_key 判存, 不存在则挂指定目录
     * (sort_no 续接目录现有子项最大值); 存在或补种后将 id 回填 menuIds 供角色补授权。
     * 目录不存在(异常库)则跳过不补种, 不拖垮后续初始化。
     */
    private void ensureChildMenu(Map<String, Long> menuIds, String dirKey, String key, String name, String comp) {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", key).last("LIMIT 1"));
        if (m == null) {
            SysMenu dir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", dirKey).last("LIMIT 1"));
            if (dir == null) {
                return;
            }
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", dir.getId()))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            m = new SysMenu();
            m.setParentId(dir.getId());
            m.setMenuKey(key);
            m.setMenuName(name);
            m.setMenuType(2);
            m.setComp(comp);
            m.setSortNo(maxSort + 1);
            m.setVisible(1);
            m.setStatus(1);
            menuMapper.insert(m);
            log.info("菜单已补充: {}({}/{})", name, key, comp);
        }
        menuIds.put(key, m.getId());
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

    /** 确保租户有默认县级牵头机构, 返回其 id */
    private Long ensureDefaultOrg(SysTenant t) {
        List<SysOrg> orgs = orgMapper.selectList(new QueryWrapper<SysOrg>().orderByAsc("id"));
        if (!orgs.isEmpty()) {
            // 优先取牵头机构, 其次县级机构作为默认归属
            for (SysOrg o : orgs) {
                if (o.getIsLead() != null && o.getIsLead() == 1) {
                    return o.getId();
                }
            }
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
        o.setIsLead(1);
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
     * 幂等保证 sys_org.is_lead 列存在并回填牵头标识(牵头与 org_level=县级 解耦: 县级可有多个成员机构, 牵头租户内唯一)。
     * 回填规则: 机构编码=租户登录码(org_code=tenant_code, 与开通/种子路径一致)即牵头;
     * 无匹配的租户退化取最小 id 的县级机构; 其余多余牵头(脏数据)清零只保留每租户最小 id 一条。
     * 必须在任何 MyBatis-Plus 查询 sys_org 之前执行, 否则实体映射列缺失会报错。
     */
    private void ensureOrgLeadColumn() {
        String check = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'sys_org' AND column_name = 'is_lead'";
        try (Connection conn = dataSource.getConnection()) {
            boolean exists;
            try (PreparedStatement ps = conn.prepareStatement(check); ResultSet rs = ps.executeQuery()) {
                exists = rs.next() && rs.getInt(1) > 0;
            }
            if (!exists) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE sys_org ADD COLUMN is_lead TINYINT NOT NULL DEFAULT 0 "
                            + "COMMENT '是否牵头机构:1-牵头(每医共体唯一) 0-成员' AFTER org_level");
                }
                log.info("sys_org 已追加 is_lead 牵头标识列");
            }
            try (Statement st = conn.createStatement()) {
                int n1 = st.executeUpdate("UPDATE sys_org o JOIN sys_tenant t ON t.id = o.tenant_id "
                        + "SET o.is_lead = 1 WHERE o.org_code = t.tenant_code AND o.deleted = 0 AND o.is_lead <> 1");
                int n2 = st.executeUpdate("UPDATE sys_org o JOIN (SELECT m.tenant_id, MIN(m.id) mid FROM sys_org m "
                        + "WHERE m.org_level = 1 AND m.deleted = 0 AND NOT EXISTS (SELECT 1 FROM (SELECT tenant_id FROM sys_org WHERE is_lead = 1 AND deleted = 0) x WHERE x.tenant_id = m.tenant_id) "
                        + "GROUP BY m.tenant_id) z ON z.tenant_id = o.tenant_id AND z.mid = o.id SET o.is_lead = 1");
                int n3 = st.executeUpdate("UPDATE sys_org o SET o.is_lead = 0 WHERE o.is_lead = 1 AND o.deleted = 0 "
                        + "AND o.id <> (SELECT w.mid FROM (SELECT MIN(id) mid, tenant_id FROM sys_org WHERE is_lead = 1 AND deleted = 0 GROUP BY tenant_id) w WHERE w.tenant_id = o.tenant_id)");
                if (n1 + n2 + n3 > 0) {
                    log.info("sys_org 牵头标识回填: 按租户码匹配 {} 条, 兜底补齐 {} 条, 多余清零 {} 条", n1, n2, n3);
                }
            }
        } catch (Exception e) {
            log.warn("sys_org.is_lead 列迁移跳过: {}", e.getMessage());
        }
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
