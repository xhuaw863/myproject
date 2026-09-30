package com.yb.hi.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.entity.cashier.HisChargeBill;
import com.yb.hi.entity.cashier.HisChargeBillItem;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.warehouse.HisDrugStock;
import com.yb.hi.entity.warehouse.HisWarehouseDef;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.cashier.HisChargeBillItemMapper;
import com.yb.hi.mapper.cashier.HisChargeBillMapper;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.warehouse.HisDrugStockMapper;
import com.yb.hi.platform.dto.TenantRegisterReq;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.platform.service.AuthService;
import com.yb.hi.platform.service.SysTenantService;
import com.yb.hi.service.pharmacy.PharmacyDefService;
import com.yb.hi.service.warehouse.WarehouseDefService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 演示数据初始化: 应用启动时幂等创建演示医院(租户)与管理员账号
 * 用于多租户隔离验证与开发调试。可通过 his.demo-data.enabled=false 关闭。
 * 2026-09 扩展: 药品库存演示(药库库存/低库存预警测试) + 演示收费单(报表/Dashboard数据)。
 * 2026-09 集成扩展(三模块基座): 危急值规则(6条门诊通用阈值) + 护士站执行记录(含皮试阳性同步过敏档案)
 * + 治疗计划(进行中疗程) + 危急值待确认记录(Dashboard徽章演示)。
 * 库存/收费/三模块种子均在基础数据已就绪的库上生效(药品目录已导入/已完成就诊存在/机构已建):
 * 全新库首次启动时 RBAC(@Order(3)) 尚未建机构、目录/就诊未导入, 自然跳过, 满足条件后重启补种。
 */
@Slf4j
@Order(1)
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private final SysTenantService tenantService;
    private final AuthService authService;
    private final HisDrugStockMapper stockMapper;
    private final HisDrugCatalogMapper drugCatalogMapper;
    private final HisChargeBillMapper billMapper;
    private final HisChargeBillItemMapper billItemMapper;
    private final HisVisitMapper visitMapper;
    private final SysOrgMapper orgMapper;
    private final JdbcTemplate jdbcTemplate;
    private final WarehouseDefService warehouseDefService;
    private final PharmacyDefService pharmacyDefService;

    @Value("${his.demo-data.enabled:true}")
    private boolean enabled;

    public DemoDataInitializer(SysTenantService tenantService, AuthService authService,
                               HisDrugStockMapper stockMapper, HisDrugCatalogMapper drugCatalogMapper,
                               HisChargeBillMapper billMapper, HisChargeBillItemMapper billItemMapper,
                               HisVisitMapper visitMapper, SysOrgMapper orgMapper,
                               JdbcTemplate jdbcTemplate, WarehouseDefService warehouseDefService,
                               PharmacyDefService pharmacyDefService) {
        this.tenantService = tenantService;
        this.authService = authService;
        this.stockMapper = stockMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.billMapper = billMapper;
        this.billItemMapper = billItemMapper;
        this.visitMapper = visitMapper;
        this.orgMapper = orgMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.warehouseDefService = warehouseDefService;
        this.pharmacyDefService = pharmacyDefService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            // 演示账号口令: 环境变量 HIS_BOOTSTRAP_PASSWORD 优先, 未注入回落内置演示口令(仅限开发/演示)
            String pwd = BootstrapPassword.resolve();
            if (BootstrapPassword.isDefault(pwd)) {
                log.warn("【安全】演示医院管理员使用内置演示口令, 生产部署请设置环境变量 {} 注入独立口令", BootstrapPassword.ENV_NAME);
            }
            seedTenant("H42010000000", "测试医院", "H42010000000", "420100", "admin", pwd, "张管理");
            seedTenant("DEMO_B", "演示医院B", "H42020000000", "420200", "admin", pwd, "李管理");
            // 系统参数全局种子(分组+定义, tenant_id=0 跨租户共享): 独立 try 防表未建时拖累租户种子,
            // 表由 DictSchemaMigration(@Order(0)) 幂等建, 未建时跳过下次启动补
            try {
                seedSystemParams();
            } catch (Exception e) {
                log.warn("系统参数种子跳过(可能表未建): {}", e.getMessage());
            }
            // 药库库存 + 演示收费单: 按租户幂等补种(基础数据就绪才生效, 见类注释)
            for (SysTenant t : tenantService.listAll()) {
                if (SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                    continue; // 平台运营方租户无医院业务数据
                }
                Long prev = TenantContext.get();
                try {
                    TenantContext.set(t.getId());
                    seedDrugStock(t.getId());
                    seedSampleBills(t.getId());
                } catch (Exception e) {
                    log.warn("租户[{}] 演示库存/收费数据初始化跳过: {}", t.getId(), e.getMessage());
                }
                // 三模块基座演示(危急值规则/护士站/治疗/危急值待确认): 独立 try 防表未建时拖累其他种子,
                // 表由 DictSchemaMigration(@Order(0)) 幂等建, 关联医嘱/就诊未就绪时逐方法内部判空跳过
                try {
                    seedCriticalRules(t.getId());
                    seedSampleNurseData(t.getId());
                    seedSampleTreatmentData(t.getId());
                    seedSampleCriticalPending(t.getId());
                } catch (Exception e) {
                    log.warn("租户[{}] 三模块演示数据初始化跳过(可能表未建或关联数据未就绪): {}", t.getId(), e.getMessage());
                } finally {
                    if (prev != null) {
                        TenantContext.set(prev);
                    } else {
                        TenantContext.clear();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("演示数据初始化跳过(可能表未建或已存在): {}", e.getMessage());
        }
    }

    /* ===================== 系统参数种子(全局, 2026-09) ===================== */

    /**
     * 系统参数全局种子: 8 个参数分组(sys_param_group) + 25 条参数定义(sys_param 定义行 scope_level=0,
     * scope_id=0, tenant_id=0)。sys_param/sys_param_group 均已豁免租户插件且全局行 tenant_id=0 需跨租户
     * 可见, 故不走 Mapper 而直接 JdbcTemplate INSERT 并显式落 tenant_id=0。
     * 幂等: 两表物理唯一键(uk_group_code / uk_param_scope)均不含 deleted, 墓碑行仍占键位 —— 判存查询
     * 不带 deleted 条件, 命中未删除行则跳过(保持手工修改), 命中墓碑行则复活并重置
     * (与 SystemParamService.createDefinition 同款口径)。定义行 param_value 与 default_value 同步落值,
     * 保证解析器 resolve 命中全局生效值。dataType 用规范短名(string/int/decimal/bool/enum),
     * enum_options 为逗号分隔(后端 validateValue 的 split(",") 精确等值校验口径)。
     */
    private void seedSystemParams() {
        Object[][] groups = {
                {"system", "系统通用", 1, "平台运行与账号安全参数"},
                {"outpatient", "门诊业务", 2, "门诊挂号/退号/接诊参数"},
                {"pharmacy", "药房管理", 3, "发药/退药/扣减策略参数"},
                {"warehouse", "药库管理", 4, "药库业务参数"},
                {"schedule", "排班管理", 5, "排班与号源参数"},
                {"nurse", "护士站", 6, "护理执行与安全参数"},
                {"treatment", "治疗管理", 7, "疗程计划与执行参数"},
                {"medtech", "医技管理", 8, "标本/报告/危急值参数"}
        };
        int groupsAdded = 0;
        for (Object[] g : groups) {
            String code = (String) g[0];
            List<Map<String, Object>> hit = jdbcTemplate.queryForList(
                    "SELECT id, deleted FROM sys_param_group WHERE group_code = ? LIMIT 1", code);
            if (!hit.isEmpty()) {
                Number deleted = (Number) hit.get(0).get("deleted");
                if (deleted != null && deleted.intValue() == 1) {
                    jdbcTemplate.update(
                            "UPDATE sys_param_group SET group_name = ?, sort_no = ?, remark = ?, deleted = 0,"
                                    + " update_by = 'demo-seed', update_time = NOW() WHERE id = ?",
                            g[1], g[2], g[3], hit.get(0).get("id"));
                }
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO sys_param_group (group_code, group_name, sort_no, remark,"
                            + " create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (?, ?, ?, ?, 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                    code, g[1], g[2], g[3]);
            groupsAdded++;
        }
        // 参数定义行: {分组, 参数键, 名称, 数据类型, 默认值, 枚举选项, 最小值, 最大值, 必填, 允许作用域, 备注}
        Object[][] params = {
                {"system", "system.session_timeout", "会话超时时间(分钟)", "int", "30", null, "1", "1440", 1, "0,1,2,3", "登录会话无操作超时时长"},
                {"system", "system.password_min_length", "密码最小长度", "int", "6", null, "4", "32", 1, "0,1,2,3", "账号密码最小字符数"},
                {"system", "system.login_fail_lock_count", "登录失败锁定次数", "int", "5", null, "3", "10", 1, "0,1,2,3", "连续失败达到该次数锁定账号"},
                {"system", "system.page_size_default", "默认分页行数", "enum", "20", "10,20,50,100", null, null, 1, "0,1,2,3", "列表页默认每页行数"},
                {"system", "system.data_retain_months", "数据保留月数", "int", "36", null, "6", "120", 1, "0,1,2,3", "业务数据在线保留月数"},
                {"system", "system.menu_default_collapsed", "左菜单默认折叠", "bool", "false", null, null, null, 0, "0,1,2", "登录后左侧菜单是否默认折叠为入口条; 机构级配置优先于租户级"},
                {"system", "system.list_default_paged", "列表默认分页显示", "bool", "true", null, null, null, 0, "0,1,2", "列表页首次进入时默认使用分页(true)还是全量(false)模式; 用户手动切换后以本地偏好为准"},
                {"system", "system.list_full_threshold", "全量显示确认阈值", "int", "2000", null, "100", "50000", 0, "0,1,2", "数据行数超过此阈值时切换全量模式需弹确认提示; 设为极大值等效于不提示"},
                {"outpatient", "outpatient.default_reg_fee", "默认挂号费(元)", "decimal", "15.00", null, "0", "1000", 0, "0,1,2,3", "普通号默认挂号费"},
                {"outpatient", "outpatient.allow_same_day_refund", "允许当日退号", "bool", "true", null, null, null, 1, "0,1,2,3", "当日挂号是否允许退号"},
                {"outpatient", "outpatient.queue_call_enabled", "叫号功能启用", "bool", "false", null, null, null, 0, "0,1,2,3", "候诊叫号屏开关"},
                {"outpatient", "outpatient.visit_timeout_minutes", "接诊超时时间(分钟)", "int", "120", null, "10", "1440", 1, "0,1,2,3", "接诊记录超时归档时长"},
                {"pharmacy", "pharmacy.dispense_double_check", "发药双人核对", "bool", "true", null, null, null, 1, "0,1,2,3", "发药时需第二人核对"},
                {"pharmacy", "pharmacy.auto_refresh_seconds", "待发药自动刷新间隔(秒)", "int", "30", null, "0", "600", 0, "0,1,2", "待发药工作站列表自动刷新间隔秒数; 设为0则不自动刷新"},
                {"pharmacy", "pharmacy.return_need_approval", "退药需审批", "bool", "true", null, null, null, 0, "0,1,2,3", "退药是否需药师长审批"},
                {"pharmacy", "pharmacy.fifo_enabled", "先进先出扣减", "bool", "true", null, null, null, 1, "0,1,2,3", "按批次效期先进先出扣库存"},
                {"schedule", "schedule.default_slot_count", "默认号源数量", "int", "30", null, "1", "200", 1, "0,1,2,3", "新建排班默认号源数"},
                {"schedule", "schedule.advance_days", "可预约天数", "int", "7", null, "1", "30", 1, "0,1,2,3", "患者可提前预约天数"},
                {"schedule", "schedule.slot_duration_minutes", "每号时长(分钟)", "int", "10", null, "5", "60", 1, "0,1,2,3", "单个号源的接诊时长"},
                {"nurse", "nurse.skin_test_observe_minutes", "皮试观察时间(分钟)", "int", "20", null, "10", "60", 1, "0,1,2,3", "皮试后观察时长"},
                {"nurse", "nurse.infusion_patrol_interval", "输液巡视间隔(分钟)", "int", "30", null, "10", "120", 1, "0,1,2,3", "输液中巡视间隔"},
                {"nurse", "nurse.allergy_alert_enabled", "过敏提醒启用", "bool", "true", null, null, null, 1, "0,1,2,3", "开单/执行前过敏史提醒"},
                {"nurse", "nurse.double_check_required", "双人核对必须", "bool", "false", null, null, null, 0, "0,1,2", "高危操作双人核对强制开关"},
                {"treatment", "treatment.plan_expire_days", "疗程默认有效天数", "int", "90", null, "7", "365", 1, "0,1,2,3", "新建疗程默认有效期"},
                {"treatment", "treatment.allow_over_session", "允许超疗程次数执行", "bool", "false", null, null, null, 0, "0,1,2,3", "超出计划次数后是否仍可执行"},
                {"medtech", "medtech.report_double_review", "报告双人审核", "bool", "true", null, null, null, 1, "0,1,2,3", "报告发布需第二人审核"},
                {"medtech", "medtech.critical_notify_timeout", "危急值通知超时(分钟)", "int", "30", null, "5", "240", 1, "0,1,2,3", "危急值发现后须通知的最长时长"},
                {"medtech", "medtech.critical_receive_timeout", "危急值接收超时(分钟)", "int", "30", null, "5", "240", 1, "0,1,2,3", "危急值通知后临床须接收的最长时长"},
                {"medtech", "medtech.specimen_barcode_prefix", "标本条码前缀", "string", "BB", null, null, null, 0, "0,1", "标本条码生成前缀"}
        };
        int paramsAdded = 0;
        for (Object[] p : params) {
            String key = (String) p[1];
            List<Map<String, Object>> hit = jdbcTemplate.queryForList(
                    "SELECT id, deleted FROM sys_param WHERE param_key = ? AND scope_level = 0 AND scope_id = 0 LIMIT 1", key);
            if (!hit.isEmpty()) {
                Number deleted = (Number) hit.get(0).get("deleted");
                if (deleted != null && deleted.intValue() == 1) {
                    // 墓碑行复活并重置(uk_param_scope 物理唯一不含 deleted), param_value 与 default_value 同步
                    jdbcTemplate.update(
                            "UPDATE sys_param SET tenant_id = 0, param_value = ?, scope_id = 0, group_code = ?,"
                                    + " param_name = ?, data_type = ?, default_value = ?, enum_options = ?,"
                                    + " min_value = ?, max_value = ?, required = ?, allow_scope = ?, remark = ?,"
                                    + " deleted = 0, update_by = 'demo-seed', update_time = NOW() WHERE id = ?",
                            p[4], p[0], p[2], p[3], p[4], p[5], toBd(p[6]), toBd(p[7]), p[8], p[9], p[10],
                            hit.get(0).get("id"));
                }
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO sys_param (tenant_id, param_key, param_value, scope_level, scope_id, group_code,"
                            + " param_name, data_type, default_value, enum_options, min_value, max_value, required,"
                            + " allow_scope, remark, create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (0, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                    key, p[4], p[0], p[2], p[3], p[4], p[5], toBd(p[6]), toBd(p[7]), p[8], p[9], p[10]);
            paramsAdded++;
        }
        if (groupsAdded > 0 || paramsAdded > 0) {
            log.info("系统参数种子完成: 分组新增 {} 个, 参数定义新增 {} 条(全局行 tenant_id=0, 已存在保持不动)",
                    groupsAdded, paramsAdded);
        }
    }

    private void seedTenant(String code, String name, String fixmedinsCode, String admvs,
                            String adminUser, String adminPwd, String adminName) {
        if (tenantService.codeExists(code)) {
            return;
        }
        TenantRegisterReq req = new TenantRegisterReq();
        req.setTenantCode(code);
        req.setTenantName(name);
        req.setFixmedinsCode(fixmedinsCode);
        req.setFixmedinsName(name);
        req.setMdtrtareaAdmvs(admvs);
        req.setInsuplcAdmdvs(admvs);
        req.setMockEnabled(1);
        req.setContact(adminName);
        req.setAdminUsername(adminUser);
        req.setAdminPassword(adminPwd);
        req.setAdminName(adminName);
        authService.openHospital(req);
        log.info("演示医院已创建: {} ({}), 管理员 {}/{}", name, code, adminUser, adminPwd);
    }

    /* ===================== 药品库存演示 ===================== */

    /**
     * 两级库存演示数据: 为默认药库位与默认药房库存位各自播种基础库存(从医共体药品目录取前20种启用药品,
     * 每条1-2个批次), 使"药库库存页"与"药房发药链路"开箱可用。
     * 幂等: 按库位粒度判定(该库位已有库存则跳过), 存量库补种药房库位不会重复。
     * 前3种药品造低库存(qty<warn_qty=20)供预警测试; 机构/目录未就绪则跳过。
     */
    private void seedDrugStock(Long tenantId) {
        Long orgId = leadOrgId();
        if (orgId == null) {
            return; // 机构未建(RBAC初始化未执行), 下次启动补
        }
        List<HisDrugCatalog> drugs = drugCatalogMapper.selectList(new QueryWrapper<HisDrugCatalog>()
                .eq("status", 1).orderByAsc("id").last("LIMIT 20"));
        if (drugs.isEmpty()) {
            return; // 药品目录未导入
        }
        // 两级库存目标库位: 默认药库 + 默认药房库存位(均由各自 Service 幂等确保存在)
        List<Long> targets = new ArrayList<>();
        List<HisWarehouseDef> whs = warehouseDefService.list(orgId);
        if (!whs.isEmpty()) {
            targets.add(whs.get(0).getId());
        }
        Long phLoc = defaultPharmacyStockLocation(orgId);
        if (phLoc != null && !targets.contains(phLoc)) {
            targets.add(phLoc);
        }
        if (targets.isEmpty()) {
            return;
        }
        int total = 0;
        for (Long locId : targets) {
            total += seedLocationStock(orgId, locId, drugs);
        }
        if (total > 0) {
            log.info("租户[{}] 两级库存演示数据初始化完成: 库位={}, 新增库存行={}", tenantId, targets, total);
        }
    }

    /** 为指定库位播种基础库存(幂等: 该库位已有库存则跳过); 返回新增行数 */
    private int seedLocationStock(Long orgId, Long warehouseId, List<HisDrugCatalog> drugs) {
        if (stockMapper.selectCount(new QueryWrapper<HisDrugStock>()
                .eq("org_id", orgId).eq("warehouse_id", warehouseId)) > 0) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        String month = today.format(DateTimeFormatter.ofPattern("yyyyMM"));
        Random rnd = new Random(20260925L + warehouseId); // 按库位偏移使批次量/效期有差异但可重现
        int batchSeq = 0;
        int inserted = 0;
        for (int i = 0; i < drugs.size(); i++) {
            HisDrugCatalog d = drugs.get(i);
            int batches = (i % 3 == 0) ? 2 : 1;
            for (int b = 0; b < batches; b++) {
                HisDrugStock s = new HisDrugStock();
                s.setOrgId(orgId);
                s.setWarehouseId(warehouseId);
                s.setDrugCatalogId(d.getId());
                s.setDrugCode(d.getDrugCode());
                s.setDrugName(StringUtils.hasText(d.getGenericName()) ? d.getGenericName() : d.getDrugCode());
                s.setSpec(d.getSpec());
                s.setDosform(d.getDosformName());
                s.setBatchNo("PH" + month + String.format("%03d", ++batchSeq));
                s.setManufacturer(d.getManufacturer());
                boolean low = i < 3; // 前3种造低库存(5-15 < 预警量20), 供预警测试
                s.setQty(BigDecimal.valueOf(low ? 5 + rnd.nextInt(11) : 50 + rnd.nextInt(151)));
                s.setWarnQty(BigDecimal.valueOf(20));
                s.setCostPrice(d.getPurchasePrice() != null ? d.getPurchasePrice() : new BigDecimal("10.0000"));
                s.setRetailPrice(d.getRetailPrice() != null ? d.getRetailPrice() : new BigDecimal("15.0000"));
                s.setProdDate(today.minusMonths(6));
                s.setExpDate(today.plusYears(1 + rnd.nextInt(3)).plusDays(rnd.nextInt(60)));
                s.setStatus(1);
                stockMapper.insert(s);
                inserted++;
            }
        }
        return inserted;
    }

    /** 默认药房的库存位ID(触发 PharmacyDefService.list 幂等建默认药房及其 PHARMACY 库位) */
    private Long defaultPharmacyStockLocation(Long orgId) {
        List<HisPharmacyDef> phs = pharmacyDefService.list(orgId);
        for (HisPharmacyDef p : phs) {
            if (p.getStockLocationId() != null) {
                return p.getStockLocationId();
            }
        }
        return null;
    }

    /* ===================== 演示收费单 ===================== */

    /**
     * 演示收费单: 取已完成(visit_status=3)且未收费(charge_status=0)的最近3条就诊生成收费单+明细,
     * 供报表页(结算记录/门诊日结)与 Dashboard 展示。
     * 含医保结算(有医保就诊信息的首条, Mock口径四分)与自费(全额自付现金)两种类型。
     * 幂等: 该租户已有收费记录则跳过; 无可用就诊/机构未建则跳过。
     */
    private void seedSampleBills(Long tenantId) {
        if (billMapper.selectCount(null) > 0) {
            return; // 已有收费记录
        }
        Long orgId = leadOrgId();
        if (orgId == null) {
            return; // 机构未建, 下次启动补
        }
        List<HisVisit> visits = visitMapper.selectList(new QueryWrapper<HisVisit>()
                .eq("visit_status", 3)
                .apply("(charge_status IS NULL OR charge_status = 0)")
                .orderByDesc("id")
                .last("LIMIT 3"));
        if (visits.isEmpty()) {
            return; // 无已完成就诊(医生站未使用), 无从生成收费单
        }
        // 首条有医保就诊信息(mdtrt_id+psn_no)的走医保结算, 其余自费; 保证两种类型都有
        int ybIdx = -1;
        for (int i = 0; i < visits.size(); i++) {
            HisVisit v = visits.get(i);
            if (StringUtils.hasText(v.getMdtrtId()) && StringUtils.hasText(v.getPsnNo())) {
                ybIdx = i;
                break;
            }
        }
        int created = 0;
        for (int i = 0; i < visits.size(); i++) {
            if (createDemoBill(tenantId, visits.get(i), orgId, i == ybIdx)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("租户[{}] 演示收费单初始化完成({}张: 医保结算{}张/自费{}张)",
                    tenantId, created, ybIdx >= 0 ? 1 : 0, created - (ybIdx >= 0 ? 1 : 0));
        }
    }

    /**
     * 生成单张演示收费单: 明细优先取该就诊真实处方/医嘱明细(与 CashierService.billDetail 同口径),
     * 无明细时用演示明细兜底(诊查费/血常规/药品); 医保单按 Mock 四分(基金70%/个账10%/自付20%)。
     * 收费后原生 SQL 回写 his_visit.charge_status=1(与收费流程同口径)。
     */
    private boolean createDemoBill(Long tenantId, HisVisit v, Long orgId, boolean ybFlow) {
        List<HisChargeBillItem> items = collectVisitItems(v.getId());
        if (items.isEmpty()) {
            items = buildDemoItems();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (HisChargeBillItem it : items) {
            if (it.getAmount() != null) {
                total = total.add(it.getAmount());
            }
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        HisChargeBill bill = new HisChargeBill();
        bill.setOrgId(orgId);
        bill.setBillNo(nextDemoBillNo(tenantId, "SF"));
        bill.setVisitId(v.getId());
        bill.setRegistrationId(v.getRegistrationId());
        bill.setPatientId(v.getPatientId());
        bill.setPatientName(v.getPatientName());
        bill.setBillType(1);
        bill.setTotalAmount(total);
        BigDecimal fundPay = BigDecimal.ZERO;
        BigDecimal acctPay = BigDecimal.ZERO;
        BigDecimal selfPay = total;
        if (ybFlow) {
            // Mock结算口径(与 CashierService.doCharge 一致): 基金70%/个账10%/自付20%(余数归自付)
            fundPay = total.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.HALF_UP);
            acctPay = total.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
            selfPay = total.subtract(fundPay).subtract(acctPay);
            bill.setSetlId("DEMO" + System.currentTimeMillis());
        }
        bill.setFundPay(fundPay);
        bill.setAcctPay(acctPay);
        bill.setSelfPay(selfPay);
        bill.setCashPay(selfPay);
        bill.setStatus(1);
        bill.setChargeBy("演示收费员");
        bill.setChargeTime(LocalDateTime.now());
        billMapper.insert(bill);
        for (HisChargeBillItem it : items) {
            it.setBillId(bill.getId());
            billItemMapper.insert(it);
        }
        // 回写就诊收费状态(实体无该字段, 原生SQL维护, 与 CashierService 同口径)
        jdbcTemplate.update("UPDATE his_visit SET charge_status = 1 WHERE id = ? AND tenant_id = ? AND deleted = 0",
                v.getId(), tenantId);
        return true;
    }

    /** 汇总就诊费用明细(处方药品 + 医嘱检查/治疗), 与 CashierService.billDetail 同口径 */
    private List<HisChargeBillItem> collectVisitItems(Long visitId) {
        List<HisChargeBillItem> items = new ArrayList<>();
        List<Map<String, Object>> drugRows = jdbcTemplate.queryForList(
                "SELECT pi.item_code, pi.item_name, pi.spec, pi.quantity AS qty, pi.price, pi.amount,"
                        + " COALESCE(pi.med_list_codg, dc.yb_drug_code) AS med_list_codg, IFNULL(dc.selfpay_prop, 0) AS ratio"
                        + " FROM his_prescription_item pi"
                        + " JOIN his_prescription pr ON pr.id = pi.prescription_id AND pr.deleted = 0"
                        + " LEFT JOIN his_drug_catalog dc ON dc.id = pi.drug_id"
                        + " WHERE pr.visit_id = ? AND pi.deleted = 0 ORDER BY pi.id", visitId);
        for (Map<String, Object> r : drugRows) {
            items.add(buildItem(1, "prescription_item", r));
        }
        List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                "SELECT oi.item_code, oi.item_name, oi.spec, oi.quantity AS qty, oi.price, oi.amount,"
                        + " COALESCE(oi.med_list_codg, ci.med_list_codg) AS med_list_codg, IFNULL(ci.selfpay_prop, 0) AS ratio,"
                        + " o.order_type"
                        + " FROM his_order_item oi"
                        + " JOIN his_order o ON o.id = oi.order_id AND o.deleted = 0"
                        + " LEFT JOIN his_charge_item ci ON ci.id = oi.item_id"
                        + " WHERE o.visit_id = ? AND oi.deleted = 0 ORDER BY oi.id", visitId);
        for (Map<String, Object> r : orderRows) {
            String ot = r.get("order_type") == null ? "" : r.get("order_type").toString();
            items.add(buildItem("检查".equals(ot) || "检验".equals(ot) ? 2 : 3, "order_item", r));
        }
        return items;
    }

    /** 组装明细实体(SQL 别名列取值) */
    private HisChargeBillItem buildItem(int itemType, String refType, Map<String, Object> r) {
        HisChargeBillItem it = new HisChargeBillItem();
        it.setItemType(itemType);
        it.setRefType(refType);
        it.setItemCode(str(r.get("item_code")));
        it.setItemName(str(r.get("item_name")));
        it.setSpec(str(r.get("spec")));
        it.setQty(toBd(r.get("qty")));
        it.setPrice(toBd(r.get("price")));
        it.setAmount(toBd(r.get("amount")));
        it.setMedListCodg(str(r.get("med_list_codg")));
        it.setRatio(toBd(r.get("ratio")));
        return it;
    }

    /** 演示费用明细兜底(就诊无处方/医嘱时): 诊查费/血常规/药品, 口径对齐 BasedataDemoInitializer 种子收费项目 */
    private List<HisChargeBillItem> buildDemoItems() {
        Object[][] demo = {
                {3, "ZL0001", "普通门诊诊查费", "", 1, "10.00", "01010101000000001", "0.00"},
                {2, "ZL0002", "血常规", "", 1, "25.00", "02010101000000001", "0.00"},
                {1, "YP0001", "阿莫西林胶囊", "0.25g*24粒", 2, "12.50", "XJ01CAA04501010113", "0.00"}
        };
        List<HisChargeBillItem> items = new ArrayList<>();
        for (Object[] d : demo) {
            HisChargeBillItem it = new HisChargeBillItem();
            it.setItemType((Integer) d[0]);
            it.setItemCode((String) d[1]);
            it.setItemName((String) d[2]);
            it.setSpec((String) d[3]);
            it.setQty(BigDecimal.valueOf(((Number) d[4]).longValue()));
            it.setPrice(new BigDecimal((String) d[5]));
            it.setAmount(it.getPrice().multiply(it.getQty()));
            it.setMedListCodg((String) d[6]);
            it.setRatio(new BigDecimal((String) d[7]));
            items.add(it);
        }
        return items;
    }

    /** 演示单号: 前缀+yyyyMMdd+4位序号(与 CashierService.generateBillNo 同格式), 查当天最大序号防冲突 */
    private String nextDemoBillNo(Long tenantId, String prefix) {
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(bill_no, 11) AS UNSIGNED)), 0) FROM his_charge_bill"
                        + " WHERE tenant_id = ? AND bill_no LIKE ?",
                Integer.class, tenantId, prefix + day + "%");
        int next = (max == null ? 0 : max) + 1;
        return prefix + day + String.format("%04d", next);
    }

    /* ===================== 三模块基座演示数据 (2026-09 集成联调) ===================== */

    /**
     * 危急值规则种子: 6条门诊常用通用规则(patient_type=NULL 全人群适用, 与危急值规则页同口径),
     * 阈值按集成规格(血钾2.5/6.5、血糖2.8/22.2、血小板30/1000、白细胞2.0/30、血红蛋白50/200、PT上限30)。
     * 幂等: 逐条按 租户+项目编码+通用类型 判存补种 —— 已有规则(如手工造的 K 血钾)保持不动, 缺几条补几条;
     * 项目编码与前端 MedtechCriticalRule "一键初始化"集合一致(K/GLU/PLT/WBC/HGB/PT), 保证 detectCritical 精确命中。
     */
    private void seedCriticalRules(Long tenantId) {
        Object[][] rules = {
                {"K", "血钾", "2.5", "6.5"},
                {"GLU", "血糖", "2.8", "22.2"},
                {"PLT", "血小板", "30", "1000"},
                {"WBC", "白细胞", "2.0", "30.0"},
                {"HGB", "血红蛋白", "50", "200"},
                {"PT", "凝血酶原时间", null, "30"}
        };
        int added = 0;
        for (Object[] r : rules) {
            String code = (String) r[0];
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_critical_rule"
                            + " WHERE tenant_id = ? AND item_code = ? AND patient_type IS NULL AND deleted = 0",
                    Integer.class, tenantId, code);
            if (exists != null && exists > 0) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO his_critical_rule (tenant_id, item_code, item_name, low_threshold, high_threshold,"
                            + " patient_type, is_active, create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (?, ?, ?, ?, ?, NULL, 1, 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                    tenantId, code, r[1], toBd(r[2]), toBd(r[3]));
            added++;
        }
        if (added > 0) {
            log.info("租户[{}] 危急值规则种子: 补种 {} 条门诊通用规则(已有规则保持不动)", tenantId, added);
        }
    }

    /**
     * 护士站演示数据: 取一条已付费输液/注射医嘱生成完整闭环样本 ——
     * 青霉素皮试(阳性→通知医生→同步过敏档案) + 换药后静脉输液(穿刺/巡视/拔针, 双人核对)。
     * 幂等: 该租户已有执行记录则跳过; 无已付费医嘱/无职工则跳过(基础数据就绪后重启补种)。
     * 执行单号用 9001+ 序号段, 与 NurseExecService.generateExecNo 当日回读逻辑兼容不撞号。
     */
    private void seedSampleNurseData(Long tenantId) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_nurse_exec WHERE tenant_id = ? AND deleted = 0", Integer.class, tenantId);
        if (existing != null && existing > 0) {
            return; // 已有执行数据(护士站已被使用/已种过), 不重复种
        }
        Map<String, Object> od = jdbcTemplate.queryForList(
                "SELECT o.id AS orderId, o.order_no AS orderNo, o.visit_id AS visitId, o.patient_id AS patientId,"
                        + " o.patient_name AS patientName, oi.id AS itemId, oi.item_name AS itemName,"
                        + " v.dept_id AS deptId, d.org_id AS orgId"
                        + " FROM his_order o"
                        + " JOIN his_order_item oi ON oi.order_id = o.id AND oi.deleted = 0"
                        + " JOIN his_visit v ON v.id = o.visit_id AND v.deleted = 0"
                        + " JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0"
                        + " WHERE o.tenant_id = ? AND o.deleted = 0 AND o.paid_flag = 1 AND o.status > 0"
                        + " AND (oi.item_name LIKE ? OR oi.item_name LIKE ?)"
                        + " ORDER BY o.id DESC LIMIT 1",
                tenantId, "%输液%", "%注射%").stream().findFirst().orElse(null);
        if (od == null) {
            return; // 无已付费注射/输液医嘱(医生站未使用), 下次启动补
        }
        Long orgId = toLong(od.get("orgId"));
        List<Long> nurses = pickDemoStaff(tenantId, orgId, 2);
        if (nurses.size() < 2) {
            return; // 无职工(RBAC未建/未排班), 下次启动补
        }
        Long nurseExec = nurses.get(0);
        Long nurseCheck = nurses.get(1);
        Long orderId = toLong(od.get("orderId"));
        Long visitId = toLong(od.get("visitId"));
        Long patientId = toLong(od.get("patientId"));
        Long itemId = toLong(od.get("itemId"));
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // 1) 青霉素皮试(阳性闭环): 执行单 + 皮试记录(观察窗20分钟, 阳性通知医生并确认)
        LocalDateTime t0 = LocalDateTime.now().minusHours(3);
        String skinNo = "HS" + day + "9001";
        jdbcTemplate.update(
                "INSERT INTO his_nurse_exec (tenant_id, org_id, visit_id, order_id, order_item_id, patient_id,"
                        + " exec_type, exec_no, exec_status, exec_nurse_id, exec_time, end_time, patient_response,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, ?, ?, NULL, ?, 'skin_test', ?, 2, ?, ?, ?, ?,"
                        + " 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                tenantId, orgId, visitId, orderId, patientId, skinNo, nurseExec, t0, t0.plusMinutes(20),
                "皮试阳性, 已通知医生并换用非青霉素类药物");
        Long skinExecId = jdbcTemplate.queryForObject(
                "SELECT id FROM his_nurse_exec WHERE tenant_id = ? AND exec_no = ?", Long.class, tenantId, skinNo);
        jdbcTemplate.update(
                "INSERT INTO his_skin_test (tenant_id, exec_id, drug_name, drug_id, test_dose, observe_start, observe_end,"
                        + " result, result_desc, notify_doctor_time, doctor_confirm_time,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, '青霉素', NULL, '0.1ml(含青霉素500U)', ?, ?, 2, ?, ?, ?,"
                        + " 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                tenantId, skinExecId, t0, t0.plusMinutes(20),
                "皮丘红晕, 硬结直径1.2cm, 伴伪足, 判为阳性", t0.plusMinutes(20), t0.plusMinutes(25));
        Long skinId = jdbcTemplate.queryForObject(
                "SELECT id FROM his_skin_test WHERE tenant_id = ? AND exec_id = ?", Long.class, tenantId, skinExecId);

        // 2) 皮试阳性同步过敏档案(医生站开单过敏拦截/护士站过敏档案页演示数据), 幂等: 同患者同过敏原只登记一条
        Integer allergyExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_patient_allergy WHERE tenant_id = ? AND patient_id = ?"
                        + " AND allergen_name = ? AND deleted = 0",
                Integer.class, tenantId, patientId, "青霉素");
        if (allergyExists != null && allergyExists == 0) {
            jdbcTemplate.update(
                    "INSERT INTO his_patient_allergy (tenant_id, patient_id, allergen_type, allergen_name, allergen_code,"
                            + " severity, source, source_id, record_time, record_by, is_active,"
                            + " create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (?, ?, 'drug', '青霉素', NULL, 'severe', 'skin_test', ?, ?, ?, 1,"
                            + " 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                    tenantId, patientId, skinId, t0.plusMinutes(20), nurseExec);
        }

        // 3) 换药后静脉输液完成闭环: 执行单(高危双人核对) + 输液记录(穿刺/巡视/拔针)
        LocalDateTime i0 = LocalDateTime.now().minusHours(2);
        String infNo = "HS" + day + "9002";
        jdbcTemplate.update(
                "INSERT INTO his_nurse_exec (tenant_id, org_id, visit_id, order_id, order_item_id, patient_id,"
                        + " exec_type, exec_no, exec_status, exec_nurse_id, verify_nurse_id, exec_time, end_time, patient_response,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'infusion', ?, 2, ?, ?, ?, ?, ?,"
                        + " 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                tenantId, orgId, visitId, orderId, itemId, patientId, infNo, nurseExec, nurseCheck,
                i0, i0.plusHours(1), "输注顺利, 无不良反应");
        Long infExecId = jdbcTemplate.queryForObject(
                "SELECT id FROM his_nurse_exec WHERE tenant_id = ? AND exec_no = ?", Long.class, tenantId, infNo);
        String patrol = "[{\"time\":\"" + i0.plusMinutes(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + "\",\"dripRate\":60,\"status\":\"正常\",\"note\":\"滴速正常, 无不适\"}]";
        jdbcTemplate.update(
                "INSERT INTO his_infusion_record (tenant_id, exec_id, seat_no, solution, drip_rate, puncture_time,"
                        + " puncture_site, puncture_nurse_id, remove_time, remove_nurse_id, patrol_records,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, 'A03', '0.9%氯化钠注射液 250ml', 60, ?, '左手背', ?, ?, ?, ?,"
                        + " 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                tenantId, infExecId, i0, nurseExec, i0.plusHours(1), nurseCheck, patrol);
        log.info("租户[{}] 护士站演示数据: 皮试(阳性)+输液完成闭环 x1 (患者{}, 医嘱{})",
                tenantId, od.get("patientName"), od.get("orderNo"));
    }

    /**
     * 治疗演示数据: 取一条已付费治疗类医嘱生成疗程样本 ——
     * 进行中疗程计划(已完成1次/共N次, N不足3按3演示保证进行中可见) + 第1次已完成执行 + 第2次待执行。
     * 幂等: 该租户已有治疗计划则跳过(治疗模块业务/冒烟数据不覆盖); 无治疗类医嘱则跳过。
     */
    private void seedSampleTreatmentData(Long tenantId) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_treatment_plan WHERE tenant_id = ? AND deleted = 0", Integer.class, tenantId);
        if (existing != null && existing > 0) {
            return; // 已有疗程数据(含进行中疗程), 不重复种
        }
        Map<String, Object> od = jdbcTemplate.queryForList(
                "SELECT o.id AS orderId, o.dr_id AS doctorId, o.visit_id AS visitId, o.patient_id AS patientId,"
                        + " oi.id AS itemId, oi.item_name AS itemName, oi.quantity AS quantity, d.org_id AS orgId"
                        + " FROM his_order o"
                        + " JOIN his_order_item oi ON oi.order_id = o.id AND oi.deleted = 0"
                        + " JOIN his_visit v ON v.id = o.visit_id AND v.deleted = 0"
                        + " JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0"
                        + " WHERE o.tenant_id = ? AND o.deleted = 0 AND o.paid_flag = 1 AND o.status > 0"
                        + " AND (oi.item_name LIKE ? OR oi.item_name LIKE ? OR oi.item_name LIKE ?"
                        + " OR oi.item_name LIKE ? OR oi.item_name LIKE ? OR oi.item_name LIKE ?)"
                        + " ORDER BY o.id DESC LIMIT 1",
                tenantId, "%针灸%", "%推拿%", "%理疗%", "%脉冲%", "%牵引%", "%拔罐%")
                .stream().findFirst().orElse(null);
        if (od == null) {
            return; // 无治疗类医嘱, 下次启动补
        }
        Long orgId = toLong(od.get("orgId"));
        List<Long> staff = pickDemoStaff(tenantId, orgId, 1);
        if (staff.isEmpty()) {
            return; // 无职工, 下次启动补
        }
        String itemName = str(od.get("itemName"));
        BigDecimal qty = toBd(od.get("quantity"));
        int total = qty == null ? 3 : Math.max(1, qty.intValue());
        if (total < 2) {
            total = 3; // 疗程演示至少多次, 保证"进行中"状态可见
        }
        String category = (itemName.contains("针灸") || itemName.contains("推拿") || itemName.contains("拔罐"))
                ? "tcm" : "physiotherapy";
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String planNo = "ZL" + day + "9001";
        // 疗程计划: 进行中(已完成1次/共total次, 昨起每日1次)
        jdbcTemplate.update(
                "INSERT INTO his_treatment_plan (tenant_id, org_id, plan_no, visit_id, patient_id, doctor_id, order_id,"
                        + " item_code, item_name, category, total_sessions, completed_sessions, frequency, start_date,"
                        + " expire_date, status, remark, create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, 1, '每日1次', ?, ?, 0, '演示疗程计划',"
                        + " 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                tenantId, orgId, planNo, toLong(od.get("visitId")), toLong(od.get("patientId")),
                toLong(od.get("doctorId")), toLong(od.get("orderId")), itemName, category, total,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(29));
        Long planId = jdbcTemplate.queryForObject(
                "SELECT id FROM his_treatment_plan WHERE tenant_id = ? AND plan_no = ?", Long.class, tenantId, planNo);
        String equipCode = jdbcTemplate.queryForList(
                "SELECT equip_code FROM his_treatment_equipment WHERE tenant_id = ? AND org_id = ? AND deleted = 0"
                        + " ORDER BY id LIMIT 1",
                tenantId, orgId).stream().findFirst().map(r -> str(r.get("equip_code"))).orElse(null);
        // 第1次: 已完成留痕(签到/治疗师/设备/时长/反应)
        jdbcTemplate.update(
                "INSERT INTO his_treatment_exec (tenant_id, org_id, exec_no, plan_id, order_id, order_item_id, patient_id,"
                        + " session_index, exec_date, exec_therapist_id, equipment_code, duration_min, patient_response,"
                        + " checkin_time, exec_status, create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 30, '治疗过程顺利, 无不适', ?, 2,"
                        + " 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                tenantId, orgId, "ZX" + day + "9001", planId, toLong(od.get("orderId")), toLong(od.get("itemId")),
                toLong(od.get("patientId")), LocalDate.now().minusDays(1), staff.get(0), equipCode,
                LocalDate.now().minusDays(1).atTime(9, 0));
        // 第2次: 待执行(治疗工作台待办演示)
        jdbcTemplate.update(
                "INSERT INTO his_treatment_exec (tenant_id, org_id, exec_no, plan_id, order_id, order_item_id, patient_id,"
                        + " session_index, exec_date, exec_therapist_id, exec_status,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 2, ?, ?, 0, 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                tenantId, orgId, "ZX" + day + "9002", planId, toLong(od.get("orderId")), toLong(od.get("itemId")),
                toLong(od.get("patientId")), LocalDate.now(), staff.get(0));
        log.info("租户[{}] 治疗演示数据: 疗程计划[{}] x1 (进行中 1/{}) + 执行记录 x2", tenantId, itemName, total);
    }

    /**
     * 危急值待确认样本: 复用已审核危急报告构造 status=2(已通知待临床接收)记录,
     * 供 Dashboard "危急值待确认"徽章与危急值管理页接收→处置闭环演示。
     * 幂等: 已有待确认记录则跳过; 无危急报告则跳过; 优先取尚无危急值单的报告(真实首报),
     * 全部已建单时取最新报告模拟"复测再危急"场景(不影响 detectCritical 防重复建单口径)。
     */
    private void seedSampleCriticalPending(Long tenantId) {
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_critical_value WHERE tenant_id = ? AND status = 2 AND deleted = 0",
                Integer.class, tenantId);
        if (pending != null && pending > 0) {
            return; // 已有待确认危急值(业务进行中), 不重复种
        }
        String base = " FROM his_exam_report r"
                + " JOIN his_exam_result_item i ON i.report_id = r.id AND i.deleted = 0"
                + " WHERE r.tenant_id = ? AND r.deleted = 0 AND r.status = 2 AND r.critical_flag = 1";
        String cols = "SELECT r.id AS reportId, r.order_id AS orderId, r.patient_id AS patientId, r.org_id AS orgId,"
                + " i.item_code AS itemCode, i.item_name AS itemName, i.result_value AS resultValue";
        Map<String, Object> rpt = jdbcTemplate.queryForList(cols + base
                + " AND NOT EXISTS (SELECT 1 FROM his_critical_value cv WHERE cv.tenant_id = r.tenant_id"
                + " AND cv.report_id = r.id AND cv.item_code = i.item_code AND cv.deleted = 0)"
                + " ORDER BY r.id DESC, i.id DESC LIMIT 1", tenantId)
                .stream().findFirst().orElse(null);
        if (rpt == null) {
            // 演示库常见: 危急报告均已建单(医技站已走过闭环), 取最新报告模拟复测再危急
            rpt = jdbcTemplate.queryForList(cols + base
                    + " ORDER BY r.id DESC, i.id DESC LIMIT 1", tenantId)
                    .stream().findFirst().orElse(null);
        }
        if (rpt == null) {
            return; // 无已审核危急报告(医技站未使用), 下次启动补
        }
        Long orgId = toLong(rpt.get("orgId"));
        List<Long> staff = pickDemoStaff(tenantId, orgId, 1);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO his_critical_value (tenant_id, org_id, report_id, order_id, patient_id, item_code, item_name,"
                        + " result_value, discover_time, verify_tech_id, verify_time, notify_time, notify_target, status,"
                        + " create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '门诊接诊医生(电话通知)', 2,"
                        + " 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                tenantId, orgId, toLong(rpt.get("reportId")), toLong(rpt.get("orderId")), toLong(rpt.get("patientId")),
                str(rpt.get("itemCode")), str(rpt.get("itemName")), str(rpt.get("resultValue")),
                now.minusMinutes(40), staff.isEmpty() ? null : staff.get(0), now.minusMinutes(30), now.minusMinutes(20));
        log.info("租户[{}] 危急值待确认样本 x1 ({} {}={}), 供 Dashboard 徽章与接收闭环演示",
                tenantId, str(rpt.get("itemName")), str(rpt.get("itemCode")), str(rpt.get("resultValue")));
    }

    /** 演示职工(护士/治疗师/技师): 职称或姓名含"护"者优先, 否则取 id 最大者(避开主诊排班医生段); 异常回退通用查询 */
    private List<Long> pickDemoStaff(Long tenantId, Long orgId, int n) {
        List<Long> ids = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM his_staff WHERE tenant_id = ? AND org_id = ? AND deleted = 0"
                            + " AND (title_name LIKE '%护%' OR staff_name LIKE '%护%') ORDER BY id LIMIT ?",
                    tenantId, orgId, n);
            if (!rows.isEmpty()) {
                for (Map<String, Object> r : rows) {
                    ids.add(toLong(r.get("id")));
                }
                return ids;
            }
        } catch (Exception e) {
            // title_name 列不存在等场景回退通用查询
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM his_staff WHERE tenant_id = ? AND org_id = ? AND deleted = 0 ORDER BY id DESC LIMIT ?",
                tenantId, orgId, n);
        for (Map<String, Object> r : rows) {
            ids.add(toLong(r.get("id")));
        }
        return ids;
    }

    /** SQL 行值转 Long(BIGINT→Long / DECIMAL→Long.valueOf) */
    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Long ? (Long) v : Long.valueOf(v.toString());
    }

    /** 取当前租户牵头机构(sys_org is_lead=1, 无牵头取最小id); 无机构返回 null */
    private Long leadOrgId() {
        List<SysOrg> orgs = orgMapper.selectList(new QueryWrapper<SysOrg>().orderByAsc("id"));
        for (SysOrg o : orgs) {
            if (o.getIsLead() != null && o.getIsLead() == 1) {
                return o.getId();
            }
        }
        return orgs.isEmpty() ? null : orgs.get(0).getId();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }
}
