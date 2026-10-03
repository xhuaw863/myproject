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
            ensureSupplierDictMenu();
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
            ensureWindowMenus(menuIds);
            ensureRxAuditMenu(menuIds);
            ensurePurchaseMenus(menuIds);
            regroupWarehouseMenus(menuIds);
            consolidatePharmacyMenus(menuIds);
            migrateDictMapToCatalogMap();
            ensureBasedataMenuUnderPlatform();
            ensureDiagMapMenu(menuIds);
            moveSupplierDictToBasedata();
            ensureFeePayDictMenu();
            ensureMedTypeDictMenu();
            activateReportMenus();
            mergeDictDirsIntoPlatform();
            renameBizDirs();
            renameMenusToPageTitle();
            moveReportIntoOutpatient();
            mergeDoctorMenus(menuIds);
            ensureDoctorWorklogMenu(menuIds);
            ensureMedicalTemplateMenu(menuIds);
            ensureEmrDesignerMenu(menuIds);
            ensureEmrQualityMenus(menuIds);
            ensureNurseStationMenus(menuIds);
            ensureTreatmentMenus(menuIds);
            ensureMedtechMenus(menuIds);
            renameNurseTreatmentMedtechMenus();
            ensureSystemParamMenu(menuIds);
            ensureVerifyConsoleMenu(menuIds);
            ensureUploadCenterMenu(menuIds);
            mergeInpatientMenus(menuIds);
            ensureMedicalRecordMenus(menuIds);
            Map<String, Long> roleIds = seedGlobalRoles();
            ensureOrgAdminRole();
            ensureTherapistTechnicianRoles(roleIds);
            ensureMedicalRecordRoles(roleIds);
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
        ids.put("medical-template", menuK("medical-template", "病历模板管理", "MedicalTemplateManage", null, g6, ++sort[0]));
        ids.put("emr-designer", menuK("emr-designer", "病历模板设计器", "EmrTemplateDesigner", null, g6, ++sort[0]));
        // 病历质控与数据元(Phase C 2026-10: 质控规则维护/病历检索上报/质控评分看板, 跨scope结构化病历二次利用与质控闭环;
        // P1a 2026-10 病历管理前置两菜单: 数据集管理/结构化模板设计器, comp 与 HIS.views 注册键同值(小写短横线特例))
        long gEmrQ = dir("emr-quality", "病历质控与数据元", 0L, ++sort[0]);
        ids.put("emr-dataset", menuK("emr-dataset", "数据集管理", "emr-dataset", null, gEmrQ, ++sort[0]));
        ids.put("emr-template-designer", menuK("emr-template-designer", "模板设计器(结构化)", "emr-template-designer", null, gEmrQ, ++sort[0]));
        // P1c(2026-10) 患者全景时间线: 门诊+住院就诊事件统一时间轴, comp 与 HIS.views 注册键同值
        ids.put("emr-patient-timeline", menuK("emr-patient-timeline", "患者全景时间线", "emr-patient-timeline", null, gEmrQ, ++sort[0]));
        ids.put("emr-quality-rule", menuK("emr-quality-rule", "质控规则维护", "EmrQualityRuleManage", null, gEmrQ, ++sort[0]));
        ids.put("emr-element-search", menuK("emr-element-search", "病历检索上报", "EmrElementSearch", null, gEmrQ, ++sort[0]));
        ids.put("emr-quality-board", menuK("emr-quality-board", "质控评分看板", "EmrQualityBoard", null, gEmrQ, ++sort[0]));
        // P5b(2026-10 质控工作台): 六模块一体(标准/时效/内涵/查询/人工质控/统计)
        ids.put("emr-quality-console", menuK("emr-quality-console", "质控工作台", "EmrQualityConsole", null, gEmrQ, ++sort[0]));
                // P2(病历审计日志): 操作审计全量分页检索
        ids.put("emr-audit-log", menuK("emr-audit-log", "病历审计日志", "EmrAuditLog", null, gEmrQ, ++sort[0]));
        // P7a(2026-10 病历归档工作台): 归档全流程管控(待归档/归档管理/封存管理/统计/Webhook订阅, comp=EmrArchive)
        ids.put("emr-archive", menuK("emr-archive", "归档工作台", "EmrArchive", null, gEmrQ, ++sort[0]));
        // P7b-3(2026-10 病历等级自评): 等级徽章+八维度SVG雷达+维度明细卡(comp=EmrLevelAssess, DOCTOR可查看)
        ids.put("emr-level-assess", menuK("emr-level-assess", "等级自评", "EmrLevelAssess", null, gEmrQ, ++sort[0]));
        // 药房(2026-09 P1d 交付: 待发药/调剂发药/退药前端已上线; P2 药房管理/药房统计上线)
        long g7 = dir("pharmacy", "药房系统", 0L, ++sort[0]);
        // 药房系统按业务域分组(2026-11 整合: 门诊发药/住院发药/药房运营与追溯/统计查询; 对齐药库五域做法;
        // 原独立顶级"住院药师站"(inp-pharm-group)4 项并入"住院发药"子域, 存量库由 consolidatePharmacyMenus 迁移)
        long gPhOutp = dir("ph-outp", "门诊发药", g7, ++sort[0]);
        ids.put("dispense-todo", menuK("dispense-todo", "待发药", "DispenseTodo", null, gPhOutp, ++sort[0]));
        ids.put("window-workstation", menuK("window-workstation", "发药工作站", "WindowWorkstation", null, gPhOutp, ++sort[0]));
        ids.put("dispense", menuK("dispense", "调剂发药", "DispenseRecord", null, gPhOutp, ++sort[0]));
        ids.put("rx-audit", menuK("rx-audit", "处方审核", "OutpRxAudit", null, gPhOutp, ++sort[0]));
        ids.put("drug-return", menuK("drug-return", "退药", "DrugReturn", null, gPhOutp, ++sort[0]));
        long gPhInp = dir("ph-inp", "住院发药", g7, ++sort[0]);
        ids.put("pharm-station", menuK("pharm-station", "药师审核", "pharm-station", null, gPhInp, ++sort[0]));
        ids.put("inp-dispense-work", menuK("inp-dispense-work", "住院发药工作台", "InpDispenseWork", null, gPhInp, ++sort[0]));
        ids.put("inp-discharge-pickup", menuK("inp-discharge-pickup", "出院带药核发", "InpDischargePickup", null, gPhInp, ++sort[0]));
        ids.put("inp-dispense-history", menuK("inp-dispense-history", "住院发药历史", "InpDispenseHistory", null, gPhInp, ++sort[0]));
        long gPhOps = dir("ph-ops", "药房运营与追溯", g7, ++sort[0]);
        ids.put("pharmacy-def", menuK("pharmacy-def", "药房管理", "PharmacyDef", null, gPhOps, ++sort[0]));
        ids.put("price-mgr", menuK("price-mgr", "药房定价", "PharmacyPriceManage", null, gPhOps, ++sort[0]));
        ids.put("req-mgr", menuK("req-mgr", "药品请领", "RequisitionManage", null, gPhOps, ++sort[0]));
        ids.put("pharmacy-window", menuK("pharmacy-window", "发药窗口", "PharmacyWindowManage", null, gPhOps, ++sort[0]));
        ids.put("window-dept-rule", menuK("window-dept-rule", "科室定向窗口", "WindowDeptRule", null, gPhOps, ++sort[0]));
        ids.put("pharmacy-cross", menuK("pharmacy-cross", "跨药房配置", "PharmacyCrossConfig", null, gPhOps, ++sort[0]));
        ids.put("trace-code", menuK("trace-code", "药品追溯码", "TraceCodeManage", null, gPhOps, ++sort[0]));
        long gPhStat = dir("ph-stat", "统计查询", g7, ++sort[0]);
        ids.put("pharmacy-rpt", menuK("pharmacy-rpt", "药房统计", "PharmacyReport", null, gPhStat, ++sort[0]));
        // 统计查询增强(2026-11): 药品消耗分析/医保合规分析/处方与退药质量, 只读聚合 + EasyExcel 导出
        ids.put("stat-usage", menuK("stat-usage", "药品消耗分析", "PharmacyUsageStat", null, gPhStat, ++sort[0]));
        ids.put("stat-yb", menuK("stat-yb", "医保合规分析", "PharmacyYbStat", null, gPhStat, ++sort[0]));
        ids.put("stat-quality", menuK("stat-quality", "处方与退药质量", "PharmacyQualityStat", null, gPhStat, ++sort[0]));
        // 药库(采购入库/出库管理/库存流水已上线; P2 药品目录/盘点绑定组件, 药库管理/药库统计上线)
        long g8 = dir("warehouse", "药库系统", 0L, ++sort[0]);
        // 药库系统按业务域分组(2026-10 整合: 采购/库存作业/财务结算/账簿统计/养护管理; 采购入库与增强版去重, 账簿查询并入进销存台账)
        long gWhBuy = dir("wh-purchase", "药品采购", g8, ++sort[0]);
        ids.put("supplier-mgr", menuK("supplier-mgr", "供应商管理", "SupplierManage", null, gWhBuy, ++sort[0]));
        ids.put("purchase-rule", menuK("purchase-rule", "采购规则", "PurchaseRule", null, gWhBuy, ++sort[0]));
        ids.put("purchase-plan", menuK("purchase-plan", "采购计划", "PurchasePlan", null, gWhBuy, ++sort[0]));
        ids.put("purchase-order", menuK("purchase-order", "采购订单", "PurchaseOrder", null, gWhBuy, ++sort[0]));
        long gWhOps = dir("wh-stock-ops", "库存作业", g8, ++sort[0]);
        ids.put("wh-in", menuK("wh-in", "采购入库", "StockInManage", null, gWhOps, ++sort[0]));
        ids.put("wh-out", menuK("wh-out", "出库管理", "StockOutManage", null, gWhOps, ++sort[0]));
        ids.put("trf-mgr", menuK("trf-mgr", "库存调拨", "TransferManage", null, gWhOps, ++sort[0]));
        ids.put("wh-check", menuK("wh-check", "盘点", "StockCheck", null, gWhOps, ++sort[0]));
        ids.put("wh-stock", menuK("wh-stock", "库存/流水", "DrugStock", null, gWhOps, ++sort[0]));
        ids.put("warehouse-def", menuK("warehouse-def", "药库管理", "WarehouseDef", null, gWhOps, ++sort[0]));
        ids.put("wh-drug", menuK("wh-drug", "药品目录", "DrugCatalogView", null, gWhOps, ++sort[0]));
        long gWhFin = dir("wh-finance", "财务结算", g8, ++sort[0]);
        ids.put("stock-accept", menuK("stock-accept", "财务验收", "StockAccept", null, gWhFin, ++sort[0]));
        ids.put("supplier-pay", menuK("supplier-pay", "供应商付款", "SupplierPayment", null, gWhFin, ++sort[0]));
        ids.put("payable-rpt", menuK("payable-rpt", "应付账款", "PayableReport", null, gWhFin, ++sort[0]));
        ids.put("price-adjust", menuK("price-adjust", "药品调价", "PriceAdjust", null, gWhFin, ++sort[0]));
        ids.put("month-end", menuK("month-end", "库房月结", "MonthEnd", null, gWhFin, ++sort[0]));
        long gWhBook = dir("wh-book", "账簿统计", g8, ++sort[0]);
        ids.put("stock-ledger", menuK("stock-ledger", "进销存台账", "DrugLedger", null, gWhBook, ++sort[0]));
        ids.put("warehouse-rpt", menuK("warehouse-rpt", "药库统计", "WarehouseReport", null, gWhBook, ++sort[0]));
        long gWhMaint = dir("wh-maint", "养护管理", g8, ++sort[0]);
        ids.put("drug-maint", menuK("drug-maint", "药品养护", "DrugMaintenance", null, gWhMaint, ++sort[0]));
        ids.put("maint-template", menuK("maint-template", "养护模板", "MaintenanceTemplate", null, gWhMaint, ++sort[0]));
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
        // 病案统计(2026-11 P0 病案统计科侧: 病案分配/首页编目/质量审核, comp 与 HIS.views 注册键同值)
        long gMr = dir("mr", "病案统计", 0L, ++sort[0]);
        ids.put("mr-assign", menuK("mr-assign", "病案分配", "MrAssign", null, gMr, ++sort[0]));
        ids.put("mr-catalog", menuK("mr-catalog", "首页编目", "MrCatalog", null, gMr, ++sort[0]));
        ids.put("mr-review", menuK("mr-review", "质量审核", "MrReview", null, gMr, ++sort[0]));
        ids.put("mr-recall", menuK("mr-recall", "病案收回", "MrRecall", null, gMr, ++sort[0]));
        ids.put("mr-borrow", menuK("mr-borrow", "病案借阅", "MrBorrow", null, gMr, ++sort[0]));
        ids.put("mr-annotation", menuK("mr-annotation", "批注反馈", "MrAnnotation", null, gMr, ++sort[0]));
        ids.put("mr-search", menuK("mr-search", "检索查询", "MrSearch", null, gMr, ++sort[0]));
        // 病案统计 P2(2026-11 收尾): 工作量录入与逻辑审核 / 系统维护字典 / 报表统计 / 上报三段闭环
        ids.put("mr-workload", menuK("mr-workload", "工作量统计", "MrWorkload", null, gMr, ++sort[0]));
        ids.put("mr-maintain", menuK("mr-maintain", "系统维护字典", "MrMaintain", null, gMr, ++sort[0]));
        ids.put("mr-report", menuK("mr-report", "报表统计", "MrReport", null, gMr, ++sort[0]));
        ids.put("mr-submit", menuK("mr-submit", "上报闭环", "MrSubmit", null, gMr, ++sort[0]));
                // P3 集成阶段: DRG/DIP 预分组 + 质控前后对比
        ids.put("mr-drg", menuK("mr-drg", "DRG分组对比", "MrDrg", null, gMr, ++sort[0]));
        ids.put("mr-quality-diff", menuK("mr-quality-diff", "质控前后对比", "MrQualityDiff", null, gMr, ++sort[0]));
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
     *  doctor-queue/doctor-work=候诊列表/接诊工作台(合并为门诊医生工作站 doctor-ws, 2026-09 合并);
     *  purchase-stock-in=采购入库增强版(功能已并入原采购入库 wh-in/StockInManage, 2026-10 去重下线);
     *  stock-book=账簿查询(进价/零售口径与财务/实物账已并入进销存台账 stock-ledger/DrugLedger, 2026-10 去重下线) */
    private static final String[] RETIRED_MENU_KEYS = {"charge-item", "doctor-queue", "doctor-work", "purchase-stock-in", "stock-book"};

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
    private static final String[] MERGED_AWAY_DIR_KEYS = {"charge", "report", "system", "inp-pharm-group"};

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
        ids.put(Roles.MR_INPUT, role(Roles.MR_INPUT, "病案录入组", 0, "病案首页信息录入/明细维护"));
        ids.put(Roles.MR_CATALOG, role(Roles.MR_CATALOG, "病案编目组", 0, "病案分配/首页编目定稿"));
        ids.put(Roles.MR_REVIEW, role(Roles.MR_REVIEW, "病案质控组", 0, "首页质量审核/确认锁定"));
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
        grants.put(Roles.DOCTOR, new String[]{"dashboard", "doctor-ws", "patient", "doctor-worklog", "medical-template", "emr-designer", "emr-template-designer", "emr-patient-timeline", "emr-element-search", "emr-quality-board", "emr-quality-console", "emr-audit-log", "emr-archive", "emr-level-assess", "nurse-allergy", "medtech-report-query",
                // 住院医生站(2026-09 住院模块): 医嘱/诊断/病历/患者概览入口共用工作站组件
                "inp-doctor-ws", "inp-order-manage", "inp-order-template", "inp-diagnosis", "inp-med-record", "inp-patient-overview",
                // 临床路径/手术麻醉(2026-09 集成): 路径模板管理 + 手术管理 + 麻醉记录; 手麻P0: 手术申请管理
                "pathway-template", "pathway-stats", "surgery-manage", "surgery-apply", "anesthesia-record",
                // 住院报表(2026-09 报表/打印模块): 报表中心(只读)
                "inp-report",
                // 手麻P2: 手术统计报表(只读)
                "surgery-report"});
        grants.put(Roles.PHARMACIST, new String[]{"dashboard", "dispense-todo", "dispense", "drug-return", "pharmacy-def", "pharmacy-rpt", "wh-stock", "wh-in", "wh-out", "warehouse-def", "warehouse-rpt", "wh-check", "req-mgr", "trf-mgr", "price-adjust", "stock-ledger", "trace-code", "price-mgr", "pharmacy-window", "window-workstation", "window-dept-rule", "pharmacy-cross", "rx-audit", "supplier-mgr", "purchase-rule", "purchase-plan", "purchase-order", "stock-accept", "supplier-pay", "payable-rpt", "drug-maint", "maint-template", "month-end",
                // 住院药师站(T41 审核 + P4 发药增强)
                "pharm-station", "inp-dispense-work", "inp-discharge-pickup", "inp-dispense-history",
                // 统计查询增强(2026-11): 药品消耗分析/医保合规分析/处方与退药质量
                "stat-usage", "stat-yb", "stat-quality"});
        grants.put(Roles.CASHIER, new String[]{"dashboard", "charge-ws", "charge-todo", "charge-setl", "charge-refund", "invoice-mgr", "charge-rpt", "rpt-setl", "rpt-daily",
                // 住院登记结算(2026-09 住院模块): 入院登记/在院患者/床位/预交金/费用清单/出院结算/住院日报
                "inp-admission", "inp-patient-list", "inp-bed-manage", "inp-deposit", "inp-charge-list", "inp-settle", "inp-daily-summary",
                // 手麻记费(2026-09 集成)
                "surgery-fee",
                // 住院报表(2026-09 报表/打印模块): 打印管理(日清单/结算单打印)
                "inp-print"});
        grants.put(Roles.NURSE, new String[]{"dashboard", "patient", "nurse-pending", "nurse-skin-test", "nurse-infusion", "nurse-allergy", "nurse-exec-log",
                // 住院护士站(2026-09 住院模块): 医嘱审核/执行/护理记录/交接班/床位一览入口共用工作站组件
                "inp-nurse-ws", "inp-order-audit", "inp-order-exec", "inp-nursing-record", "inp-shift-handover", "inp-bed-overview",
                // 麻醉记录(2026-09 集成, 只读) + 手麻P0: 病区复核角色手术申请管理
                "anesthesia-record", "surgery-apply",
                // 住院报表(2026-09 报表/打印模块): 报表中心(只读)
                "inp-report"});
        grants.put(Roles.THERAPIST, new String[]{"dashboard", "patient", "treatment-pending", "treatment-plan", "treatment-equip", "treatment-log"});
        grants.put(Roles.TECHNICIAN, new String[]{"dashboard", "patient", "medtech-specimen", "medtech-report", "medtech-critical", "medtech-critical-rule", "medtech-report-query"});
        // 病案统计科(P0): 录入组编目首页; 编目组分配+编目; 质控组审核+查阅编目
        grants.put(Roles.MR_INPUT, new String[]{"dashboard", "mr-catalog", "mr-annotation", "mr-search", "mr-workload", "mr-report", "mr-drg", "mr-quality-diff"});
        grants.put(Roles.MR_CATALOG, new String[]{"dashboard", "mr-assign", "mr-catalog", "mr-annotation", "mr-recall", "mr-borrow", "mr-search", "mr-workload", "mr-maintain", "mr-report", "mr-submit", "mr-drg", "mr-quality-diff"});
        grants.put(Roles.MR_REVIEW, new String[]{"dashboard", "mr-catalog", "mr-review", "mr-annotation", "mr-search", "mr-workload", "mr-report", "mr-submit", "mr-drg", "mr-quality-diff"});
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
        grants.put(Roles.PHARMACIST, new String[]{"wh-stock", "wh-in", "wh-out", "wh-check", "pharmacy-def", "pharmacy-rpt", "warehouse-def", "warehouse-rpt", "req-mgr", "trf-mgr", "price-adjust", "stock-ledger", "trace-code", "price-mgr", "pharmacy-window", "window-workstation", "window-dept-rule", "pharmacy-cross", "rx-audit",
                // 住院药师站(T41): 药师审核(药品医嘱审方)
                "pharm-station",
                // P4 住院发药增强: 发药工作台/出院带药核发/历史发药查询
                "inp-dispense-work", "inp-discharge-pickup", "inp-dispense-history",
                // 药品采购全流程(批次A): 供应商/采购规则/采购计划/采购订单
                "supplier-mgr", "purchase-rule", "purchase-plan", "purchase-order", "stock-accept",
                // 付款处理/应付账款(批次C)
                "supplier-pay", "payable-rpt",
                // 养护/模板/月结/账簿(批次D)
                "drug-maint", "maint-template", "month-end",
                // 统计查询增强(2026-11): 药品消耗分析/医保合规分析/处方与退药质量
                "stat-usage", "stat-yb", "stat-quality"});
        grants.put(Roles.CASHIER, new String[]{"rpt-setl", "rpt-daily", "invoice-mgr", "charge-rpt", "charge-ws",
                // 住院登记结算(2026-09 住院模块)
                "inp-admission", "inp-patient-list", "inp-bed-manage", "inp-deposit", "inp-charge-list", "inp-settle", "inp-daily-summary",
                // 手麻记费(2026-09 集成)
                "surgery-fee",
                // 住院报表(2026-09 报表/打印模块): 打印管理(日清单/结算单打印)
                "inp-print"});
        grants.put(Roles.REGISTRAR, new String[]{"reg_stats", "reg_detail"});
        grants.put(Roles.DOCTOR, new String[]{"doctor-ws", "doctor-worklog", "medical-template", "emr-designer", "emr-template-designer", "emr-patient-timeline", "emr-element-search", "emr-quality-board", "emr-quality-console", "emr-audit-log", "emr-archive", "emr-level-assess", "nurse-allergy", "medtech-report-query",
                // 住院医生站(2026-09 住院模块)
                "inp-doctor-ws", "inp-order-manage", "inp-order-template", "inp-diagnosis", "inp-med-record", "inp-patient-overview",
                // 临床路径/手术麻醉(2026-09 集成): 路径模板管理 + 手术管理 + 麻醉记录; 手麻P0: 手术申请管理
                "pathway-template", "pathway-stats", "surgery-manage", "surgery-apply", "anesthesia-record",
                // 住院报表(2026-09 报表/打印模块): 报表中心(只读)
                "inp-report",
                // 手麻P2: 手术统计报表(只读)
                "surgery-report",
                // 住院危急值闭环(T41): 危急值管理(确认/处置/关闭)
                "critical-value",
                // P6 会诊统一流程: 全院会诊流转驾驶舱(多维查询/统计/超时预警, 只读管理视角)
                "consultation-manage"});
        grants.put(Roles.NURSE, new String[]{"doctor-ws", "doctor-worklog", "nurse-pending", "nurse-skin-test", "nurse-infusion", "nurse-allergy", "nurse-exec-log",
                // 住院护士站(2026-09 住院模块)
                "inp-nurse-ws", "inp-order-audit", "inp-order-exec", "inp-nursing-record", "inp-shift-handover", "inp-bed-overview",
                // 麻醉记录(2026-09 集成, 只读) + 手麻P0: 病区复核角色手术申请管理
                "anesthesia-record", "surgery-apply",
                // 住院报表(2026-09 报表/打印模块): 报表中心(只读)
                "inp-report",
                // 住院危急值闭环 + PDA扫码(T41)
                "critical-value", "pda-simulation"});
        grants.put(Roles.THERAPIST, new String[]{"dashboard", "patient", "treatment-pending", "treatment-plan", "treatment-equip", "treatment-log"});
        grants.put(Roles.TECHNICIAN, new String[]{"dashboard", "patient", "medtech-specimen", "medtech-report", "medtech-critical", "medtech-critical-rule", "medtech-report-query"});
        // 病案统计科(P0)存量库补授: 录入组编目首页; 编目组分配+编目; 质控组审核+查阅编目
        grants.put(Roles.MR_INPUT, new String[]{"dashboard", "mr-catalog", "mr-annotation", "mr-search", "mr-workload", "mr-report", "mr-drg", "mr-quality-diff"});
        grants.put(Roles.MR_CATALOG, new String[]{"dashboard", "mr-assign", "mr-catalog", "mr-annotation", "mr-recall", "mr-borrow", "mr-search", "mr-workload", "mr-maintain", "mr-report", "mr-submit", "mr-drg", "mr-quality-diff"});
        grants.put(Roles.MR_REVIEW, new String[]{"dashboard", "mr-catalog", "mr-review", "mr-annotation", "mr-search", "mr-workload", "mr-report", "mr-submit", "mr-drg", "mr-quality-diff"});
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
            log.info("业务角色新菜单授权已补充: 药师(药库+管理统计+盘点+请领调拨调价台账追溯码+药房定价+药师审核)/收费员(报表+发票管理收费统计+住院登记结算+手麻记费+打印管理)/挂号员(挂号统计与明细)/医生护士(门诊医生站+医生工作日志)/医生(过敏登记+报告查询+住院医生站+临床路径/手术管理/麻醉记录+报表中心+危急值管理)/护士(护士站五项+住院护士站+麻醉记录+报表中心+危急值管理+PDA扫码)/治疗师医技人员(治疗/医技全量) 共 {} 条", added);
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
     * 幂等确保“企业字典”菜单存在(初挂在“标准字典”目录下; 新库随后由
     * moveSupplierDictToBasedata 统一改挂到"医共体管理›基础数据", 供牵头机构系统管理员维护)。
     */
    private void ensureSupplierDictMenu() {
        Long cnt = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("menu_key", "supplier-dict"));
        if (cnt != null && cnt > 0) {
            return;
        }
        long parentId = 0L;
        int sortNo = 98;
        SysMenu stdDir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "std-dict").last("LIMIT 1"));
        if (stdDir != null) {
            parentId = stdDir.getId();
            sortNo = 3;
        }
        SysMenu m = new SysMenu();
        m.setParentId(parentId);
        m.setMenuKey("supplier-dict");
        m.setMenuName("企业字典");
        m.setMenuType(2);
        m.setComp("SupplierDict");
        m.setSortNo(sortNo);
        m.setStatus(1);
        menuMapper.insert(m);
        log.info("企业字典菜单已补充(平台超级管理员专属)");
    }

    /**
     * 幂等将“企业字典”(supplier-dict)从“标准字典”目录改挂到“医共体管理›基础数据”(basedata)目录下,
     * 排在医保目录对照(1)/医保疾病对照(2)/医共体字典(3)之后(sort_no=4)。
     * 同时把存量库旧名“供货商维护”更名为“企业字典”。
     * 只改 parent_id/sort_no/menu_name, 菜单 id 不变, sys_role_menu 授权自动保持; 机构端可见性由
     * SysRoleService 移除 supplier-dict 排除项后, 经 ADMIN all_menus 递归补全 basedata→platform 祖先自动生效。
     * 须在 ensureBasedataMenuUnderPlatform/ensureDiagMapMenu 之后执行(basedata 目录已存在)。
     */
    private void moveSupplierDictToBasedata() {
        renameMenuIfOldName("supplier-dict", "供货商维护", "企业字典");
        SysMenu basedata = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "basedata").last("LIMIT 1"));
        SysMenu sup = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "supplier-dict").last("LIMIT 1"));
        if (basedata == null || sup == null) {
            return;
        }
        if (!basedata.getId().equals(sup.getParentId()) || sup.getSortNo() == null || sup.getSortNo() != 4) {
            SysMenu upd = new SysMenu();
            upd.setId(sup.getId());
            upd.setParentId(basedata.getId());
            upd.setSortNo(4);
            menuMapper.updateById(upd);
            log.info("「企业字典」已归入「医共体管理 → 基础数据」");
        }
    }

    /**
     * 幂等补种"费别与支付"菜单(fee-pay-dict/FeePayDict, 2026-10 费别/支付方式自定义字典):
     * 挂"医共体管理 → 基础数据"目录下, 紧随企业字典(sort_no=4)之后(sort_no=5)。
     * ADMIN/ORG_ADMIN 走 all_menus 免配置自动可见(含祖先递归补全); 写守卫在 Service 层
     * requireSelfOrgWrite(各机构自治维护本机构字典), 菜单可见性与接口守卫双重保障。
     * 须在 moveSupplierDictToBasedata 之后执行(basedata 目录已存在)。
     */
    private void ensureFeePayDictMenu() {
        Long cnt = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("menu_key", "fee-pay-dict"));
        if (cnt != null && cnt > 0) {
            return;
        }
        SysMenu basedata = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "basedata").last("LIMIT 1"));
        if (basedata == null) {
            return;
        }
        SysMenu m = new SysMenu();
        m.setParentId(basedata.getId());
        m.setMenuKey("fee-pay-dict");
        m.setMenuName("费别与支付");
        m.setMenuType(2);
        m.setComp("FeePayDict");
        m.setSortNo(5);
        m.setStatus(1);
        menuMapper.insert(m);
        log.info("「费别与支付」菜单已补种(医共体管理 → 基础数据)");
    }

    /**
     * 幂等补种"医疗类别"菜单(med-type-dict/MedTypeDict, 2026-10 医疗类别维护字典):
     * 挂"医共体管理 → 基础数据"目录下, 紧随费别与支付(sort_no=5)之后(sort_no=6)。
     * ADMIN/ORG_ADMIN 走 all_menus 免配置自动可见(含祖先递归补全); 写守卫在 Service 层
     * requireLeadOrg(仅牵头机构统一维护), 菜单可见性与接口守卫双重保障。
     */
    private void ensureMedTypeDictMenu() {
        Long cnt = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("menu_key", "med-type-dict"));
        if (cnt != null && cnt > 0) {
            return;
        }
        SysMenu basedata = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "basedata").last("LIMIT 1"));
        if (basedata == null) {
            return;
        }
        SysMenu m = new SysMenu();
        m.setParentId(basedata.getId());
        m.setMenuKey("med-type-dict");
        m.setMenuName("医疗类别");
        m.setMenuType(2);
        m.setComp("MedTypeDict");
        m.setSortNo(6);
        m.setStatus(1);
        menuMapper.insert(m);
        log.info("「医疗类别」菜单已补种(医共体管理 → 基础数据)");
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
     * 幂等确保"医保上报中心"菜单存在(批次4 M5: his_upload_status 上传管线监控台,
     * 按业务类型/状态筛选 + 失败待补手动重传)。挂"医保字典"子目录末尾,
     * ADMIN/ORG_ADMIN/SUPER_ADMIN 走 all_menus 自动可见; 业务角色需在角色权限页勾选授权。
     */
    private void ensureUploadCenterMenu(Map<String, Long> menuIds) {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "upload-center").last("LIMIT 1"));
        if (m == null) {
            SysMenu ybDir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "yb-dict").last("LIMIT 1"));
            if (ybDir == null) {
                return;
            }
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", ybDir.getId()))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            m = new SysMenu();
            m.setParentId(ybDir.getId());
            m.setMenuKey("upload-center");
            m.setMenuName("医保上报中心");
            m.setMenuType(2);
            m.setComp("UploadCenter");
            m.setSortNo(maxSort + 1);
            m.setVisible(1);
            m.setStatus(1);
            menuMapper.insert(m);
            log.info("医保上报中心菜单已补充(upload-center/UploadCenter)");
        }
        menuIds.put("upload-center", m.getId());
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
     * 幂等补种"医保疾病对照"菜单(diag-map/DiagMap, 2026-09 新增): 挂"医共体管理 → 基础数据"目录下,
     * 紧随"医保目录对照"(catalog-map, sort_no=1)之后(sort_no=2), 医共体字典顺延为 3。
     * ADMIN/ORG_ADMIN/SUPER_ADMIN 走 all_menus 免配置自动可见; 菜单 id 不变, 与 catalog-map 同级同授权策略。
     */
    private void ensureDiagMapMenu(Map<String, Long> menuIds) {
        SysMenu basedata = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "basedata").last("LIMIT 1"));
        if (basedata == null) {
            return;
        }
        ensureChildMenu(menuIds, "basedata", "diag-map", "医保疾病对照", "DiagMap");
        Long id = menuIds.get("diag-map");
        if (id == null) {
            return;
        }
        SysMenu m = menuMapper.selectById(id);
        if (m != null && (m.getSortNo() == null || m.getSortNo() != 2)) {
            SysMenu upd = new SysMenu();
            upd.setId(id);
            upd.setSortNo(2);
            menuMapper.updateById(upd);
        }
        SysMenu community = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "community-dict").last("LIMIT 1"));
        if (community != null && basedata.getId().equals(community.getParentId())
                && (community.getSortNo() == null || community.getSortNo() < 3)) {
            SysMenu cu = new SysMenu();
            cu.setId(community.getId());
            cu.setSortNo(3);
            menuMapper.updateById(cu);
        }
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
     * 幂等确保“病历模板管理”菜单存在(医生站模板/处方套/医嘱套/诊断维护统一页, 排在医生工作日志之后):
     * 既有库 seedMenus 表非空即跳过, 在此按 menu_key 判存补种挂 doctor 目录, 并回填 menuIds
     * 供 ensureBizRoleGrants 给医生角色补授权(ADMIN 走 all_menus 免配置)。
     */
    private void ensureMedicalTemplateMenu(Map<String, Long> menuIds) {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "medical-template").last("LIMIT 1"));
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
            m.setMenuKey("medical-template");
            m.setMenuName("病历模板管理");
            m.setMenuType(2);
            m.setComp("MedicalTemplateManage");
            m.setSortNo(maxSort + 1);
            m.setVisible(1);
            m.setStatus(1);
            menuMapper.insert(m);
            log.info("病历模板管理菜单已补充(medical-template/MedicalTemplateManage)");
        }
        menuIds.put("medical-template", m.getId());
    }

    /**
     * 幂等确保“病历模板设计器”菜单存在(结构化病历模板可视化自建, 排在病历模板管理之后):
     * 既有库按 menu_key 判存补种挂 doctor 目录, 并回填 menuIds 供 ensureBizRoleGrants 给医生角色补授权。
     */
    private void ensureEmrDesignerMenu(Map<String, Long> menuIds) {
        SysMenu m = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "emr-designer").last("LIMIT 1"));
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
            m.setMenuKey("emr-designer");
            m.setMenuName("病历模板设计器");
            m.setMenuType(2);
            m.setComp("EmrTemplateDesigner");
            m.setSortNo(maxSort + 1);
            m.setVisible(1);
            m.setStatus(1);
            menuMapper.insert(m);
            log.info("病历模板设计器菜单已补充(emr-designer/EmrTemplateDesigner)");
        }
        menuIds.put("emr-designer", m.getId());
    }

    /**
     * 幂等补种“病历质控与数据元”目录及五个子菜单(Phase C 2026-10: 质控规则维护/病历检索上报/质控评分看板;
     * P1a 2026-10 病历管理: 数据集管理/结构化模板设计器, 与前端 app.js 静态菜单同 key 同名同组)。
     * 既有库 seedMenus 表非空即跳过, 故先按 menu_key 判存建顶级目录(menu_type=1, sort 续接), 再挂叶子并回填 menuIds
     * 供 ensureBizRoleGrants 给医生角色补授权(检索/看板/结构化模板设计器); 规则维护与数据集管理属管理职能,
     * 由走 all_menus 免配置的机构/平台管理员可见。
     */
    private void ensureEmrQualityMenus(Map<String, Long> menuIds) {
        SysMenu dir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "emr-quality").last("LIMIT 1"));
        if (dir == null) {
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", 0L))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            dir = new SysMenu();
            dir.setParentId(0L);
            dir.setMenuKey("emr-quality");
            dir.setMenuName("病历质控与数据元");
            dir.setMenuType(1);
            dir.setSortNo(maxSort + 1);
            dir.setVisible(1);
            dir.setStatus(1);
            menuMapper.insert(dir);
            log.info("病历质控与数据元目录已补充(emr-quality)");
        }
        menuIds.put("emr-quality", dir.getId());
        ensureChildMenu(menuIds, "emr-quality", "emr-quality-rule", "质控规则维护", "EmrQualityRuleManage");
        ensureChildMenu(menuIds, "emr-quality", "emr-element-search", "病历检索上报", "EmrElementSearch");
        ensureChildMenu(menuIds, "emr-quality", "emr-quality-board", "质控评分看板", "EmrQualityBoard");
        // P5b(2026-10 质控工作台): 六模块一体(标准/时效/内涵/查询/人工质控/统计)
        ensureChildMenu(menuIds, "emr-quality", "emr-quality-console", "质控工作台", "EmrQualityConsole");
        // P1a(2026-10) 病历管理两叶子: 数据集管理(章节/小节/数据元三级维护, 管理职能) + 结构化模板设计器(数据集驱动 Tiptap 三栏式)
        ensureChildMenu(menuIds, "emr-quality", "emr-dataset", "数据集管理", "emr-dataset");
        ensureChildMenu(menuIds, "emr-quality", "emr-template-designer", "模板设计器(结构化)", "emr-template-designer");
        // P1c(2026-10) 患者全景时间线: 门诊+住院就诊事件统一时间轴(只读聚合, DOCTOR可访问)
        ensureChildMenu(menuIds, "emr-quality", "emr-patient-timeline", "患者全景时间线", "emr-patient-timeline");
        // P2(病历审计日志): 操作审计全量分页检索(牵头机构管理员可查, 医生可查单份病历审计)
        ensureChildMenu(menuIds, "emr-quality", "emr-audit-log", "病历审计日志", "EmrAuditLog");
        // P7a(2026-10 病历归档工作台): 归档全流程管控四页签(待归档/归档管理/封存管理/统计, DOCTOR可查看)
        ensureChildMenu(menuIds, "emr-quality", "emr-archive", "归档工作台", "EmrArchive");
        // P7b-3(2026-10 病历等级自评): 八维度自评雷达+维度明细卡(存量库补种, DOCTOR可查看)
        ensureChildMenu(menuIds, "emr-quality", "emr-level-assess", "等级自评", "EmrLevelAssess");
    }

    /**
     * 幂等补种病案统计菜单目录(2026-11 P0 病案统计科侧): 顶级目录"病案统计"(mr) + 三叶子菜单
     * 病案分配(MrAssign)/首页编目(MrCatalog)/质量审核(MrReview)。既有库 seedMenus 表非空即跳过,
     * 在此按 menu_key 判存补种, id 回填 menuIds 供 ensureBizRoleGrants 角色补授权;
     * ADMIN/ORG_ADMIN/SUPER_ADMIN 走 all_menus 自动可见。
     */
    private void ensureMedicalRecordMenus(Map<String, Long> menuIds) {
        ensureDirMenu(menuIds, "mr", "病案统计");
        ensureChildMenu(menuIds, "mr", "mr-assign", "病案分配", "MrAssign");
        ensureChildMenu(menuIds, "mr", "mr-catalog", "首页编目", "MrCatalog");
        ensureChildMenu(menuIds, "mr", "mr-review", "质量审核", "MrReview");
        ensureChildMenu(menuIds, "mr", "mr-recall", "病案收回", "MrRecall");
        ensureChildMenu(menuIds, "mr", "mr-borrow", "病案借阅", "MrBorrow");
        ensureChildMenu(menuIds, "mr", "mr-annotation", "批注反馈", "MrAnnotation");
        ensureChildMenu(menuIds, "mr", "mr-search", "检索查询", "MrSearch");
        // P2(2026-11 收尾) 病案统计存量库补种: 工作量 / 字典 / 报表 / 上报闭环
        ensureChildMenu(menuIds, "mr", "mr-workload", "工作量统计", "MrWorkload");
        ensureChildMenu(menuIds, "mr", "mr-maintain", "系统维护字典", "MrMaintain");
        ensureChildMenu(menuIds, "mr", "mr-report", "报表统计", "MrReport");
        ensureChildMenu(menuIds, "mr", "mr-submit", "上报闭环", "MrSubmit");
                // P3 集成阶段补种
                ensureChildMenu(menuIds, "mr", "mr-drg", "DRG分组对比", "MrDrg");
                ensureChildMenu(menuIds, "mr", "mr-quality-diff", "质控前后对比", "MrQualityDiff");
    }

    /** 幂等补种病案统计科业务角色(all_menus=0)并回填 roleIds 供授权(与 THERAPIST/TECHNICIAN 同档)。 */
    private void ensureMedicalRecordRoles(Map<String, Long> roleIds) {
        ensureGlobalRole(roleIds, Roles.MR_INPUT, "病案录入组", "病案首页信息录入/明细维护");
        ensureGlobalRole(roleIds, Roles.MR_CATALOG, "病案编目组", "病案分配/首页编目定稿");
        ensureGlobalRole(roleIds, Roles.MR_REVIEW, "病案质控组", "首页质量审核/确认锁定");
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

    /**
     * 幂等补种 P1 发药窗口子系统菜单(窗口维护/发药工作站/科室定向窗口/跨药房配置), 挂 pharmacy 目录。
     * 既有库 seedMenus 表非空即跳过, 故按 menu_key 判存补种并回填 menuIds 供角色补授权(ensureBizRoleGrants 给药师);
     * ADMIN/ORG_ADMIN/SUPER_ADMIN 走 all_menus 免配置自动可见。
     */
    private void ensureWindowMenus(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "pharmacy", "pharmacy-window", "发药窗口", "PharmacyWindowManage");
        ensureChildMenu(menuIds, "pharmacy", "window-workstation", "发药工作站", "WindowWorkstation");
        ensureChildMenu(menuIds, "pharmacy", "window-dept-rule", "科室定向窗口", "WindowDeptRule");
        ensureChildMenu(menuIds, "pharmacy", "pharmacy-cross", "跨药房配置", "PharmacyCrossConfig");
    }

    /**
     * 幂等补种 P2 门诊处方审核菜单(审方工作台), 挂 pharmacy 目录。
     * 同 ensureWindowMenus: 按 menu_key 判存补种并回填 menuIds 供药师补授权; 管理员走 all_menus 自动可见。
     */
    private void ensureRxAuditMenu(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "pharmacy", "rx-audit", "处方审核", "OutpRxAudit");
    }

    /**
     * 幂等补种药品采购全流程菜单(批次A: 供应商/采购规则/采购计划/采购订单), 挂 warehouse 目录。
     * 既有库 seedMenus 表非空即跳过, 故按 menu_key 判存补种并回填 menuIds 供角色补授权(ensureBizRoleGrants 给药师)。
     */
    private void ensurePurchaseMenus(Map<String, Long> menuIds) {
        ensureChildMenu(menuIds, "warehouse", "supplier-mgr", "供应商管理", "SupplierManage");
        ensureChildMenu(menuIds, "warehouse", "purchase-rule", "采购规则", "PurchaseRule");
        ensureChildMenu(menuIds, "warehouse", "purchase-plan", "采购计划", "PurchasePlan");
        ensureChildMenu(menuIds, "warehouse", "purchase-order", "采购订单", "PurchaseOrder");
                ensureChildMenu(menuIds, "warehouse", "stock-accept", "财务验收", "StockAccept");
                ensureChildMenu(menuIds, "warehouse", "supplier-pay", "供应商付款", "SupplierPayment");
                ensureChildMenu(menuIds, "warehouse", "payable-rpt", "应付账款", "PayableReport");
                ensureChildMenu(menuIds, "warehouse", "drug-maint", "药品养护", "DrugMaintenance");
                ensureChildMenu(menuIds, "warehouse", "maint-template", "养护模板", "MaintenanceTemplate");
                ensureChildMenu(menuIds, "warehouse", "month-end", "库房月结", "MonthEnd");
    }

    /**
     * 幂等将药库系统(g8/warehouse)平铺叶子按业务域重挂到 5 个子目录(2026-10 整合):
     * 药品采购/库存作业/财务结算/账簿统计/养护管理。先建子目录(含碑复活防 uk_menu_key),
     * 再按 key 将叶子 parent_id 改到对应子目录(sort_no 续接), 仅改 parent_id/sort_no 不改菜单 id
     * 故 sys_role_menu 授权天然保持; 子目录靠 treeByIds 自动补祖先无需显式授权。已到位则跳过。
     */
    private void regroupWarehouseMenus(Map<String, Long> menuIds) {
        SysMenu wh = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "warehouse").last("LIMIT 1"));
        if (wh == null) {
            return;
        }
        Map<String, String[]> groups = new java.util.LinkedHashMap<>();
        groups.put("wh-purchase", new String[] {"药品采购", "supplier-mgr", "purchase-rule", "purchase-plan", "purchase-order"});
        groups.put("wh-stock-ops", new String[] {"库存作业", "wh-in", "wh-out", "trf-mgr", "wh-check", "wh-stock", "warehouse-def", "wh-drug"});
        groups.put("wh-finance", new String[] {"财务结算", "stock-accept", "supplier-pay", "payable-rpt", "price-adjust", "month-end"});
        groups.put("wh-book", new String[] {"账簿统计", "stock-ledger", "warehouse-rpt"});
        groups.put("wh-maint", new String[] {"养护管理", "drug-maint", "maint-template"});
        for (Map.Entry<String, String[]> e : groups.entrySet()) {
            Long dirId = ensureSubDirUnder(e.getKey(), e.getValue()[0], wh.getId());
            if (dirId == null) {
                continue;
            }
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", dirId))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            for (int i = 1; i < e.getValue().length; i++) {
                SysMenu leaf = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", e.getValue()[i]).last("LIMIT 1"));
                if (leaf == null || dirId.equals(leaf.getParentId())) {
                    continue;
                }
                leaf.setParentId(dirId);
                leaf.setSortNo(++maxSort);
                menuMapper.updateById(leaf);
                log.info("药库菜单「{}」已重挂到子目录「{}」", leaf.getMenuName(), e.getValue()[0]);
            }
        }
    }

    /** 幂等确保 warehouse 下的子目录存在(menu_type=1, parent_id=whId): 先复活同 key 碑行防 uk_menu_key 冲突, 不存在则新建, 返回其 id */
    private Long ensureSubDirUnder(String key, String name, Long parentId) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE sys_menu SET deleted = 0 WHERE menu_key = '" + key + "' AND deleted = 1");
        } catch (Exception e) {
            log.warn("{} 碑行复活跳过: {}", key, e.getMessage());
        }
        SysMenu dir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", key).last("LIMIT 1"));
        if (dir == null) {
            int maxSort = 0;
            for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", parentId))) {
                maxSort = Math.max(maxSort, c.getSortNo() == null ? 0 : c.getSortNo());
            }
            dir = new SysMenu();
            dir.setParentId(parentId);
            dir.setMenuKey(key);
            dir.setMenuName(name);
            dir.setMenuType(1);
            dir.setSortNo(maxSort + 1);
            dir.setVisible(1);
            dir.setStatus(1);
            menuMapper.insert(dir);
            log.info("药库子目录「{}」已新建({})", name, key);
        }
        return dir.getId();
    }

    /**
     * 幂等将药房系统(g7/pharmacy)平铺叶子按业务域重挂到 4 个子目录(2026-11 整合): 门诊发药/住院发药/
     * 药房运营与追溯/统计查询。先建子目录(含碑复活防 uk_menu_key), 再按 key 将叶子 parent_id 改到对应
     * 子目录(sort_no 续接), 仅改 parent_id/sort_no 不改菜单 id 故 sys_role_menu 授权天然保持; 子目录靠
     * treeByIds 自动补祖先无需显式授权(与 regroupWarehouseMenus 同构)。住院 4 项由原独立顶级目录
     * inp-pharm-group 迁入 ph-inp; 统计查询新增 stat-usage/stat-yb/stat-quality 三叶子(缺失则补种并回填
     * menuIds)。迁移完成后 inp-pharm-group 子项清空则软删(其 key 已入 MERGED_AWAY_DIR_KEYS 由清墓碑)。
     * 全程判存幂等, 对新库(seedMenus 已分组)为无害空转。
     */
    private void consolidatePharmacyMenus(Map<String, Long> menuIds) {
        SysMenu ph = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "pharmacy").last("LIMIT 1"));
        if (ph == null) {
            return;
        }
        // 子域目录: {key, 名称}
        String[][] dirs = {
                {"ph-outp", "门诊发药"},
                {"ph-inp", "住院发药"},
                {"ph-ops", "药房运营与追溯"},
                {"ph-stat", "统计查询"},
        };
        // 叶子: {key, 名称, comp, 目标子域key}
        String[][] leaves = {
                {"dispense-todo", "待发药", "DispenseTodo", "ph-outp"},
                {"window-workstation", "发药工作站", "WindowWorkstation", "ph-outp"},
                {"dispense", "调剂发药", "DispenseRecord", "ph-outp"},
                {"rx-audit", "处方审核", "OutpRxAudit", "ph-outp"},
                {"drug-return", "退药", "DrugReturn", "ph-outp"},
                {"pharm-station", "药师审核", "pharm-station", "ph-inp"},
                {"inp-dispense-work", "住院发药工作台", "InpDispenseWork", "ph-inp"},
                {"inp-discharge-pickup", "出院带药核发", "InpDischargePickup", "ph-inp"},
                {"inp-dispense-history", "住院发药历史", "InpDispenseHistory", "ph-inp"},
                {"pharmacy-def", "药房管理", "PharmacyDef", "ph-ops"},
                {"price-mgr", "药房定价", "PharmacyPriceManage", "ph-ops"},
                {"req-mgr", "药品请领", "RequisitionManage", "ph-ops"},
                {"pharmacy-window", "发药窗口", "PharmacyWindowManage", "ph-ops"},
                {"window-dept-rule", "科室定向窗口", "WindowDeptRule", "ph-ops"},
                {"pharmacy-cross", "跨药房配置", "PharmacyCrossConfig", "ph-ops"},
                {"trace-code", "药品追溯码", "TraceCodeManage", "ph-ops"},
                {"pharmacy-rpt", "药房统计", "PharmacyReport", "ph-stat"},
                {"stat-usage", "药品消耗分析", "PharmacyUsageStat", "ph-stat"},
                {"stat-yb", "医保合规分析", "PharmacyYbStat", "ph-stat"},
                {"stat-quality", "处方与退药质量", "PharmacyQualityStat", "ph-stat"},
        };
        Map<String, Long> dirIds = new java.util.LinkedHashMap<>();
        for (String[] g : dirs) {
            Long id = ensurePharmacySubDir(g[0], g[1], ph.getId());
            if (id != null) {
                dirIds.put(g[0], id);
                menuIds.put(g[0], id);
            }
        }
        for (String[] lv : leaves) {
            Long dirId = dirIds.get(lv[3]);
            if (dirId == null) {
                continue;
            }
            SysMenu leaf = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", lv[0]).last("LIMIT 1"));
            if (leaf == null) {
                leaf = new SysMenu();
                leaf.setParentId(dirId);
                leaf.setMenuKey(lv[0]);
                leaf.setMenuName(lv[1]);
                leaf.setMenuType(2);
                leaf.setComp(lv[2]);
                leaf.setSortNo(maxSortUnder(dirId) + 1);
                leaf.setVisible(1);
                leaf.setStatus(1);
                menuMapper.insert(leaf);
                log.info("药房菜单「{}」已新建挂到子目录「{}」", lv[1], lv[3]);
            } else if (!dirId.equals(leaf.getParentId())) {
                leaf.setParentId(dirId);
                leaf.setSortNo(maxSortUnder(dirId) + 1);
                menuMapper.updateById(leaf);
                log.info("药房菜单「{}」已重挂到子目录「{}」", leaf.getMenuName(), lv[3]);
            }
            menuIds.put(lv[0], leaf.getId());
        }
        // 迁空后软删独立顶级目录 inp-pharm-group(住院 4 项已并入 ph-inp)
        SysMenu inpPharm = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", "inp-pharm-group").last("LIMIT 1"));
        if (inpPharm != null) {
            Long remaining = menuMapper.selectCount(new QueryWrapper<SysMenu>().eq("parent_id", inpPharm.getId()));
            if (remaining == null || remaining == 0L) {
                roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("menu_id", inpPharm.getId()));
                menuMapper.deleteById(inpPharm.getId());
                log.info("住院药师站顶级目录已软删(4项并入药房系统/住院发药): inp-pharm-group");
            }
        }
    }

    /** 幂等确保 pharmacy 下的子目录存在(menu_type=1, parent_id=phId): 先复活同 key 碑行防 uk_menu_key 冲突, 不存在则新建, 返回其 id */
    private Long ensurePharmacySubDir(String key, String name, Long parentId) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE sys_menu SET deleted = 0 WHERE menu_key = '" + key + "' AND deleted = 1");
        } catch (Exception e) {
            log.warn("{} 碑行复活跳过: {}", key, e.getMessage());
        }
        SysMenu dir = menuMapper.selectOne(new QueryWrapper<SysMenu>().eq("menu_key", key).last("LIMIT 1"));
        if (dir == null) {
            dir = new SysMenu();
            dir.setParentId(parentId);
            dir.setMenuKey(key);
            dir.setMenuName(name);
            dir.setMenuType(1);
            dir.setSortNo(maxSortUnder(parentId) + 1);
            dir.setVisible(1);
            dir.setStatus(1);
            menuMapper.insert(dir);
            log.info("药房子目录「{}」已新建({})", name, key);
        }
        return dir.getId();
    }

    /** 指定父目录下现有子项的最大 sort_no(无子项返回 0), 供新建/重挂叶子续接排序 */
    private int maxSortUnder(Long parentId) {
        int max = 0;
        for (SysMenu c : menuMapper.selectList(new QueryWrapper<SysMenu>().eq("parent_id", parentId))) {
            max = Math.max(max, c.getSortNo() == null ? 0 : c.getSortNo());
        }
        return max;
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

    /* ===================== 3d. 住院模块基座菜单(2026-09) ===================== */

    /**
     * 幂等补种住院三目录及18叶子(2026-09 住院模块): 住院登记结算(7)/住院医生站(5)/住院护士站(6)。
     * 与三模块基座同款判存补种(ensureDirMenu/ensureChildMenu 幂等且自动复活墓碑行),
     * id 回填 menuIds 供角色补授权(ensureBizRoleGrants: 医生→住院医生站全部, 护士→住院护士站全部,
     * 收费员→住院登记结算全部; ADMIN/ORG_ADMIN/SUPER_ADMIN 走 all_menus 免配置自动可见)。
     * 医生站/护士站多叶子共用同一工作站组件(InpDoctorWorkstation/InpNurseStation), 入口 key 区分定位页签;
     * 菜单 key 与前端 app.js 静态兑底 MENU 同 key 同名(动态菜单优先, 失败回退静态)。
     * 2026-09 集成追加: 临床路径(路径模板管理/独立页组件)与手术麻醉(手术管理/麻醉记录/手麻记费,
     * 三独立视图组件)两组, 同款判存补种。
     * 2026-09 报表/打印模块追加: 住院报表(报表中心InpReportCenter/打印管理InpPrintCenter)一组,
     * 同款判存补种; 医生/护士补授报表中心(只读), 收费员补授打印管理(日清单/结算单打印),
     * ADMIN/ORG_ADMIN/SUPER_ADMIN 走 all_menus 免配置自动可见。
     * T41 追加: 住院药师站(药师审核pharm-station)/危急值闭环(危急值管理critical-value)/
     * 移动护理(PDA扫码pda-simulation/移动护理mobile-nurse)三组, comp 与 HIS.views 注册键同值。
     * P6 追加: 会诊统一流程目录(consult-group/会诊管理, consultation-manage.js 全院会诊流转驾驶舱,
     * 住院门诊通用, 医生由 ensureBizRoleGrants 补授 consultation-manage)。
     */
    private void mergeInpatientMenus(Map<String, Long> menuIds) {
        ensureDirMenu(menuIds, "inpatient-group", "住院登记结算");
        ensureChildMenu(menuIds, "inpatient-group", "inp-admission", "入院登记", "InpAdmission");
        ensureChildMenu(menuIds, "inpatient-group", "inp-patient-list", "在院患者管理", "InpPatientList");
        ensureChildMenu(menuIds, "inpatient-group", "inp-bed-manage", "床位管理", "InpBedManage");
        ensureChildMenu(menuIds, "inpatient-group", "inp-deposit", "预交金管理", "InpDeposit");
        ensureChildMenu(menuIds, "inpatient-group", "inp-charge-list", "住院费用清单", "InpChargeList");
        ensureChildMenu(menuIds, "inpatient-group", "inp-settle", "出院结算", "InpSettle");
        ensureChildMenu(menuIds, "inpatient-group", "inp-daily-summary", "住院日报", "InpDailySummary");
        ensureDirMenu(menuIds, "inp-doctor-group", "住院医生站");
        ensureChildMenu(menuIds, "inp-doctor-group", "inp-doctor-ws", "住院医生工作站", "InpDoctorWorkstation");
        ensureChildMenu(menuIds, "inp-doctor-group", "inp-order-manage", "医嘱管理", "InpDoctorWorkstation");
        ensureChildMenu(menuIds, "inp-doctor-group", "inp-order-template", "医嘱模板/套餐", "InpOrderTemplateManage");
        ensureChildMenu(menuIds, "inp-doctor-group", "inp-diagnosis", "住院诊断", "InpDoctorWorkstation");
        ensureChildMenu(menuIds, "inp-doctor-group", "inp-med-record", "住院病历", "InpDoctorWorkstation");
        ensureChildMenu(menuIds, "inp-doctor-group", "inp-patient-overview", "患者概览", "InpDoctorWorkstation");
        ensureDirMenu(menuIds, "inp-nurse-group", "住院护士站");
        ensureChildMenu(menuIds, "inp-nurse-group", "inp-nurse-ws", "住院护士工作站", "InpNurseStation");
        ensureChildMenu(menuIds, "inp-nurse-group", "inp-order-audit", "医嘱审核", "InpNurseStation");
        ensureChildMenu(menuIds, "inp-nurse-group", "inp-order-exec", "医嘱执行", "InpNurseStation");
        ensureChildMenu(menuIds, "inp-nurse-group", "inp-nursing-record", "护理记录", "InpNurseStation");
        ensureChildMenu(menuIds, "inp-nurse-group", "inp-shift-handover", "交接班", "InpNurseStation");
        ensureChildMenu(menuIds, "inp-nurse-group", "inp-bed-overview", "床位一览", "InpNurseStation");
        // 临床路径(2026-09 临床路径模块): 路径模板管理 + 路径统计质控
        ensureDirMenu(menuIds, "clinical-pathway-group", "临床路径");
        ensureChildMenu(menuIds, "clinical-pathway-group", "pathway-template", "路径模板管理", "ClinicalPathwayManage");
        ensureChildMenu(menuIds, "clinical-pathway-group", "pathway-stats", "路径统计质控", "ClinicalPathwayStats");
        // 手术麻醉(2026-09 手麻记费模块): 手术管理/麻醉记录/手麻记费; 手麻P0: 手术申请管理(含通知管理)
        ensureDirMenu(menuIds, "surgery-group", "手术麻醉");
        ensureChildMenu(menuIds, "surgery-group", "surgery-manage", "手术管理", "SurgeryManage");
        ensureChildMenu(menuIds, "surgery-group", "surgery-apply", "手术申请管理", "SurgeryApply");
        ensureChildMenu(menuIds, "surgery-group", "anesthesia-record", "麻醉记录", "AnesthesiaRecord");
                ensureChildMenu(menuIds, "surgery-group", "surgery-fee", "手麻记费", "SurgeryFee");
                // 手麻P2: 手术统计报表(手术量/麻醉/时长/费用/质量/KPI 六类只读)
                ensureChildMenu(menuIds, "surgery-group", "surgery-report", "手术统计报表", "SurgeryReport");
        // 住院报表(2026-09 报表/打印模块): 报表中心/打印管理
        ensureDirMenu(menuIds, "inp-report-group", "住院报表");
        ensureChildMenu(menuIds, "inp-report-group", "inp-report", "报表中心", "InpReportCenter");
        ensureChildMenu(menuIds, "inp-report-group", "inp-print", "打印管理", "InpPrintCenter");
        // T41(与 app.js 静态兑底菜单同 key 同名): 危急值管理/PDA扫码/移动护理;
        // comp 用 HIS.views 注册键(critical-value.js 注册键为小写短横线, 与此处一致)
        // 住院药师站 4 项(pharm-station/inp-dispense-work/inp-discharge-pickup/inp-dispense-history)已并入
        // "药房系统/住院发药"(ph-inp)子域(2026-11 药房整合): 新库由 seedMenus 直接种子, 存量库由
        // consolidatePharmacyMenus 重挂 parent_id 并软删 inp-pharm-group 顶级目录; 此处不再种植独立目录。
        ensureDirMenu(menuIds, "critical-value-group", "危急值闭环");
        ensureChildMenu(menuIds, "critical-value-group", "critical-value", "危急值管理", "critical-value");
        ensureDirMenu(menuIds, "mobile-nurse-group", "移动护理");
        ensureChildMenu(menuIds, "mobile-nurse-group", "pda-simulation", "PDA扫码", "pda-simulation");
        ensureChildMenu(menuIds, "mobile-nurse-group", "mobile-nurse", "移动护理", "mobile-nurse");
        // P6(2026-11) 会诊统一流程: 全院会诊流转驾驶舱(多维查询/统计分析/超时预警三页签,
        // consultation-manage.js, 统一接口 /api/his/consultation), 住院/门诊通用;
        // comp 与 HIS.views 注册键 ConsultationManage 同值, key 与前端 app.js 静态兑底菜单同 key 同名
        ensureDirMenu(menuIds, "consult-group", "会诊管理");
        ensureChildMenu(menuIds, "consult-group", "consultation-manage", "会诊管理", "ConsultationManage");
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
     * 幂等种子平台运营方租户(PLATFORM) + 超级管理员账号。
     * 口令经 BootstrapPassword 解析: 环境变量 HIS_BOOTSTRAP_PASSWORD 优先,
     * 未注入回落内置演示口令(仅限开发/演示, 日志不打印口令)。
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
        String pwd = BootstrapPassword.resolve();
        Long superRoleId = globalRoleIds.get(Roles.SUPER_ADMIN);
        userService.createUser(t.getId(), "superadmin", pwd, "超级管理员",
                Roles.SUPER_ADMIN, null, null, null, superRoleId, null, null);
        if (BootstrapPassword.isDefault(pwd)) {
            log.warn("【安全】平台超级管理员使用内置演示口令, 生产部署请设置环境变量 {} 注入独立口令", BootstrapPassword.ENV_NAME);
        }
        log.info("平台超级管理员已创建: {} / superadmin", code);
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
