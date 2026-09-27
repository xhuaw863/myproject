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
import java.sql.SQLException;
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
            mergeSystemIntoPlatform();
            moveScheduleToOutpatient();
            movePatientToPlatform();
            mergeChargeIntoOutpatient();
            ensureRegStatsMenu(menuIds);
            ensureRegDetailMenu(menuIds);
            renameUnregisterMenu();
            ensureCommunityDictMenus();
            ensureOrgMgmtDir();
            bindWarehouseMenus();
            bindPharmacyMenus();
            bindChargeMenus();
            ensureNewPharmacyMenus(menuIds);
            ensurePharmaPriceMenu(menuIds);
            ensureNewWarehouseMenus(menuIds);
            ensureNewChargeMenus(menuIds);
            ensureChargeWorkstationMenu(menuIds);
            ensureStockChainMenus(menuIds);
            migrateDictMapToCatalogMap();
            ensureBasedataMenuUnderPlatform();
            activateReportMenus();
            mergeDictDirsIntoPlatform();
            renameBizDirs();
            renameMenusToPageTitle();
            moveReportIntoOutpatient();
            mergeDoctorMenus(menuIds);
            ensureDoctorWorklogMenu(menuIds);
            ensureNurseStationMenus(menuIds);
            ensureTreatmentMenus(menuIds);
            ensureMedtechMenus(menuIds);
            renameNurseTreatmentMedtechMenus();
            ensureSystemParamMenu(menuIds);
            ensureVerifyConsoleMenu(menuIds);
            Map<String, Long> roleIds = seedGlobalRoles();
            ensureOrgAdminRole();
            ensureTherapistTechnicianRoles(roleIds);
            seedRoleMenus(menuIds, roleIds);
            ensureBizRoleGrants(menuIds, roleIds);
            seedPlatformAdmin(roleIds);
            migrateTenants(roleIds);
            purgeMenuTombstones();
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
        // 医共体管理(含基础数据+系统管理: 医院信息/用户/科室/职工/患者档案 + 机构/角色/菜单维护, 2026-09 平台管理改名并吸收系统管理目录; 收费项目对照已并入三目录医保对照下线; 排班号源 2026-09 移至门诊挂号台; 患者建档/查询 2026-09 移入并更名档案管理)
        long g1 = dir("platform", "医共体管理", 0L, ++sort[0]);
        ids.put("tenant-info", menuK("tenant-info", "医院信息", "TenantInfo", null, g1, ++sort[0]));
        ids.put("user-manage", menuK("user-manage", "用户管理", "UserManage", null, g1, ++sort[0]));
        ids.put("dept", menuK("dept", "科室管理", "DeptManage", null, g1, ++sort[0]));
        ids.put("staff", menuK("staff", "职工管理", "StaffManage", null, g1, ++sort[0]));
        ids.put("patient", menuK("patient", "档案管理", "PatientManage", null, g1, ++sort[0]));
        ids.put("org-manage", menuK("org-manage", "机构管理", "OrgManage", null, g1, ++sort[0]));
        ids.put("role-manage", menuK("role-manage", "角色权限", "RoleManage", null, g1, ++sort[0]));
        ids.put("menu-manage", menuK("menu-manage", "菜单管理", "MenuManage", null, g1, ++sort[0]));
        // 系统参数(四级作用域参数配置: 全局默认/租户/机构/科室覆盖, 2026-09)
        ids.put("system-param", menuK("system-param", "系统参数", "SystemParam", null, g1, ++sort[0]));
        // 医保字典(2026-09 移入医共体管理目录, sort 101/102 排在机构/角色/菜单与基础数据子目录之后)
        long g3 = dir("yb-dict", "医保字典", g1, 101);
        ids.put("dict-download", menuK("dict-download", "字典下载", "DictDownload", null, g3, ++sort[0]));
        ids.put("dict-version", menuK("dict-version", "版本状态", "DictVersion", null, g3, ++sort[0]));
        ids.put("catalog-map", menuK("catalog-map", "医保目录对照", "CatalogMap", null, g3, ++sort[0]));
        // 医保验证台(独立静态调试页外链, 前端 app.js 按 key 拦截 window.open; 医保按机构对接, 收编进菜单随机构上下文生效)
        ids.put("verify-console", menuK("verify-console", "医保验证台", null, null, g3, ++sort[0]));
        // 标准字典(2026-09 移入医共体管理目录; 超管视图由 SysMenuService.treeOnlyTopKeys 递归命中仍作顶级展示)
        long g4 = dir("std-dict", "标准字典", g1, 102);
        ids.put("std-dict-browse", menuK("std-dict-browse", "字典浏览", "StdDictBrowse", null, g4, ++sort[0]));
        ids.put("std-dict-import", menuK("std-dict-import", "提取入库", "StdDictImport", null, g4, ++sort[0]));
        // 行政区划为基础字典(全局共享 area_code_2021), 归标准字典目录, 仅平台超管可见
        ids.put("area-code", menuK("area-code", "行政区划", "AreaManage", null, g4, ++sort[0]));
        // 门诊挂号收费(2026-09 原"门诊挂号台"与"收费结算台"两目录合并为单目录, key 沿用 outpatient)
        long g5 = dir("outpatient", "门诊挂号收费", 0L, ++sort[0]);
        ids.put("register", menuK("register", "门诊挂号", "RegistrationDesk", null, g5, ++sort[0]));
        ids.put("unregister", menuK("unregister", "退号换号", "UnregisterDesk", null, g5, ++sort[0]));
        ids.put("schedule", menuK("schedule", "排班号源", "ScheduleManage", null, g5, ++sort[0]));
        ids.put("reg_stats", menuK("reg_stats", "挂号统计", "RegStatistics", null, g5, ++sort[0]));
        ids.put("reg_detail", menuK("reg_detail", "挂号明细", "RegDetailQuery", null, g5, ++sort[0]));
        // 收费域菜单并入门诊挂号收费目录(g5), 独立"charge"目录已废弃(存量库由 mergeChargeIntoOutpatient 迁移)
        ids.put("charge-ws", menuK("charge-ws", "门诊收费", "ChargeWorkstation", null, g5, ++sort[0]));
        ids.put("charge-todo", menuK("charge-todo", "待收费", "ChargeTodo", null, g5, ++sort[0]));
        ids.put("charge-setl", menuK("charge-setl", "收费结算记录", "ChargeSetl", null, g5, ++sort[0]));
        ids.put("charge-refund", menuK("charge-refund", "退费记录", "ChargeRefund", null, g5, ++sort[0]));
        ids.put("invoice-mgr", menuK("invoice-mgr", "发票管理", "InvoiceManage", null, g5, ++sort[0]));
        ids.put("charge-rpt", menuK("charge-rpt", "收费统计", "ChargeReport", null, g5, ++sort[0]));
        // 医生站(候诊列表+接诊工作台合并为单一门诊医生站)
        long g6 = dir("doctor", "门诊医生站", 0L, ++sort[0]);
        ids.put("doctor-ws", menuK("doctor-ws", "门诊医生工作站", "DoctorWorkstation", null, g6, ++sort[0]));
        ids.put("doctor-worklog", menuK("doctor-worklog", "医生工作日志", "DoctorWorklog", null, g6, ++sort[0]));
        // 药房(2026-09 P1d 交付: 待发药/调剂发药/退药前端已上线; P2 药房管理/药房统计上线)
        long g7 = dir("pharmacy", "药房系统", 0L, ++sort[0]);
        ids.put("dispense-todo", menuK("dispense-todo", "待发药", "DispenseTodo", null, g7, ++sort[0]));
        ids.put("dispense", menuK("dispense", "调剂发药", "DispenseRecord", null, g7, ++sort[0]));
        ids.put("drug-return", menuK("drug-return", "退药", "DrugReturn", null, g7, ++sort[0]));
        ids.put("pharmacy-def", menuK("pharmacy-def", "药房管理", "PharmacyDef", null, g7, ++sort[0]));
        ids.put("pharmacy-rpt", menuK("pharmacy-rpt", "药房统计", "PharmacyReport", null, g7, ++sort[0]));
        // 药房药库协同二期(2026-09): 请领/调拨/调价/进销存台账/医保追溯码
        ids.put("req-mgr", menuK("req-mgr", "药品请领", "RequisitionManage", null, g7, ++sort[0]));
        ids.put("trace-code", menuK("trace-code", "药品追溯码", "TraceCodeManage", null, g7, ++sort[0]));
        // 三期(药房维度定价): 药房定价覆盖价维护, 未覆盖回落目录零售价
        ids.put("price-mgr", menuK("price-mgr", "药房定价", "PharmacyPriceManage", null, g7, ++sort[0]));
        // 药库(采购入库/出库管理/库存流水已上线; P2 药品目录/盘点绑定组件, 药库管理/药库统计上线)
        long g8 = dir("warehouse", "药库系统", 0L, ++sort[0]);
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
        // 门诊护士站(2026-09 基座: 待执行医嘱/皮试/输液/过敏登记/执行记录, 联动 his_order.exec_status)
        long g9 = dir("nurse-station-group", "门诊护士站", 0L, ++sort[0]);
        ids.put("nurse-pending", menuK("nurse-pending", "待执行医嘱", "NursePending", null, g9, ++sort[0]));
        ids.put("nurse-skin-test", menuK("nurse-skin-test", "皮试管理", "NurseSkinTest", null, g9, ++sort[0]));
        ids.put("nurse-infusion", menuK("nurse-infusion", "输液管理", "NurseInfusion", null, g9, ++sort[0]));
        ids.put("nurse-allergy", menuK("nurse-allergy", "过敏档案", "NurseAllergy", null, g9, ++sort[0]));
        ids.put("nurse-exec-log", menuK("nurse-exec-log", "执行记录查询", "NurseExecLog", null, g9, ++sort[0]));
        // 治疗管理(2026-09 基座: 疗程计划/逐次执行/设备台账)
        long g10 = dir("treatment-group", "治疗管理", 0L, ++sort[0]);
        ids.put("treatment-pending", menuK("treatment-pending", "待执行治疗", "TreatmentPending", null, g10, ++sort[0]));
        ids.put("treatment-plan", menuK("treatment-plan", "疗程管理", "TreatmentPlan", null, g10, ++sort[0]));
        ids.put("treatment-equip", menuK("treatment-equip", "设备管理", "TreatmentEquipment", null, g10, ++sort[0]));
        ids.put("treatment-log", menuK("treatment-log", "治疗记录查询", "TreatmentLog", null, g10, ++sort[0]));
        // 医技管理(2026-09 基座: 标本采集签收/报告书写/危急值闭环/报告查询)
        long g11 = dir("medtech-group", "医技管理", 0L, ++sort[0]);
        ids.put("medtech-specimen", menuK("medtech-specimen", "标本管理", "MedtechSpecimen", null, g11, ++sort[0]));
        ids.put("medtech-report", menuK("medtech-report", "报告工作站", "MedtechReport", null, g11, ++sort[0]));
        ids.put("medtech-critical", menuK("medtech-critical", "危急值管理", "MedtechCritical", null, g11, ++sort[0]));
        ids.put("medtech-critical-rule", menuK("medtech-critical-rule", "危急值规则", "MedtechCriticalRule", null, g11, ++sort[0]));
        ids.put("medtech-report-query", menuK("medtech-report-query", "报告查询", "MedtechReportQuery", null, g11, ++sort[0]));
        // 收费域菜单已并入门诊挂号收费目录 g5(见上), 独立"收费结算台"目录已废弃(2026-09 两目录合并)
        // 结算记录/门诊日结(2026-09 移入门诊挂号收费目录 g5), 独立"查询报表"目录已废弃(存量库由 moveReportIntoOutpatient 迁移)
        ids.put("rpt-setl", menuK("rpt-setl", "结算记录", "SettleRecords", null, g5, ++sort[0]));
        ids.put("rpt-daily", menuK("rpt-daily", "门诊日结", "DailySettle", null, g5, ++sort[0]));
        // 系统管理三菜单(机构/角色/菜单维护)种子已直接挂医共体管理目录 g1(见上), 独立"系统管理"目录已废弃
        log.info("RBAC 菜单种子完成, 共 {} 项", ids.size());
        return ids;
    }

    /** 已下线菜单(功能移除或合并, 不再种子): 清理其角色授权与菜单行, 幂等;
     *  charge-item=收费项目对照(与三目录医保对照重复, 2026-09 下线);
     *  doctor-queue/doctor-work=候诊列表/接诊工作台(合并为门诊医生工作站 doctor-ws, 2026-09 合并) */
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

    /** 因“目录合并”而下线(非功能下线)的目录 key: pruneRetiredMenus 与各合并迁移走的都是逻辑删除,
     *  行作墓碑留在表内; 而 uk_menu_key 是物理唯一索引, 墓碑会阻塞同名 key 将来复活。 */
    private static final String[] MERGED_AWAY_DIR_KEYS = {"charge", "report", "system"};

    /**
     * 物理清除已下线菜单/已合并目录留下的逻辑删除墓碑(deleted=1)。
     * 注册在 run() 最后: 此时所有迁移与补种已完成, 没有任何步骤再依赖这些行。
     * 仅删 deleted=1 且 key 在下线清单内的行, 活菜单一律不碰(幂等, 第二次启动 0 行)。
     */
    private void purgeMenuTombstones() {
        StringBuilder in = new StringBuilder();
        for (String key : RETIRED_MENU_KEYS) {
            if (in.length() > 0) { in.append(","); }
            in.append('\'').append(key).append('\'');
        }
        for (String key : MERGED_AWAY_DIR_KEYS) {
            if (in.length() > 0) { in.append(","); }
            in.append('\'').append(key).append('\'');
        }
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            int refs = st.executeUpdate("DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id"
                    + " WHERE m.deleted = 1 AND m.menu_key IN (" + in + ")");
            int rows = st.executeUpdate("DELETE FROM sys_menu WHERE deleted = 1 AND menu_key IN (" + in + ")");
            if (rows > 0 || refs > 0) {
                log.info("菜单墓碑物理清理: sys_menu 删 {} 行, sys_role_menu 删 {} 行", rows, refs);
            }
        } catch (SQLException e) {
            log.warn("菜单墓碑物理清理失败(不影响启动): {}", e.getMessage());
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
        ids.put(Roles.THERAPIST, role(Roles.THERAPIST, "治疗师", 0, "治疗计划执行"));
        ids.put(Roles.TECHNICIAN, role(Roles.TECHNICIAN, "医技人员", 0, "医技检查检验"));
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
     * 幂等补种全局预置业务角色(2026-09 三模块基座): 治疗师(THERAPIST)/医技人员(TECHNICIAN)。
     * 既有库 seedGlobalRoles 表非空即跳过种子块, 在此按 role_code 判存补种(all_menus=0),
     * 并将 id 回填 roleIds 供 seedRoleMenus/ensureBizRoleGrants 授权。
     */
    private void ensureTherapistTechnicianRoles(Map<String, Long> roleIds) {
        ensureGlobalRole(roleIds, Roles.THERAPIST, "治疗师", "治疗计划执行");
        ensureGlobalRole(roleIds, Roles.TECHNICIAN, "医技人员", "医技检查检验");
    }

    /** 幂等补种全局预置角色并回填 roleIds(roleIds 已含则跳过; 存在则回查 id; 均无则种 all_menus=0)。 */
    private void ensureGlobalRole(Map<String, Long> roleIds, String code, String name, String remark) {
        if (roleIds.containsKey(code)) {
            return;
        }
        SysRole existing = roleMapper.selectOne(new QueryWrapper<SysRole>()
                .isNull("tenant_id").eq("role_code", code).last("LIMIT 1"));
        if (existing != null) {
            roleIds.put(code, existing.getId());
            return;
        }
        roleIds.put(code, role(code, name, 0, remark));
        log.info("{} 角色已补充({}, 全局预置)", name, code);
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
        grants.put(Roles.DOCTOR, new String[]{"dashboard", "doctor-ws", "patient", "doctor-worklog", "nurse-allergy", "medtech-report-query"});
        grants.put(Roles.PHARMACIST, new String[]{"dashboard", "dispense-todo", "dispense", "drug-return", "pharmacy-def", "pharmacy-rpt", "wh-stock", "wh-in", "wh-out", "warehouse-def", "warehouse-rpt", "wh-check", "req-mgr", "trf-mgr", "price-adjust", "stock-ledger", "trace-code", "price-mgr"});
        grants.put(Roles.CASHIER, new String[]{"dashboard", "charge-ws", "charge-todo", "charge-setl", "charge-refund", "invoice-mgr", "charge-rpt", "rpt-setl", "rpt-daily"});
        grants.put(Roles.NURSE, new String[]{"dashboard", "patient", "nurse-pending", "nurse-skin-test", "nurse-infusion", "nurse-allergy", "nurse-exec-log"});
        grants.put(Roles.THERAPIST, new String[]{"dashboard", "patient", "treatment-pending", "treatment-plan", "treatment-equip", "treatment-log"});
        grants.put(Roles.TECHNICIAN, new String[]{"dashboard", "patient", "medtech-specimen", "medtech-report", "medtech-critical", "medtech-critical-rule", "medtech-report-query"});
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
        grants.put(Roles.PHARMACIST, new String[]{"wh-stock", "wh-in", "wh-out", "wh-check", "pharmacy-def", "pharmacy-rpt", "warehouse-def", "warehouse-rpt", "req-mgr", "trf-mgr", "price-adjust", "stock-ledger", "trace-code", "price-mgr"});
        grants.put(Roles.CASHIER, new String[]{"rpt-setl", "rpt-daily", "invoice-mgr", "charge-rpt", "charge-ws"});
        grants.put(Roles.REGISTRAR, new String[]{"reg_stats", "reg_detail"});
        grants.put(Roles.DOCTOR, new String[]{"doctor-ws", "doctor-worklog", "nurse-allergy", "medtech-report-query"});
        grants.put(Roles.NURSE, new String[]{"doctor-ws", "doctor-worklog", "nurse-pending", "nurse-skin-test", "nurse-infusion", "nurse-allergy", "nurse-exec-log"});
        grants.put(Roles.THERAPIST, new String[]{"dashboard", "patient", "treatment-pending", "treatment-plan", "treatment-equip", "treatment-log"});
        grants.put(Roles.TECHNICIAN, new String[]{"dashboard", "patient", "medtech-specimen", "medtech-report", "medtech-critical", "medtech-critical-rule", "medtech-report-query"});
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
            log.info("业务角色新菜单授权已补充: 药师(药库+管理统计+盘点+请领调拨调价台账追溯码+药房定价)/收费员(报表+发票管理收费统计)/挂号员(挂号统计与明细)/医生护士(门诊医生站+医生工作日志)/医生(过敏登记+报告查询)/护士(护士站五项)/治疗师医技人员(治疗/医技全量) 共 {} 条", added);
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
     * 幂等将"患者建档/查询"菜单移至"医共体管理"目录并更名为"档案管理"(2026-09 菜单调整):
     * 患者档案属机构级基础档案, 与医院信息/用户/科室/职工同级; 仅当父目录非 platform 或
     * 仍为旧名时更新。菜单 id 不变, sys_role_menu 按 id 授权全部保持(挂号员/医生/护士子集不受影响)。
     */
    private void movePatientToPlatform() {
        SysMenu platform = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "platform").last("LIMIT 1"));
        SysMenu patient = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "patient").last("LIMIT 1"));
        if (platform == null || patient == null
                || (platform.getId().equals(patient.getParentId()) && "档案管理".equals(patient.getMenuName()))) {
            return;
        }
        int maxSort = 0;
        for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", platform.getId()))) {
            maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
        }
        patient.setParentId(platform.getId());
        patient.setSortNo(maxSort + 1);
        patient.setMenuName("档案管理");
        menuMapper.updateById(patient);
        log.info("「患者建档/查询」菜单已移至「医共体管理」并更名为「档案管理」");
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
     * 幂等确保"医保验证台"菜单存在(2026-09 从顶栏静态链接收编进 RBAC 菜单: 医保按机构对接,
     * 入口应随当前登录机构上下文与角色授权生效)。挂"医保字典"子目录末尾, comp 为空(外链页,
     * 前端 app.js onSelect 按 key 拦截 window.open 新标签打开 /verify/index.html)。
     * ADMIN/ORG_ADMIN/SUPER_ADMIN 走 all_menus 自动可见; 业务角色需在角色权限页勾选授权。
     */
    private void ensureVerifyConsoleMenu(Map<String, Long> menuIds) {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "verify-console").last("LIMIT 1"));
        if (m == null) {
            SysMenu ybDir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "yb-dict").last("LIMIT 1"));
            if (ybDir == null) {
                ybDir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "platform").last("LIMIT 1"));
            }
            if (ybDir == null) {
                return;
            }
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", ybDir.getId()))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            m = new SysMenu();
            m.setParentId(ybDir.getId());
            m.setMenuKey("verify-console");
            m.setMenuName("医保验证台");
            m.setMenuType(2);
            m.setSortNo(maxSort + 1);
            m.setVisible(1);
            m.setStatus(1);
            menuMapper.insert(m);
            log.info("医保验证台菜单已补充(verify-console, 外链 /verify/index.html)");
        }
        menuIds.put("verify-console", m.getId());
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
        if (basedata == null || platform == null
                || (basedata.getParentId() != null && basedata.getParentId() != 0L)) {
            // 仅处理老库"顶级"basedata(parent_id=0); 新库"医共体管理"下的 basedata 子目录(parent_id!=0)
            // 由 ensureBasedataMenuUnderPlatform 维护, 不可在此被误当成老目录并入删除。
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
     * 幂等将"平台管理"目录更名为"医共体管理", 并把"系统管理"目录(机构管理/角色权限/菜单管理)并入其中
     * (2026-09 分组调整): 子菜单重挂 platform 目录且 sort_no 续接其现有子项之后, 删除 system 目录及其
     * role_menu 残留授权。子集角色无需补授权: SysMenuService.treeByIds 会自动补全命中菜单的祖先目录;
     * ORG_ADMIN 排除集按子菜单 key 逐个排除(SysRoleService.SYSTEM_MENUS), 目录移除不影响收权。
     */
    private void mergeSystemIntoPlatform() {
        SysMenu platform = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "platform").last("LIMIT 1"));
        if (platform == null) {
            return;
        }
        if ("平台管理".equals(platform.getMenuName())) {
            platform.setMenuName("医共体管理");
            menuMapper.updateById(platform);
            log.info("「平台管理」目录已更名为「医共体管理」");
        }
        SysMenu system = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "system").last("LIMIT 1"));
        if (system == null) {
            return;
        }
        List<SysMenu> children = menuMapper.selectList(new QueryWrapper<SysMenu>()
                .eq("parent_id", system.getId()).orderByAsc("sort_no", "id"));
        int maxSort = 0;
        for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", platform.getId()))) {
            maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
        }
        for (SysMenu c : children) {
            c.setParentId(platform.getId());
            c.setSortNo(++maxSort);
            menuMapper.updateById(c);
        }
        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("menu_id", system.getId()));
        menuMapper.deleteById(system.getId());
        log.info("「系统管理」目录已并入「医共体管理」(迁移子菜单 {} 个)", children.size());
    }

    /**
     * 幂等确保医共体统一字典相关顶级菜单存在(既有库 seedMenus 表非空即跳过时补种):
     * - community-dict(医共体字典): 牵头机构维护三目录+调价+标准字典导入, 非牵头 ADMIN 在 SysRoleService 排除;
     * - org-catalog(机构目录选用): 各机构勾选本院开展的项目(L3)。
     */
    private void ensureCommunityDictMenus() {
        ensureTopMenu("community-dict", "医共体字典", "CommunityDict", 12);
        // org-catalog(机构目录选用) 不再作顶级补种, 改由 ensureOrgMgmtDir 挂到"机构维护管理"目录下(2026-09)
    }

    /**
     * 幂等新建顶级目录"机构维护管理"(org-mgmt, 2026-09), 并将"机构目录选用"(org-catalog)挂其下。
     * 与现有叶子 org-manage(机构CRUD维护, 挂医共体管理下)不重名、不混用。目录 sort=3 紧接
     * platform(=2) 之后与其他大 sort 顶级目录隔开; org-catalog 新库本方法直接建到目录下, 存量库
     * 若仍为顶级则重挂(菜单 id 不变, sys_role_menu 按 id 授权自动保持; 院长走 all_menus 全树不受影响)。
     */
    private void ensureOrgMgmtDir() {
        // 复活墓碑行(basedata 同款坑位): sys_menu.uk_menu_key 为物理唯一索引与 deleted 位无关,
        // 若 org-mgmt 曾被逻辑删除, selectOne 查不到但唯一索引仍占位, 直接 insert 会 Duplicate entry 并静默拖垮后续迁移
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE sys_menu SET deleted = 0 WHERE menu_key = 'org-mgmt' AND deleted = 1");
        } catch (Exception e) {
            log.warn("org-mgmt 墓碑行复活跳过: {}", e.getMessage());
        }
        SysMenu orgMgmt = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "org-mgmt").last("LIMIT 1"));
        if (orgMgmt == null) {
            orgMgmt = new SysMenu();
            orgMgmt.setParentId(0L);
            orgMgmt.setMenuKey("org-mgmt");
            orgMgmt.setMenuName("机构维护管理");
            orgMgmt.setMenuType(1);
            orgMgmt.setSortNo(3);
            orgMgmt.setVisible(1);
            orgMgmt.setStatus(1);
            menuMapper.insert(orgMgmt);
            log.info("顶级目录「机构维护管理」已新建(org-mgmt)");
        } else if (!"机构维护管理".equals(orgMgmt.getMenuName())) {
            orgMgmt.setMenuName("机构维护管理");
            menuMapper.updateById(orgMgmt);
        }
        SysMenu oc = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "org-catalog").last("LIMIT 1"));
        if (oc == null) {
            oc = new SysMenu();
            oc.setParentId(orgMgmt.getId());
            oc.setMenuKey("org-catalog");
            oc.setMenuName("机构目录选用");
            oc.setMenuType(2);
            oc.setComp("OrgCatalog");
            oc.setSortNo(1);
            oc.setVisible(1);
            oc.setStatus(1);
            menuMapper.insert(oc);
            log.info("「机构目录选用」已建于「机构维护管理」下(org-catalog)");
        } else if (!orgMgmt.getId().equals(oc.getParentId())) {
            oc.setParentId(orgMgmt.getId());
            oc.setSortNo(1);
            menuMapper.updateById(oc);
            log.info("「机构目录选用」已从顶级重挂到「机构维护管理」下(org-catalog)");
        }
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
     * 幂等绑定收费结算菜单组件(2026-09 收费前端上线): 待收费/收费结算记录/退费记录绑定组件并清除建设中标记。
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
     * 幂等迁移: 旧"目录对照"(dict-map/DictMap, 仅收费项目单条对照)升级为"医保目录对照"(catalog-map/CatalogMap)。
     * 既有库 seedMenus 表非空即跳过, 故在此按 menu_key 原地改名+换组件, 保证老库菜单同步。
     */
    private void migrateDictMapToCatalogMap() {
        SysMenu old = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "dict-map").last("LIMIT 1"));
        if (old == null) {
            return;
        }
        old.setMenuKey("catalog-map");
        old.setMenuName("医保目录对照");
        old.setComp("CatalogMap");
        menuMapper.updateById(old);
        log.info("目录对照菜单已迁移为医保目录对照(catalog-map/CatalogMap)");
    }

    /**
     * 幂等构建"医共体管理 → 基础数据"三级分组(2026-09 菜单调整):
     * 在 platform(医共体管理)目录下新建 basedata(基础数据)子目录, 并将 catalog-map
     * (原"三目录医保对照"更名"医保目录对照")从"医保字典"目录、community-dict(医共体字典)从顶级
     * 一并重挂到 basedata 下(两者同级)。只改 parent_id/sort_no 与 menu_name, 菜单 id 不变,
     * sys_role_menu 按 id 授权全部保持; 子集角色由 SysMenuService.treeByIds 自动补全
     * basedata→platform 祖先目录, 无需补授权。非牵头机构排除 community-dict 按 key 递归剔除, 不受影响。
     * 须在 mergeBasedataIntoPlatform(删老顶级 basedata)、migrateDictMapToCatalogMap、ensureCommunityDictMenus 之后执行。
     */
    private void ensureBasedataMenuUnderPlatform() {
        SysMenu platform = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "platform").last("LIMIT 1"));
        if (platform == null) {
            return;
        }
        // 复活可能被 mergeBasedataIntoPlatform 逻辑删除的 basedata 墓碑行: sys_menu.uk_menu_key 为物理唯一索引,
        // 与逻辑删除位 deleted 无关, 若直接 insert 新 basedata 会触发 Duplicate entry 并中断本次初始化。
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE sys_menu SET deleted = 0 WHERE menu_key = 'basedata' AND deleted = 1");
        } catch (Exception e) {
            log.warn("basedata 墓碑行复活跳过: {}", e.getMessage());
        }
        int maxSort = 0;
        for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", platform.getId()))) {
            if ("basedata".equals(c.getMenuKey())) {
                continue;
            }
            maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
        }
        SysMenu basedata = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "basedata").last("LIMIT 1"));
        if (basedata == null) {
            basedata = new SysMenu();
            basedata.setParentId(platform.getId());
            basedata.setMenuKey("basedata");
            basedata.setMenuName("基础数据");
            basedata.setMenuType(1);
            basedata.setSortNo(maxSort + 1);
            basedata.setVisible(1);
            basedata.setStatus(1);
            menuMapper.insert(basedata);
            log.info("「基础数据」子目录已建于「医共体管理」下(basedata)");
        } else {
            basedata.setParentId(platform.getId());
            basedata.setMenuType(1);
            basedata.setSortNo(maxSort + 1);
            menuMapper.updateById(basedata);
        }
        // catalog-map: 更名"医保目录对照"并移入 basedata(首位)
        SysMenu catalog = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "catalog-map").last("LIMIT 1"));
        if (catalog != null) {
            catalog.setParentId(basedata.getId());
            catalog.setMenuName("医保目录对照");
            catalog.setSortNo(1);
            menuMapper.updateById(catalog);
        }
        // community-dict: 从顶级移入 basedata(与医保目录对照同级)
        SysMenu community = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "community-dict").last("LIMIT 1"));
        if (community != null) {
            community.setParentId(basedata.getId());
            community.setSortNo(2);
            menuMapper.updateById(community);
        }
        log.info("「医保目录对照」「医共体字典」已归入「医共体管理 → 基础数据」");
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
            ws.setMenuName("门诊医生工作站");
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
     * 幂等补种"药房定价"菜单(三期: 药房维度差异化定价, 覆盖价维护未覆盖回落目录零售价)。既有库
     * seedMenus 表非空即跳过, 故在此按 menu_key 判存补种(挂 pharmacy 目录, sort_no 续接), 并将 id
     * 回填 menuIds 供角色补授权(ensureBizRoleGrants 给药师)。
     */
    private void ensurePharmaPriceMenu(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "pharmacy", "price-mgr", "药房定价", "PharmacyPriceManage");
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
     * 幂等补种门诊挂号收费目录收费域新菜单(2026-09 发票管理/收费统计上线): 发票管理(InvoiceManage)与
     * 收费统计(ChargeReport)。既有库 seedMenus 表非空即跳过, 故在此按 menu_key 判存补种
     * (挂 outpatient 目录, sort_no 续接), 并将 id 回填 menuIds 供角色补授权(ensureBizRoleGrants 给收费员)。
     */
    private void ensureNewChargeMenus(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "outpatient", "invoice-mgr", "发票管理", "InvoiceManage");
        ensureChildMenu(menuIds, "outpatient", "charge-rpt", "收费统计", "ChargeReport");
    }
    
    /**
     * 幂等补种"收费工作站"菜单(2026-09 同屏化改造: 待收费/结算/退费/收据票据一屏完成, 旧菜单保留):
     * 挂 outpatient(门诊挂号收费)目录收费子块首位(紧随挂号明细, 新库种子已同此序)。
     */
    private void ensureChargeWorkstationMenu(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "outpatient", "charge-ws", "门诊收费", "ChargeWorkstation");
        SysMenu ws = menuIds.get("charge-ws") == null ? null : menuMapper.selectById(menuIds.get("charge-ws"));
        SysMenu dir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "outpatient").last("LIMIT 1"));
        if (ws == null || dir == null || ws.getSortNo() == null || ws.getSortNo() == 0) {
            return;
        }
        // 收费子块首位对齐: 若 charge-todo 已排在工作站之前, 工作站前移一位(仅相邻交换, 不动挂号子块)
        SysMenu todo = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "charge-todo").last("LIMIT 1"));
        if (todo != null && dir.getId().equals(todo.getParentId())
                && todo.getSortNo() != null && ws.getSortNo() > todo.getSortNo()) {
            int wsSort = ws.getSortNo();
            SysMenu upd = new SysMenu();
            upd.setId(ws.getId());
            upd.setSortNo(todo.getSortNo());
            menuMapper.updateById(upd);
            SysMenu updTodo = new SysMenu();
            updTodo.setId(todo.getId());
            updTodo.setSortNo(wsSort);
            menuMapper.updateById(updTodo);
            log.info("收费工作站菜单已排至收费子块首位(charge-ws, sort_no={})", todo.getSortNo());
        }
        // 幂等更名(2026-09): "收费工作站"改为"门诊收费"; 仅当现名仍为旧名时更新, 菜单 id 不变、角色授权不受影响
        if ("收费工作站".equals(ws.getMenuName())) {
            SysMenu rename = new SysMenu();
            rename.setId(ws.getId());
            rename.setMenuName("门诊收费");
            menuMapper.updateById(rename);
            log.info("收费工作站菜单已更名为门诊收费(charge-ws)");
        }
    }

    /**
     * 幂等将"收费结算台"目录(charge)并入"门诊挂号台"目录并更名为"门诊挂号收费"(2026-09 两目录合并):
     * 收费子菜单按原 sort_no 顺序重挂 outpatient 目录且 sort_no 续接挂号子块之后, 删除 charge 目录及其
     * role_menu 残留授权。子菜单 id 不变, 角色授权不受影响: SysMenuService.treeByIds 会自动补全命中
     * 菜单的祖先目录(新父目录 outpatient 随挂号子菜单已授权); CASHIER 角色由 ensureNewChargeMenus 等
     * 按子菜单 key 补授权, 不依赖 charge 目录行。幂等守卫: charge 目录已不存在时仅确保目录名已更新。
     */
    private void mergeChargeIntoOutpatient() {
        SysMenu outpatient = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "outpatient").last("LIMIT 1"));
        if (outpatient == null) {
            return;
        }
        SysMenu charge = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "charge").last("LIMIT 1"));
        if (charge == null) {
            // 新库种子已合并; 老库若目录已删但名称未更新则补改名
            if ("门诊挂号台".equals(outpatient.getMenuName())) {
                outpatient.setMenuName("门诊挂号收费");
                menuMapper.updateById(outpatient);
                log.info("「门诊挂号台」目录已更名为「门诊挂号收费」");
            }
            return;
        }
        int maxSort = 0;
        for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", outpatient.getId()))) {
            maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
        }
        List<SysMenu> children = menuMapper.selectList(new QueryWrapper<SysMenu>()
                .eq("parent_id", charge.getId()).orderByAsc("sort_no", "id"));
        for (SysMenu c : children) {
            c.setParentId(outpatient.getId());
            c.setSortNo(++maxSort);
            menuMapper.updateById(c);
        }
        outpatient.setMenuName("门诊挂号收费");
        menuMapper.updateById(outpatient);
        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("menu_id", charge.getId()));
        menuMapper.deleteById(charge.getId());
        log.info("「收费结算台」目录已并入「门诊挂号台」并更名为「门诊挂号收费」(迁移子菜单 {} 个)", children.size());
    }

    /**
     * 幂等将"医保字典"(yb-dict)与"标准字典"(std-dict)两个顶级目录移入"医共体管理"(platform)
     * (2026-09 菜单调整): 只改 parent_id/sort_no, 目录与子菜单 id 不变, sys_role_menu 按 id 授权全部
     * 保持; sort_no 取 101/102 大于 platform 全部直接子项(含基础数据子目录), 排其之后。超管精简菜单
     * 不受影响(SysMenuService.treeOnlyTopKeys 已改为任意层级递归命中, 标准字典仍作顶级展示)。
     */
    private void mergeDictDirsIntoPlatform() {
        SysMenu platform = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "platform").last("LIMIT 1"));
        if (platform == null) {
            return;
        }
        boolean moved = false;
        moved |= reparentDirUnderPlatform("yb-dict", platform.getId(), 101);
        moved |= reparentDirUnderPlatform("std-dict", platform.getId(), 102);
        if (moved) {
            log.info("「医保字典」「标准字典」目录已移入「医共体管理」");
        }
    }

    /** 幂等重挂指定目录到目标父目录(key/名称不变, 仅 parent_id/sort_no 变化时更新); 返回是否发生迁移 */
    private boolean reparentDirUnderPlatform(String key, long targetParentId, int sortNo) {
        SysMenu dirMenu = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", key).last("LIMIT 1"));
        if (dirMenu == null || dirMenu.getParentId() == null
                || (dirMenu.getParentId() == targetParentId && dirMenu.getSortNo() != null && dirMenu.getSortNo() == sortNo)) {
            return false;
        }
        dirMenu.setParentId(targetParentId);
        dirMenu.setSortNo(sortNo);
        menuMapper.updateById(dirMenu);
        return true;
    }

    /**
     * 幂等更名三个业务目录(2026-09): 医生站→门诊医生站, 药房→药房系统, 药库→药库系统。
     * 仅当现名仍为旧名时更新(不覆盖自定义名), 目录 id 不变、sys_role_menu 按 id 授权全部保持;
     * 医生站目录下唯一叶子"门诊医生站"与目录同名属预期(用户指定目录新名), 叶子行不动。
     */
    private void renameBizDirs() {
        renameMenuIfOldName("doctor", "医生站", "门诊医生站");
        renameMenuIfOldName("pharmacy", "药房", "药房系统");
        renameMenuIfOldName("warehouse", "药库", "药库系统");
    }

    /**
     * 幂等更名“菜单名与页面实际内容/页头标题不一致”的菜单(2026-09, 统一收口在此, 新项直接加一行):
     * charge-setl “医保结算”→“收费结算记录”(页实展收费与结算记录列表而非发起医保结算);
     * charge-refund “退费”→“退费记录”(页实展 TF 退费单列表; 退费动作在「门诊收费」工作站完成);
     * doctor-ws “门诊医生站”→“门诊医生工作站”(与其页头 dw-header-title 一致; 顺带消除与父目录 doctor 同名)。
     * 菜单 key/组件/id 均不变, sys_role_menu 按 id 授权自动保持。
     */
    private void renameMenusToPageTitle() {
        renameMenuIfOldName("charge-setl", "医保结算", "收费结算记录");
        renameMenuIfOldName("charge-refund", "退费", "退费记录");
        renameMenuIfOldName("doctor-ws", "门诊医生站", "门诊医生工作站");
    }

    private void renameMenuIfOldName(String key, String oldName, String newName) {
        SysMenu dirMenu = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", key).last("LIMIT 1"));
        if (dirMenu == null || !oldName.equals(dirMenu.getMenuName())) {
            return;
        }
        SysMenu upd = new SysMenu();
        upd.setId(dirMenu.getId());
        upd.setMenuName(newName);
        menuMapper.updateById(upd);
        log.info("菜单「{}」已更名为「{}」({})", oldName, newName, key);
    }

    /**
     * 幂等将"查询报表"目录(report)下的结算记录/门诊日结移入"门诊挂号收费"(outpatient)并删除已空的
     * report 目录(2026-09 菜单调整, 与 charge 目录并挂号台同款处理): 子菜单按原 sort_no 序重挂且续接
     * outpatient 现有子项之后(即排收费子块尾部), 子菜单 id 不变故 sys_role_menu 按 id 授权全部保持
     * (收费员 CASHIER 含 rpt-setl/rpt-daily, 新父目录由 treeByIds 自动补全祖先); report 目录行及其
     * role_menu 残留授权删除, 仅当确认已无剩余可见子项时才删(防误删自定义子菜单)。新库 report 目录
     * 不再生成, 方法命中 report==null 即跳过。
     */
    private void moveReportIntoOutpatient() {
        SysMenu outpatient = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "outpatient").last("LIMIT 1"));
        SysMenu report = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "report").last("LIMIT 1"));
        if (outpatient == null || report == null) {
            return;
        }
        int maxSort = 0;
        for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", outpatient.getId()))) {
            maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
        }
        List<SysMenu> children = menuMapper.selectList(new QueryWrapper<SysMenu>()
                .eq("parent_id", report.getId()).orderByAsc("sort_no", "id"));
        for (SysMenu c : children) {
            c.setParentId(outpatient.getId());
            c.setSortNo(++maxSort);
            menuMapper.updateById(c);
        }
        // 重挂后复查: 仍有子项(异常数据/自定义项)则保留目录, 不强行删
        Long left = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("parent_id", report.getId()));
        if (left != null && left > 0) {
            log.warn("「查询报表」目录仍有 {} 个子项, 保留目录不删", left);
            return;
        }
        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("menu_id", report.getId()));
        menuMapper.deleteById(report.getId());
        log.info("「结算记录」「门诊日结」已移入「门诊挂号收费」并删除空的「查询报表」目录(迁移子菜单 {} 个)", children.size());
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

    /* ===================== 3c. 护士站/治疗/医技三模块基座菜单(2026-09) ===================== */

    /**
     * 幂等补种门诊护士站目录及5叶子(2026-09 三模块基座): 既有库 seedMenus 表非空即跳过,
     * 故在此按 menu_key 判存补种(目录先行, 叶子 sort_no 续接目录现有子项最大值), 并将 id 回填
     * menuIds 供角色补授权(ensureBizRoleGrants 给护士全部5项; 医生补授 nurse-allergy)。
     */
    private void ensureNurseStationMenus(Map<String, Long> menuIds) {
        ensureDirMenu(menuIds, "nurse-station-group", "门诊护士站");
        ensureChildMenu(menuIds, "nurse-station-group", "nurse-pending", "待执行医嘱", "NursePending");
        ensureChildMenu(menuIds, "nurse-station-group", "nurse-skin-test", "皮试管理", "NurseSkinTest");
        ensureChildMenu(menuIds, "nurse-station-group", "nurse-infusion", "输液管理", "NurseInfusion");
        ensureChildMenu(menuIds, "nurse-station-group", "nurse-allergy", "过敏档案", "NurseAllergy");
        ensureChildMenu(menuIds, "nurse-station-group", "nurse-exec-log", "执行记录查询", "NurseExecLog");
    }

    /**
     * 幂等补种治疗管理目录及4叶子(2026-09 三模块基座): 治疗师专属工作站, 老库同款判存补种,
     * id 回填 menuIds 供 ensureBizRoleGrants 给治疗师补授全部4项。
     */
    private void ensureTreatmentMenus(Map<String, Long> menuIds) {
        ensureDirMenu(menuIds, "treatment-group", "治疗管理");
        ensureChildMenu(menuIds, "treatment-group", "treatment-pending", "待执行治疗", "TreatmentPending");
        ensureChildMenu(menuIds, "treatment-group", "treatment-plan", "疗程管理", "TreatmentPlan");
        ensureChildMenu(menuIds, "treatment-group", "treatment-equip", "设备管理", "TreatmentEquipment");
        ensureChildMenu(menuIds, "treatment-group", "treatment-log", "治疗记录查询", "TreatmentLog");
    }

    /**
     * 幂等补种医技管理目录及5叶子(2026-09 三模块基座): 医技人员专属工作站, 老库同款判存补种,
     * id 回填 menuIds 供 ensureBizRoleGrants 给医技人员补授全部5项; 医生补授 medtech-report-query。
     */
    private void ensureMedtechMenus(Map<String, Long> menuIds) {
        ensureDirMenu(menuIds, "medtech-group", "医技管理");
        ensureChildMenu(menuIds, "medtech-group", "medtech-specimen", "标本管理", "MedtechSpecimen");
        ensureChildMenu(menuIds, "medtech-group", "medtech-report", "报告工作站", "MedtechReport");
        ensureChildMenu(menuIds, "medtech-group", "medtech-critical", "危急值管理", "MedtechCritical");
        ensureChildMenu(menuIds, "medtech-group", "medtech-critical-rule", "危急值规则", "MedtechCriticalRule");
        ensureChildMenu(menuIds, "medtech-group", "medtech-report-query", "报告查询", "MedtechReportQuery");
    }

    /**
     * 幂等更名三模块基座菜单(2026-09 集成联调, 菜单名与集成规格/静态兑底菜单对齐):
     * nurse-allergy "过敏登记"→"过敏档案"; nurse-exec-log "执行记录"→"执行记录查询";
     * treatment-plan "治疗计划"→"疗程管理"; treatment-equip "治疗设备"→"设备管理";
     * treatment-log "治疗记录"→"治疗记录查询"; medtech-report "报告书写"→"报告工作站"。
     * 菜单 key/组件/id 均不变, sys_role_menu 按 id 授权自动保持。
     */
    private void renameNurseTreatmentMedtechMenus() {
        renameMenuIfOldName("nurse-allergy", "过敏登记", "过敏档案");
        renameMenuIfOldName("nurse-exec-log", "执行记录", "执行记录查询");
        renameMenuIfOldName("treatment-plan", "治疗计划", "疗程管理");
        renameMenuIfOldName("treatment-equip", "治疗设备", "设备管理");
        renameMenuIfOldName("treatment-log", "治疗记录", "治疗记录查询");
        renameMenuIfOldName("medtech-report", "报告书写", "报告工作站");
    }

    /**
     * 幂等补种"系统参数"菜单(2026-09 四级作用域参数配置): 挂医共体管理(platform)目录,
     * 菜单管理之后。既有库 seedMenus 表非空即跳过, 故在此按 menu_key 判存补种,
     * 并将 id 回填 menuIds。角色可见性: ADMIN/ORG_ADMIN/SUPER_ADMIN 均 allMenus=1 自动包含
     * (SysRoleService 的医院端/机构管理员排除集均不含 system-param), 无需 ensureBizRoleGrants 补授。
     */
    private void ensureSystemParamMenu(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "platform", "system-param", "系统参数", "SystemParam");
    }

    /**
     * 幂等确保顶级目录存在(新库种子已含, 老库补种): 按 menu_key 判存, 不存在则建顶级目录
     * (menu_type=1, sort_no 续接现有顶级最大值), 并将 id 回填 menuIds。先复活墓碑行
     * (sys_menu.uk_menu_key 为物理唯一索引与 deleted 位无关, 见 ensureOrgMgmtDir 同款坑位)。
     */
    private void ensureDirMenu(Map<String, Long> menuIds, String key, String name) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE sys_menu SET deleted = 0 WHERE menu_key = '" + key + "' AND deleted = 1");
        } catch (Exception e) {
            log.warn("{} 墓碑行复活跳过: {}", key, e.getMessage());
        }
        SysMenu dir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", key).last("LIMIT 1"));
        if (dir == null) {
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", 0L))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            dir = new SysMenu();
            dir.setParentId(0L);
            dir.setMenuKey(key);
            dir.setMenuName(name);
            dir.setMenuType(1);
            dir.setSortNo(maxSort + 1);
            dir.setVisible(1);
            dir.setStatus(1);
            menuMapper.insert(dir);
            log.info("顶级目录「{}」已新建({})", name, key);
        }
        menuIds.put(key, dir.getId());
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
