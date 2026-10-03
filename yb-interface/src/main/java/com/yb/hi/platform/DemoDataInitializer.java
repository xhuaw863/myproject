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
import com.yb.hi.service.inpatient.EmrQualityService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 演示数据初始化: 应用启动时幂等创建演示医院(租户)与管理员账号
 * 用于多租户隔离验证与开发调试。可通过 his.demo-data.enabled=false 关闭。
 * 2026-09 扩展: 药品库存演示(药库库存/低库存预警测试) + 演示收费单(报表/Dashboard数据)。
 * 2026-09 集成扩展(三模块基座): 危急值规则(6条门诊通用阈值) + 护士站执行记录(含皮试阳性同步过敏档案)
 * + 治疗计划(进行中疗程) + 危急值待确认记录(Dashboard徽章演示)。
 * 2026-10 扩展: CDSS 临床决策支持规则种子(8条: 主诉超长/性别描述不符/阿司匹林禁忌/重复检查/危急值未处置/诊断必填/补钾浓度/高血压血压)。
 * 2026-10 EMR扩展: 病历NLG生成模板种子(4条常用章节: 主诉/现病史/既往史/体格检查) + 医学图示模板(SVG标注底图)。
 * 2026-10 病历P2基座: 病历签名规则链种子(15类文书 author/resident/attending/director 链) + 病历常用语种子(14条全局)。
 * 2026-10 病历P5a: 病历质控评分标准种子(卫健委五类20条: 时效/完整/逻辑/规范/内涵)。
 * 2026-10 病历P5a-3: 内涵质控规则种子(400+条: 五大类型矩阵 item_value/item_compare/disease/calculation/event + 病种专项)。
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
    private final EmrQualityService emrQualityService;

    @Value("${his.demo-data.enabled:true}")
    private boolean enabled;

    public DemoDataInitializer(SysTenantService tenantService, AuthService authService,
                               HisDrugStockMapper stockMapper, HisDrugCatalogMapper drugCatalogMapper,
                               HisChargeBillMapper billMapper, HisChargeBillItemMapper billItemMapper,
                               HisVisitMapper visitMapper, SysOrgMapper orgMapper,
                               JdbcTemplate jdbcTemplate, WarehouseDefService warehouseDefService,
                               PharmacyDefService pharmacyDefService, EmrQualityService emrQualityService) {
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
        this.emrQualityService = emrQualityService;
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
                // 医学图示模板种子(SVG 标注底图): 独立 try 防表未建时拖累其他种子
                try {
                    seedDrawingTemplates(t.getId());
                } catch (Exception e) {
                    log.warn("租户[{}] 医学图示模板种子跳过(可能表未建): {}", t.getId(), e.getMessage());
                }
                // 病历NLG生成模板种子(主诉/现病史/既往史/体格检查): 独立 try 防表未建时拖累其他种子
                try {
                    seedNlgTemplates(t.getId());
                } catch (Exception e) {
                    log.warn("租户[{}] 病历NLG生成模板种子跳过(可能表未建): {}", t.getId(), e.getMessage());
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
                }
                // 病历P2基座种子(签名规则链15类+常用语14条): 独立 try 防表未建时拖累其他种子
                try {
                    seedSignatureRules(t.getId());
                    seedEmrPhrases(t.getId());
                } catch (Exception e) {
                    log.warn("租户[{}] 病历签名规则/常用语种子跳过(可能表未建): {}", t.getId(), e.getMessage());
                }
                // 病历质控评分标准种子(P5a, 卫健委五类20条): 独立 try 防表未建时拖累其他种子
                try {
                    seedScoreStandards(t.getId());
                } catch (Exception e) {
                    log.warn("租户[{}] 病历评分标准种子跳过(可能表未建): {}", t.getId(), e.getMessage());
                }
                // CDSS 临床决策支持规则种子(8条, 五类): 独立 try 防表未建拖累其他种子
                try {
                    seedCdssRules(t.getId());
                } catch (Exception e) {
                    log.warn("租户[{}] CDSS规则种子跳过(可能表未建): {}", t.getId(), e.getMessage());
                }
                // 病历内涵质控规则种子(P5a-3, 400+条五大类型矩阵+病种专项): 独立 try 防表未建拖累其他种子,
                // 逐条幂等(按 rule_code 判存含墓碑), 已有编码保持手工修改不动
                try {
                    emrQualityService.seedContentRules(t.getId());
                } catch (Exception e) {
                    log.warn("租户[{}] 内涵质控规则种子跳过(可能表未建): {}", t.getId(), e.getMessage());
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
     * 系统参数全局种子: 9 个参数分组(sys_param_group) + 30 条参数定义(sys_param 定义行 scope_level=0,
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
                {"medtech", "医技管理", 8, "标本/报告/危急值参数"},
                {"emr", "电子病历", 9, "电子病历与CDSS参数"}
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
                {"medtech", "medtech.specimen_barcode_prefix", "标本条码前缀", "string", "BB", null, null, null, 0, "0,1", "标本条码生成前缀"},
                {"emr", "emr.encryption.key", "EMR病历加密密钥", "string", "5f8a2c4e9b1d7f3a6e0c8b2d4f6a8c1e", null, null, null, 1, "0", "病历内容AES-256加密密钥(32位hex), 仅全局作用域维护, 变更需评估存量密文重加密"}
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
     * CDSS 临床决策支持规则种子(8条, 覆盖五类): drug_conflict(阿司匹林+消化道出血→block)/
     * dose_alert(静脉补钾浓度超限→warning)/repeat_exam(7天内重复检查→warning)/critical_value(危急值未处置→block)/
     * guideline(主诉超长/性别描述不符/诊断必填/高血压未记录血压)。
     * condition_expr 按 CdssEngineService 评估口径书写(eq/ne/gt/lt/contains/empty/notEmpty/in + and/or 组合,
     * 另扩展 lenGt/lenLt 文本长度); action_message 含 {fieldKey} 占位符, 评估时以命中字段值插值。
     * applicable_depts 一律放空=全院适用(科室级限定由管理员在规则维护页配置)。
     * 幂等: 逐条按 租户+规则编码 判存补种, 已有规则(含手工调整)保持不动; 机构未建则跳过, 下次启动补种。
     * 字段键与种子模板口径对齐(chiefComplaint/presentIllness/pastHistory/diagnosis/vitals 等); gender/medications/
     * duplicateExam/criticalValue/kclConcentration 等为调用方(医生站)评估时注入的上下文字段。
     */
    private void seedCdssRules(Long tenantId) {
        Long orgId = leadOrgId();
        if (orgId == null) {
            return; // 机构未建(RBAC初始化未执行), 下次启动补种
        }
        Object[][] rules = {
                {"chief_complaint_length", "主诉超长提醒", "guideline",
                        "{\"field\":\"chiefComplaint\",\"op\":\"lenGt\",\"value\":\"20\"}",
                        "主诉超过20字, 请按'症状+持续时间'精简描述(当前主诉:{chiefComplaint})",
                        "info", "《病历书写基本规范》", 10},
                {"gender_mismatch", "性别描述不符提醒", "guideline",
                        "{\"and\":[{\"field\":\"gender\",\"op\":\"eq\",\"value\":\"男\"},{\"or\":[{\"field\":\"presentIllness\",\"op\":\"contains\",\"value\":\"月经\"},{\"field\":\"presentIllness\",\"op\":\"contains\",\"value\":\"妊娠\"},{\"field\":\"pastHistory\",\"op\":\"contains\",\"value\":\"月经\"}]}]}",
                        "患者性别为{gender}, 病历中出现女性生理相关描述(月经/妊娠/经期), 请再次核对病史采集内容",
                        "warning", "病历质控规则库", 20},
                {"drug_contraindication_asa", "阿司匹林禁忌使用提示", "drug_conflict",
                        "{\"and\":[{\"field\":\"medications\",\"op\":\"contains\",\"value\":\"阿司匹林\"},{\"or\":[{\"field\":\"diagnosis\",\"op\":\"contains\",\"value\":\"消化道出血\"},{\"field\":\"admitDiagnosis\",\"op\":\"contains\",\"value\":\"消化道出血\"}]}]}",
                        "诊断提示消化道出血, 阿司匹林为禁忌用药, 请调整用药方案后再保存/签署",
                        "block", "阿司匹林说明书·禁忌", 30},
                {"duplicate_exam_7d", "7天内重复检查提醒", "repeat_exam",
                        "{\"field\":\"duplicateExam\",\"op\":\"notEmpty\"}",
                        "7天内已开具相同检查({duplicateExam}), 请确认检查必要性",
                        "warning", "合理检查质控规则", 40},
                {"critical_value_unhandled", "危急值未处置阻断", "critical_value",
                        "{\"and\":[{\"field\":\"criticalValue\",\"op\":\"notEmpty\"},{\"field\":\"criticalValueHandled\",\"op\":\"ne\",\"value\":\"Y\"}]}",
                        "存在未处置的危急值({criticalValue}), 请完成危急值处置并记录后再保存/签署",
                        "block", "危急值报告制度", 50},
                {"diagnosis_required", "诊断必填提醒", "guideline",
                        "{\"field\":\"diagnosis\",\"op\":\"empty\"}",
                        "门诊诊断为必填项, 请先补录诊断后再保存",
                        "warning", "《病历书写基本规范》", 60},
                {"dose_alert_kcl", "静脉补钾浓度预警", "dose_alert",
                        "{\"field\":\"kclConcentration\",\"op\":\"gt\",\"value\":\"3\"}",
                        "静脉补钾浓度{kclConcentration}‰超过3‰安全上限, 请稀释后使用并加强心电监护",
                        "warning", "《临床用药须知》", 70},
                {"hypertension_vitals", "高血压未记录血压提醒", "guideline",
                        "{\"and\":[{\"field\":\"diagnosis\",\"op\":\"contains\",\"value\":\"高血压\"},{\"field\":\"vitals\",\"op\":\"empty\"}]}",
                        "高血压诊断患者建议记录生命体征(血压), 便于复诊评估",
                        "info", "《中国高血压防治指南》", 80}
        };
        int added = 0;
        for (Object[] r : rules) {
            String code = (String) r[0];
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_cdss_rule WHERE tenant_id = ? AND rule_code = ? AND deleted = 0",
                    Integer.class, tenantId, code);
            if (exists != null && exists > 0) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO his_cdss_rule (tenant_id, org_id, rule_code, name, rule_type, condition_expr, action_message,"
                            + " severity, knowledge_source, applicable_depts, enabled, sort_no,"
                            + " create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?, 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                    tenantId, orgId, code, r[1], r[2], r[3], r[4], r[5], r[6], r[7]);
            added++;
        }
        if (added > 0) {
            log.info("租户[{}] CDSS规则种子: 补种 {} 条临床决策支持规则(已有规则保持不动)", tenantId, added);
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

    /* ===================== 病历NLG生成模板种子(EMR P1c) ===================== */

    /**
     * NLG 生成模板种子: 每租户 4 条常用章节模板(主诉/现病史/既往史/体格检查, scope=0 全部),
     * 供结构化字段值 → 叙述文本生成(即时预览/一键成文)。
     * 模板语法: 占位符 {fieldKey}; sort_rules 为输出语序(JSON数组); connectors 为相邻字段连接词
     * {"default":"兜底连接词","rules":[{"after":"前字段","before":"后字段","word":"连接词"}]},
     * 仅当前后字段均有值时连接词才生效(字段为空连同连接词一并跳过), 规则命中优先于模板字面量。
     * 幂等: 同租户同 section_key+scope 已存在则跳过(手工修改保持不动); 机构未建时跳过下次启动补。
     */
    private void seedNlgTemplates(Long tenantId) {
        Long orgId = leadOrgId();
        if (orgId == null) {
            return; // 机构未建(RBAC初始化未执行), 下次启动补
        }
        // 列序: {章节key, 章节名, 模板文本, 连接词JSON, 语序JSON}
        Object[][] templates = {
                {"chief_complaint", "主诉",
                        "{location}{nature}{symptom}{duration}",
                        "{\"default\":\"\"}",
                        "[\"location\",\"nature\",\"symptom\",\"duration\"]"},
                {"present_illness", "现病史",
                        "{onset}{inducement}出现{location}{symptom}，{progression}，{accompany}。{treatment}。",
                        "{\"default\":\"\",\"rules\":[{\"before\":\"treatment\",\"word\":\"，\"}]}",
                        "[\"onset\",\"inducement\",\"location\",\"symptom\",\"progression\",\"accompany\",\"treatment\"]"},
                {"past_history", "既往史",
                        "{chronicDisease}{infectiousDisease}{operationHistory}{allergyHistory}{transfusionHistory}{vaccinationHistory}。",
                        "{\"default\":\"；\"}",
                        "[\"chronicDisease\",\"infectiousDisease\",\"operationHistory\",\"allergyHistory\",\"transfusionHistory\",\"vaccinationHistory\"]"},
                {"physical_exam", "体格检查",
                        "{temperature}{pulse}{respiration}{bloodPressure}{generalCondition}{skin}{heart}{lung}{abdomen}{nervousSystem}",
                        "{\"default\":\"，\",\"rules\":[{\"before\":\"pulse\",\"word\":\" \"},{\"before\":\"respiration\",\"word\":\" \"},{\"before\":\"bloodPressure\",\"word\":\" \"},{\"before\":\"generalCondition\",\"word\":\"。\"}]}",
                        "[\"temperature\",\"pulse\",\"respiration\",\"bloodPressure\",\"generalCondition\",\"skin\",\"heart\",\"lung\",\"abdomen\",\"nervousSystem\"]"}
        };
        int added = 0;
        for (Object[] t : templates) {
            String sectionKey = (String) t[0];
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_emr_nlg_template"
                            + " WHERE tenant_id = ? AND section_key = ? AND scope = 0 AND deleted = 0",
                    Integer.class, tenantId, sectionKey);
            if (exists != null && exists > 0) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO his_emr_nlg_template (tenant_id, org_id, scope, section_key, section_name, template_text,"
                            + " connectors, sort_rules, enabled, create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (?, ?, 0, ?, ?, ?, ?, ?, 1, 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                    tenantId, orgId, sectionKey, t[1], t[2], t[3], t[4]);
            added++;
        }
        if (added > 0) {
            log.info("租户[{}] 病历NLG生成模板种子: 补种 {} 条章节模板(主诉/现病史/既往史/体格检查, 已有保持不动)",
                    tenantId, added);
        }
    }

    /* ===================== 医学图示模板种子(EMR P1b) ===================== */

    /**
     * 医学图示模板种子: 每租户 5 个基础 SVG 标注底图(正/背面人体轮廓、头面、左右手),
     * 供病历书写插入 emrDrawing 画布作背景供体表标注(简单轮廓线, 非精细解剖图)。
     * 幂等: 同租户同编码已存在则跳过(手工修改保持不动); 机构未建时跳过下次启动补。
     */
    private void seedDrawingTemplates(Long tenantId) {
        Long orgId = leadOrgId();
        if (orgId == null) {
            return; // 机构未建(RBAC初始化未执行), 下次启动补
        }
        // 列序: {编码, 名称, 类别, SVG源串, 说明}
        Object[][] templates = {
                {"SVG_BODY_FRONT", "正面人体轮廓", "body_front", svgBodyFront(), "正面全身轮廓, 供标注疼痛/皮疹/伤口等体表位置"},
                {"SVG_BODY_BACK", "背面人体轮廓", "body_back", svgBodyBack(), "背面全身轮廓(含脊柱中线), 供标注背部体表位置"},
                {"SVG_HEAD_FRONT", "头部正面", "head", svgHeadFront(), "头面部轮廓, 供标注五官/颅面区域"},
                {"SVG_ORAL", "口腔正面", "oral", svgOral(), "张口位口腔轮廓(唇/齿弓/舌/悬雍垂), 供标注口内病变"},
                {"SVG_HAND_LEFT", "左手掌面", "hand", svgHandLeft(), "左手掌面轮廓, 供标注手指/掌部皮损"},
                {"SVG_HAND_RIGHT", "右手掌面", "hand", svgHandRight(), "右手掌面轮廓, 供标注手指/掌部皮损"},
                {"SVG_FOOT", "足部轮廓", "foot", svgFoot(), "足部(足底)轮廓, 供标注足部皮损/溃疡位置"},
                {"SVG_WOUND", "伤口标注底图", "wound", svgWound(), "同心环+十字定位底图, 供标注伤口位置与范围"}
        };
        int added = 0;
        for (Object[] t : templates) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_emr_drawing_template WHERE tenant_id = ? AND code = ? AND deleted = 0",
                    Integer.class, tenantId, t[0]);
            if (exists != null && exists > 0) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO his_emr_drawing_template (tenant_id, org_id, code, title, category, svg_template, description, status,"
                            + " create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                    tenantId, orgId, t[0], t[1], t[2], t[3], t[4]);
            added++;
        }
        if (added > 0) {
            log.info("租户[{}] 医学图示模板种子: 补种 {} 个 SVG 模板(已有保持不动)", tenantId, added);
        }
    }

    /* ===================== 病历签名规则链 + 常用语种子(EMR P2) ===================== */

    /**
     * 病历签名规则链种子: 15 类文书的签名链(作者→上级→主任), stage_order 升序为签名顺序,
     * required=1 表示链上必需; title_code_min/max 为职称档位(CV08.30.005 口径: 1-2主任 3主治 4住院 5医士, 空=不限),
     * 供签署服务校验环节顺序与签署人资质。
     * 幂等: 按租户已有规则的 (record_type, stage) 判存, 已存在保持不动; 机构未建时跳过下次启动补。
     */
    private void seedSignatureRules(Long tenantId) {
        Long orgId = leadOrgId();
        if (orgId == null) {
            return; // 机构未建(RBAC初始化未执行), 下次启动补种
        }
        // 列序: {recordType, stage, stageOrder, required, titleMin, titleMax}
        Object[][] rules = {
                {1, "author", 1, 1, "4", "5"},
                {1, "attending", 2, 1, "1", "3"},
                {2, "author", 1, 1, null, null},
                {2, "attending", 2, 1, null, null},
                {3, "author", 1, 1, null, null},
                {4, "resident", 1, 1, "4", "4"},
                {4, "attending", 2, 1, "3", "3"},
                {4, "director", 3, 1, "1", "2"},
                {5, "author", 1, 1, null, null},
                {5, "attending", 2, 1, null, null},
                {6, "author", 1, 1, null, null},
                {6, "attending", 2, 1, null, null},
                {7, "author", 1, 1, null, null},
                {8, "author", 1, 1, null, null},
                {8, "attending", 2, 1, null, null},
                {9, "author", 1, 1, null, null},
                {9, "attending", 2, 1, null, null},
                {9, "director", 3, 1, null, null},
                {10, "author", 1, 1, null, null},
                {10, "attending", 2, 1, null, null},
                {11, "author", 1, 1, null, null},
                {12, "author", 1, 1, null, null},
                {12, "attending", 2, 1, null, null},
                {13, "author", 1, 1, null, null},
                {14, "author", 1, 1, null, null},
                {14, "attending", 2, 1, null, null},
                {15, "author", 1, 1, null, null}
        };
        // 一次捞出该租户已有规则键, 回程判存避免逐行查询
        List<Map<String, Object>> existRows = jdbcTemplate.queryForList(
                "SELECT record_type AS rt, stage AS stg FROM his_emr_signature_rule WHERE tenant_id = ? AND deleted = 0",
                tenantId);
        Set<String> existKeys = new HashSet<>();
        for (Map<String, Object> row : existRows) {
            existKeys.add(String.valueOf(row.get("rt")) + "|" + String.valueOf(row.get("stg")));
        }
        int added = 0;
        for (Object[] r : rules) {
            if (existKeys.contains(r[0] + "|" + r[1])) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO his_emr_signature_rule (tenant_id, org_id, record_type, stage, stage_order, required,"
                            + " title_code_min, title_code_max, create_time, update_time, deleted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)",
                    tenantId, orgId, r[0], r[1], r[2], r[3], r[4], r[5]);
            added++;
        }
        if (added > 0) {
            log.info("租户[{}] 病历签名规则链种子: 补种 {} 条签名规则(15类文书, 已有保持不动)", tenantId, added);
        }
    }

    /**
     * 病历常用语种子: 14 条全局常用语(scope=0, 主诉5/现病史3/体格检查3/处理3), "X天"为占位提示符,
     * 供医生站/护士站病历书写区按分类快速插入。
     * 幂等: 按 (category, content) 判存, 已存在保持不动; 机构未建时跳过下次启动补。
     */
    private void seedEmrPhrases(Long tenantId) {
        Long orgId = leadOrgId();
        if (orgId == null) {
            return; // 机构未建(RBAC初始化未执行), 下次启动补种
        }
        // 列序: {category, content}
        Object[][] phrases = {
                {"chief_complaint", "反复咳嗽、咳痰X天"},
                {"chief_complaint", "头晕、头痛X天"},
                {"chief_complaint", "腹痛、腹泻X天"},
                {"chief_complaint", "胸闷、气促X天"},
                {"chief_complaint", "发热X天"},
                {"present_illness", "患者于X天前无明显诱因出现上述症状"},
                {"present_illness", "病程中无发热、寒战"},
                {"present_illness", "饮食睡眠尚可，二便正常"},
                {"physical_exam", "神志清楚，精神可，查体合作"},
                {"physical_exam", "心肺听诊未闻及明显异常"},
                {"physical_exam", "腹部平软，无压痛反跳痛"},
                {"treatment", "予以对症支持治疗"},
                {"treatment", "继续当前治疗方案"},
                {"treatment", "密切观察病情变化"}
        };
        int added = 0;
        for (Object[] p : phrases) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_emr_phrase WHERE tenant_id = ? AND category = ? AND content = ? AND deleted = 0",
                    Integer.class, tenantId, p[0], p[1]);
            if (exists != null && exists > 0) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO his_emr_phrase (tenant_id, org_id, category, content, scope, dept_code, creator_id,"
                            + " usage_count, enabled, create_time, update_time, deleted)"
                            + " VALUES (?, ?, ?, ?, 0, NULL, NULL, 0, 1, NOW(), NOW(), 0)",
                    tenantId, orgId, p[0], p[1]);
            added++;
        }
        if (added > 0) {
            log.info("租户[{}] 病历常用语种子: 补种 {} 条常用语(全局作用域, 已有保持不动)", tenantId, added);
        }
    }

    /* ===================== 病历质控评分标准种子(P5a) ===================== */

    /**
     * 病历质控评分标准种子: 按卫健委电子病历应用水平(五级)建立五类 20 条分类评分标准
     * (时效5/完整5/逻辑4/规范3/内涵3), weight 权重 × base_score 基准分, 供质控评分引擎按标准打分。
     * 幂等: 按 (tenant_id, standard_code) 判存且不带 deleted 条件——唯一键 idx_standard_code 不含 deleted,
     * 软删墓碑仍占键位, 命中任何行(含墓碑)即跳过, 避免重复插入触发唯一键冲突; 机构未建时跳过下次启动补种。
     */
    private void seedScoreStandards(Long tenantId) {
        Long orgId = leadOrgId();
        if (orgId == null) {
            return; // 机构未建(RBAC初始化未执行), 下次启动补种
        }
        // 列序: {标准编码, 标准名称, 分类, 权重, 基准分, 说明}
        Object[][] standards = {
                /* ---- 时效类(5) ---- */
                {"TIMELINESS_ADMISSION", "入院记录时效", "时效", "1.0", "15", "入院记录须在患者入院后24小时内完成"},
                {"TIMELINESS_FIRST_COURSE", "首次病程时效", "时效", "1.0", "10", "首次病程记录须在患者入院后8小时内完成"},
                {"TIMELINESS_ATTENDING", "上级查房时效", "时效", "1.5", "15", "上级医师首次查房须在入院48小时内完成(一票否决项)"},
                {"TIMELINESS_OPERATION", "手术记录时效", "时效", "1.0", "10", "手术记录须在手术结束后24小时内完成"},
                {"TIMELINESS_DISCHARGE", "出院小结时效", "时效", "0.8", "10", "出院小结须在患者出院后3日内完成"},
                /* ---- 完整性类(5) ---- */
                {"COMPLETENESS_REQUIRED", "必填项完整性", "完整", "1.0", "20", "病历各文书必填项不得缺项"},
                {"COMPLETENESS_SIGNATURE", "签名完整性", "完整", "1.2", "10", "各级医师签名须完整(作者/上级/主任链)"},
                {"COMPLETENESS_DIAGNOSIS", "诊断完整性", "完整", "1.0", "10", "诊断信息(主诊断/编码/入院病情)须完整"},
                {"COMPLETENESS_ORDERS", "医嘱完整性", "完整", "0.8", "5", "医嘱记录须与诊疗过程一致且完整"},
                {"COMPLETENESS_CONSENT", "知情同意完整", "完整", "1.0", "5", "知情同意书须完整(谈话/签字/时间)"},
                /* ---- 逻辑性类(4) ---- */
                {"LOGIC_DATE_SEQUENCE", "日期逻辑", "逻辑", "1.0", "10", "关键时间节点须满足先后顺序(入院→查房→手术→出院)"},
                {"LOGIC_DIAG_MATCH", "诊断前后一致", "逻辑", "1.0", "10", "入院/病程/出院诊断须前后一致或记录变更依据"},
                {"LOGIC_AGE_DISEASE", "年龄疾病合理", "逻辑", "0.8", "5", "诊断与患者年龄/性别须合理匹配"},
                {"LOGIC_TREATMENT_DIAG", "治疗诊断吻合", "逻辑", "1.0", "5", "治疗/用药须与诊断相符"},
                /* ---- 规范性类(3) ---- */
                {"FORMAT_TERMINOLOGY", "医学术语规范", "规范", "0.6", "5", "须使用规范医学术语, 避免口语化表述"},
                {"FORMAT_RECORD_STRUCTURE", "病历结构规范", "规范", "0.8", "5", "文书章节结构须符合规范要求"},
                {"FORMAT_ABBREVIATION", "缩写规范", "规范", "0.5", "5", "医学缩写须规范且首次出现给出全称"},
                /* ---- 内涵类(3) ---- */
                {"CONTENT_CLINICAL_LOGIC", "临床逻辑", "内涵", "1.2", "15", "病情分析/诊治措施须体现临床思维逻辑"},
                {"CONTENT_EVIDENCE_BASED", "循证医学", "内涵", "1.0", "10", "诊疗决策须有循证依据支持"},
                {"CONTENT_DIFFERENTIAL", "鉴别诊断充分", "内涵", "1.0", "10", "鉴别诊断须充分并有分析依据"}
        };
        // 一次捞出该租户已有标准编码(含软删墓碑, 唯一键不含 deleted), 回程判存避免逐行查询
        List<Map<String, Object>> existRows = jdbcTemplate.queryForList(
                "SELECT standard_code AS sc FROM his_emr_score_standard WHERE tenant_id = ?", tenantId);
        Set<String> existCodes = new HashSet<>();
        for (Map<String, Object> row : existRows) {
            existCodes.add(String.valueOf(row.get("sc")));
        }
        int added = 0;
        for (Object[] s : standards) {
            if (existCodes.contains(s[0])) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO his_emr_score_standard (tenant_id, org_id, standard_code, standard_name, category,"
                            + " base_score, weight, description, status, create_by, create_time, update_by, update_time, deleted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'demo-seed', NOW(), 'demo-seed', NOW(), 0)",
                    tenantId, orgId, s[0], s[1], s[2], new BigDecimal((String) s[4]), new BigDecimal((String) s[3]), s[5]);
            added++;
        }
        if (added > 0) {
            log.info("租户[{}] 病历质控评分标准种子: 补种 {} 条评分标准(卫健委五类, 已有保持不动)", tenantId, added);
        }
    }

    /** 正面人体轮廓(头/躯干/四肢简单轮廓线) */
    private static String svgBodyFront() {
        return String.join("\n",
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 220 520' width='220' height='520'>",
                "  <g fill='#fff' stroke='#2f3640' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'>",
                "    <circle cx='110' cy='52' r='34'/>",
                "    <path d='M96 86 L96 112 M124 86 L124 112'/>",
                "    <path d='M58 132 Q76 112 96 112 L124 112 Q144 112 162 132 Q170 198 152 252 L68 252 Q50 198 58 132 Z'/>",
                "    <path d='M58 132 Q44 192 40 252 L36 356'/>",
                "    <path d='M162 132 Q176 192 180 252 L184 356'/>",
                "    <path d='M36 356 Q34 400 40 436 M184 356 Q186 400 180 436'/>",
                "    <path d='M90 252 Q86 360 82 468'/>",
                "    <path d='M130 252 Q134 360 138 468'/>",
                "    <path d='M72 468 L94 468 M126 468 L148 468'/>",
                "  </g>",
                "</svg>");
    }

    /** 背面人体轮廓(与正面同轮廓, 虚线脊柱中线 + 肩胛示意) */
    private static String svgBodyBack() {
        return String.join("\n",
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 220 520' width='220' height='520'>",
                "  <g fill='#fff' stroke='#2f3640' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'>",
                "    <circle cx='110' cy='52' r='34'/>",
                "    <path d='M96 86 L96 112 M124 86 L124 112'/>",
                "    <path d='M58 132 Q76 112 96 112 L124 112 Q144 112 162 132 Q170 198 152 252 L68 252 Q50 198 58 132 Z'/>",
                "    <path d='M58 132 Q44 192 40 252 L36 356'/>",
                "    <path d='M162 132 Q176 192 180 252 L184 356'/>",
                "    <path d='M36 356 Q34 400 40 436 M184 356 Q186 400 180 436'/>",
                "    <path d='M90 252 Q86 360 82 468'/>",
                "    <path d='M130 252 Q134 360 138 468'/>",
                "    <path d='M72 468 L94 468 M126 468 L148 468'/>",
                "    <path d='M110 120 L110 246' stroke-dasharray='6 8'/>",
                "    <path d='M80 148 Q90 162 100 170 M140 148 Q130 162 120 170' stroke-dasharray='6 8'/>",
                "  </g>",
                "</svg>");
    }

    /** 头面部正面(脸/耳/五官定位/发际线/颈部) */
    private static String svgHeadFront() {
        return String.join("\n",
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 220 260' width='220' height='260'>",
                "  <g fill='#fff' stroke='#2f3640' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'>",
                "    <ellipse cx='110' cy='114' rx='62' ry='78'/>",
                "    <path d='M48 100 Q42 122 50 142'/>",
                "    <path d='M172 100 Q178 122 170 142'/>",
                "    <path d='M82 112 Q88 118 94 112'/>",
                "    <path d='M126 112 Q132 118 138 112'/>",
                "    <path d='M110 126 L110 160 Q110 168 101 168'/>",
                "    <path d='M90 196 Q110 208 130 196'/>",
                "    <path d='M76 56 Q110 32 144 56'/>",
                "    <path d='M96 192 L96 226 M124 192 L124 226'/>",
                "    <path d='M74 226 Q110 214 146 226'/>",
                "  </g>",
                "</svg>");
    }

    /** 左手掌面(拇指在右侧, 四指朝上) */
    private static String svgHandLeft() {
        return String.join("\n",
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 200 260' width='200' height='260'>",
                "  <g fill='#fff' stroke='#2f3640' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'>",
                "    <path d='M66 152 L62 56'/>",
                "    <path d='M88 154 L86 40'/>",
                "    <path d='M110 154 L112 44'/>",
                "    <path d='M132 152 L138 64'/>",
                "    <path d='M146 168 Q178 156 170 120'/>",
                "    <path d='M58 150 Q60 210 100 224 Q140 210 146 152'/>",
                "    <path d='M84 222 L78 252 M116 222 L122 252'/>",
                "  </g>",
                "</svg>");
    }

    /** 右手掌面(左手镜像, 拇指在左侧) */
    private static String svgHandRight() {
        return String.join("\n",
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 200 260' width='200' height='260'>",
                "  <g fill='#fff' stroke='#2f3640' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'>",
                "    <path d='M134 152 L138 56'/>",
                "    <path d='M112 154 L114 40'/>",
                "    <path d='M90 154 L88 44'/>",
                "    <path d='M68 152 L62 64'/>",
                "    <path d='M54 168 Q22 156 30 120'/>",
                "    <path d='M142 150 Q140 210 100 224 Q60 210 54 152'/>",
                "    <path d='M116 222 L122 252 M84 222 L78 252'/>",
                "  </g>",
                "</svg>");
    }

    /** 口腔正面(张口位: 唇/齿弓/舌/悬雍垂) */
    private static String svgOral() {
        return String.join("\n",
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 220 260' width='220' height='260'>",
                "  <g fill='#fff' stroke='#2f3640' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'>",
                "    <ellipse cx='110' cy='130' rx='70' ry='90'/>",
                "    <ellipse cx='110' cy='132' rx='54' ry='74'/>",
                "    <path d='M62 100 Q110 78 158 100'/>",
                "    <path d='M62 166 Q110 188 158 166'/>",
                "    <ellipse cx='110' cy='166' rx='26' ry='14'/>",
                "    <circle cx='110' cy='88' r='6'/>",
                "  </g>",
                "</svg>");
    }

    /** 足部轮廓(足底: 足掌+足跟+五趾) */
    private static String svgFoot() {
        return String.join("\n",
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 200 300' width='200' height='300'>",
                "  <g fill='#fff' stroke='#2f3640' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'>",
                "    <path d='M72 112 Q60 176 68 226 Q74 262 100 262 Q126 262 132 226 Q140 176 128 112 Q122 86 100 86 Q78 86 72 112 Z'/>",
                "    <ellipse cx='66' cy='70' rx='14' ry='18'/>",
                "    <ellipse cx='94' cy='52' rx='12' ry='16'/>",
                "    <ellipse cx='118' cy='54' rx='11' ry='15'/>",
                "    <ellipse cx='140' cy='64' rx='10' ry='14'/>",
                "    <ellipse cx='158' cy='82' rx='9' ry='12'/>",
                "  </g>",
                "</svg>");
    }

    /** 伤口标注底图(同心环 + 十字定位, 供标注伤口位置与范围) */
    private static String svgWound() {
        return String.join("\n",
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 240 240' width='240' height='240'>",
                "  <g fill='#fff' stroke='#2f3640' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'>",
                "    <circle cx='120' cy='120' r='96' stroke-dasharray='6 8'/>",
                "    <circle cx='120' cy='120' r='64'/>",
                "    <circle cx='120' cy='120' r='32' stroke-dasharray='6 8'/>",
                "    <path d='M120 12 L120 228 M12 120 L228 120'/>",
                "    <path d='M120 12 L112 26 M120 12 L128 26 M120 228 L112 214 M120 228 L128 214'/>",
                "    <path d='M12 120 L26 112 M12 120 L26 128 M228 120 L214 112 M228 120 L214 128'/>",
                "    <circle cx='120' cy='120' r='4'/>",
                "  </g>",
                "</svg>");
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
