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
 * 库存/收费种子仅在基础数据已就绪的库上生效(药品目录已导入/已完成就诊存在/机构已建):
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
            seedTenant("H42010000000", "测试医院", "H42010000000", "420100", "admin", "admin123", "张管理");
            seedTenant("DEMO_B", "演示医院B", "H42020000000", "420200", "admin", "admin123", "李管理");
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
