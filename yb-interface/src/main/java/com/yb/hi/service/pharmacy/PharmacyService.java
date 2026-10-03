package com.yb.hi.service.pharmacy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.pharmacy.DispenseReq;
import com.yb.hi.dto.pharmacy.DrugReturnReq;
import com.yb.hi.dto.pharmacy.TransferReq;
import com.yb.hi.dto.warehouse.StockInItemReq;
import com.yb.hi.dto.warehouse.StockInReq;
import com.yb.hi.dto.warehouse.StockOutItemReq;
import com.yb.hi.dto.warehouse.StockOutReq;
import com.yb.hi.entity.pharmacy.HisDispense;
import com.yb.hi.entity.pharmacy.HisDispenseItem;
import com.yb.hi.entity.pharmacy.HisDrugReturn;
import com.yb.hi.entity.pharmacy.HisDrugReturnItem;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockOut;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.pharmacy.HisDispenseItemMapper;
import com.yb.hi.mapper.pharmacy.HisDispenseMapper;
import com.yb.hi.mapper.pharmacy.HisDrugReturnItemMapper;
import com.yb.hi.mapper.pharmacy.HisDrugReturnMapper;
import com.yb.hi.platform.service.SystemParamResolver;
import com.yb.hi.service.warehouse.DrugStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 药房服务(药房工作站): 待发药列表 / 发药(扣库存) / 发药记录 / 退药申请与审批(回补库存)。
 * 说明:
 * 1) 处方/就诊/处方明细为租户级表(无 org_id), 跨表查询统一走 JdbcTemplate 手动租户过滤
 *    (MyBatis-Plus 租户插件仅作用于 Mapper 语句, 原生 SQL 需显式 tenant_id);
 * 2) 发药防重复: 乐观 UPDATE his_prescription.dispense_status 0->1, affected=0 拒绝;
 * 3) 库存联动经单据完成(单次记账, 全程可追溯):
 *    - 发药 = 创建出库单(out_type=1处方发药)并确认, 确认时按有效期 FIFO 乐观扣减库存,
 *      实扣批次/价格回填出库明细(his_stock_out_item), 不再额外调用 deductStock(避免双重扣减);
 *    - 退药回库 = 按发药出库明细批次创建入库单(in_type=2退药回库)并确认, 确认时按批次 upsert 库存加量,
 *      不再额外调用 returnStock(避免双重加量);
 * 4) 药房维度: 发药记录落 pharmacy_id; 关联药库(his_pharmacy_def.warehouse_id)待药库单支持 warehouseId 后透传,
 *    当前保持既有 createStockOut/createStockIn 调用签名不变(双重扣减/加量防护逻辑不受影响);
 * 5) 单号: FY(发药)/TY(退药) + yyyyMMdd + 4位序号, synchronized 生成 + DB 回读当日最大序号兜底重启防撞号。
 */
@Slf4j
@Service
public class PharmacyService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HisDispenseMapper dispenseMapper;
    private final HisDrugReturnMapper returnMapper;
    private final HisDispenseItemMapper dispenseItemMapper;
    private final HisDrugReturnItemMapper returnItemMapper;
    private final DrugStockService drugStockService;
    private final PharmacyDefService pharmacyDefService;
    private final PharmacyPriceService pharmacyPriceService;
    private final WindowDispatchService windowDispatchService;
    private final PharmacyWindowService pharmacyWindowService;
    private final ScanVerifyService scanVerifyService;
    private final SystemParamResolver systemParamResolver;
    private final JdbcTemplate jdbcTemplate;

    /** 三期: 发药出库单与计费快照口径一致(仅药品行, 金额取 price*quantity) */
    private static final String DISPENSE_ITEM_COLS = "drug_id, item_code, item_name, spec, quantity, price, amount";

    /** 单号内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public PharmacyService(HisDispenseMapper dispenseMapper, HisDrugReturnMapper returnMapper,
                           HisDispenseItemMapper dispenseItemMapper, HisDrugReturnItemMapper returnItemMapper,
                           DrugStockService drugStockService, PharmacyDefService pharmacyDefService,
                           PharmacyPriceService pharmacyPriceService,
                           WindowDispatchService windowDispatchService, PharmacyWindowService pharmacyWindowService,
                           ScanVerifyService scanVerifyService,
                           SystemParamResolver systemParamResolver,
                           JdbcTemplate jdbcTemplate) {
        this.dispenseMapper = dispenseMapper;
        this.returnMapper = returnMapper;
        this.dispenseItemMapper = dispenseItemMapper;
        this.returnItemMapper = returnItemMapper;
        this.drugStockService = drugStockService;
        this.pharmacyDefService = pharmacyDefService;
        this.pharmacyPriceService = pharmacyPriceService;
        this.windowDispatchService = windowDispatchService;
        this.pharmacyWindowService = pharmacyWindowService;
        this.scanVerifyService = scanVerifyService;
        this.systemParamResolver = systemParamResolver;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 待发药 ================= */

    /**
     * 待发药列表: dispense_status=0 且未作废(status>0)的处方(先开先发), keyword 匹配患者姓名/处方号。
     * 处方/就诊为租户级表无 org_id, orgId 不参与过滤(租户内共享就诊数据)。
     * pharmacyId 非空时按药房类型过滤: 中药房(TCM)只看中药处方(rx_type含"中药");
     * 门诊/住院药房暂按门诊口径(不加过滤, 与默认行为一致)。
     */
    public IPage<Map<String, Object>> todoPage(Long orgId, Long pharmacyId, String keyword, long page, long size) {
        long p = safePage(page);
        long s = safeSize(size);
        // p.status>0: 医生作废的处方(-1)不得进入发药环节
        // v.charge_status=1 在 JOIN 条件上: 未收费(0)/已退费(2)的处方不得进入发药环节(先收费后发药)
        StringBuilder where = new StringBuilder(" WHERE p.dispense_status = 0 AND p.status > 0 AND p.deleted = 0 AND p.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (pharmacyId != null) {
            HisPharmacyDef def = pharmacyDefService.requireEnabled(pharmacyId, orgId);
            if (PharmacyDefService.TYPE_TCM.equalsIgnoreCase(def.getPharmacyType())) {
                where.append(" AND p.rx_type LIKE ?");
                args.add("%中药%");
            }
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (v.patient_name LIKE ? OR p.rx_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        String joins = " FROM his_prescription p JOIN his_visit v ON p.visit_id = v.id AND v.deleted = 0 AND v.charge_status = 1";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());

        String dataSql = "SELECT p.id AS prescription_id, p.rx_no AS prescription_no, p.visit_id,"
                + " v.ipt_otp_no AS visit_no, v.patient_name, v.dr_name AS doctor_name, v.dept_name,"
                + " p.total_amount,"
                + " DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i:%s') AS prescribe_time,"
                + " (SELECT COUNT(*) FROM his_prescription_item pi"
                + "  WHERE pi.prescription_id = p.id AND pi.deleted = 0) AS item_count"
                + joins + where + " ORDER BY p.create_time ASC, p.id ASC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());

        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /**
     * 发药详情: 处方信息 + 药品明细(含库存匹配, 供发药前核对)。
     * 三期: pharmacyId 入参空时按处方绑定药房取数(该房库存位维度), 未绑房维持全院口径。
     */
    public Map<String, Object> dispenseDetail(Long prescriptionId, Long pharmacyId) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        List<Map<String, Object>> presRows = jdbcTemplate.queryForList(
                "SELECT p.id, p.rx_no, p.visit_id, p.patient_id, p.patient_name, p.dr_name AS doctor_name,"
                + " p.dept_name, p.total_amount, p.dispense_status, p.pharmacy_id,"
                + " DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i:%s') AS create_time, v.ipt_otp_no AS visit_no"
                + " FROM his_prescription p LEFT JOIN his_visit v ON p.visit_id = v.id AND v.deleted = 0"
                + " WHERE p.id = ? AND p.tenant_id = ? AND p.deleted = 0", prescriptionId, tenantId());
        if (presRows.isEmpty()) {
            throw new BizException(400, "处方不存在");
        }
        Map<String, Object> pres = presRows.get(0);
        Long orgId = currentOrgId();
        if (orgId == null) {
            // 跨机构查看回落: 按处方绑定药房归属机构取库存维度
            HisPharmacyDef bind = pharmacyDefService.find(toLong(pres.get("pharmacy_id")));
            orgId = bind == null ? null : bind.getOrgId();
        }
        Long effPharmacy = pharmacyId != null ? pharmacyId : toLong(pres.get("pharmacy_id"));
        Long effWh = warehouseOfPharmacy(effPharmacy);

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> itemRows = jdbcTemplate.queryForList(
                "SELECT pi.id, pi.drug_id, pi.item_code AS drug_code, pi.item_name AS drug_name, pi.spec, pi.unit,"
                + " pi.quantity AS qty, pi.price, pi.amount, pi.usage_method, pi.frequency,"
                + " pi.dosage, pi.dosage_unit, pi.administration"
                + " FROM his_prescription_item pi"
                + " WHERE pi.prescription_id = ? AND pi.tenant_id = ? AND pi.deleted = 0 ORDER BY pi.id",
                prescriptionId, tenantId());
        for (Map<String, Object> r : itemRows) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("id", r.get("id"));
            it.put("drugId", toLong(r.get("drug_id")));
            it.put("drugCode", str(r.get("drug_code")));
            it.put("drugName", str(r.get("drug_name")));
            it.put("spec", r.get("spec"));
            it.put("unit", r.get("unit"));
            BigDecimal qty = toBd(r.get("qty"));
            it.put("qty", qty);
            it.put("price", toBd(r.get("price")));
            it.put("amount", toBd(r.get("amount")));
            it.put("usageName", r.get("usage_method"));
            it.put("freqName", r.get("frequency"));
            it.put("dosage", r.get("dosage"));
            it.put("dosageUnit", r.get("dosage_unit"));
            it.put("administration", r.get("administration"));
            BigDecimal stockQty = stockQtyOf(orgId, effWh, toLong(r.get("drug_id")));
            it.put("stockQty", stockQty);
            it.put("stockSufficient", stockQty != null && qty != null && stockQty.compareTo(qty) >= 0);
            items.add(it);
        }

        Map<String, Object> prescription = new LinkedHashMap<>();
        prescription.put("id", pres.get("id"));
        prescription.put("prescriptionNo", pres.get("rx_no"));
        prescription.put("visitId", pres.get("visit_id"));
        prescription.put("visitNo", pres.get("visit_no"));
        prescription.put("patientId", pres.get("patient_id"));
        prescription.put("patientName", pres.get("patient_name"));
        prescription.put("doctorName", pres.get("doctor_name"));
        prescription.put("deptName", pres.get("dept_name"));
        prescription.put("totalAmount", toBd(pres.get("total_amount")));
        prescription.put("dispenseStatus", pres.get("dispense_status"));
        prescription.put("pharmacyId", effPharmacy);
        HisPharmacyDef effDef = pharmacyDefService.find(effPharmacy);
        prescription.put("pharmacyName", effDef == null ? null : effDef.getName());
        prescription.put("createTime", pres.get("create_time"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prescription", prescription);
        result.put("items", items);
        result.put("orgId", orgId);
        return result;
    }

    /**
     * 执行发药: 乐观锁置处方已发药(dispense_status 0->1, affected=0 拒绝)
     * -> 创建处方发药出库单(out_type=1)并确认(确认时 FIFO 乐观扣减库存, 实扣批次/价格回填出库明细)
     * -> 落发药记录(status=2已发药, 双签: 发药人+核对人)。任一环节失败整体回滚(处方状态复原)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisDispense doDispense(DispenseReq req) {
        if (req == null || req.getPrescriptionId() == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        Long prescriptionId = req.getPrescriptionId();
        long tenantId = tenantId();

        // 1. 查处方(快照患者/医生/科室/金额), 已作废处方不得发药
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, visit_id, rx_no, patient_id, patient_name, dr_name AS doctor_name, dept_id, dept_name,"
                + " rx_type, total_amount, dispense_status, status, pharmacy_id, transfer_from_pharmacy_id, audit_status, reject_reason FROM his_prescription"
                + " WHERE id = ? AND tenant_id = ? AND deleted = 0", prescriptionId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "处方不存在");
        }
        Map<String, Object> pres = rows.get(0);
        Number rxStatus = (Number) pres.get("status");
        if (rxStatus != null && rxStatus.intValue() < 0) {
            throw new BizException("该处方已作废, 不可发药");
        }

        // 1b. 收费守卫: 就诊必须已收费(charge_status=1), 未收费/已退费不得发药(先收费后发药闭环)
        List<Map<String, Object>> vs = jdbcTemplate.queryForList(
                "SELECT charge_status FROM his_visit WHERE id = ? AND tenant_id = ? AND deleted = 0",
                toLong(pres.get("visit_id")), tenantId);
        if (vs.isEmpty() || vs.get(0).get("charge_status") == null
                || ((Number) vs.get(0).get("charge_status")).intValue() != 1) {
            throw new BizException("该处方所属就诊未收费, 不可发药");
        }

        // 2. 乐观锁防重复发药: 0未发药 -> 1已发药, affected=0 说明已被并发发药/状态异常
        int affected = jdbcTemplate.update(
                "UPDATE his_prescription SET dispense_status = 1"
                        + " WHERE id = ? AND dispense_status = 0 AND tenant_id = ? AND deleted = 0",
                prescriptionId, tenantId);
        if (affected == 0) {
            throw new BizException("该处方已发药或状态异常");
        }

        // 3. 查处方药品明细(drug_id 非空行才扣库存)
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, drug_id, item_code, item_name, spec, unit, quantity, price, amount"
                        + " FROM his_prescription_item"
                        + " WHERE prescription_id = ? AND tenant_id = ? AND drug_id IS NOT NULL AND deleted = 0 ORDER BY id",
                prescriptionId, tenantId);
        if (items.isEmpty()) {
            throw new BizException("该处方无药品明细, 无需发药");
        }

        // 3b. P2 发药前多重校验(硬阻断): 处方被驳回 或 存在皮试阳性药品 -> 拒绝发药(事务回滚复原发药状态)
        assertDispensable(pres, toLong(pres.get("visit_id")), items);

        // 4. 确定发药机构(空则当前登录用户机构)
        Long orgId = req.getOrgId() != null ? req.getOrgId() : currentOrgId();
        if (orgId == null) {
            throw new BizException(400, "机构不能为空");
        }

        // 4.1 药房: 非空时校验归属机构/启停, 并解析本药房库存位(两级库存: 发药从药房自有库存扣减)
        //     stock_location_id 为空时回退旧 warehouse_id(兼容尚未迁移的存量药房)
        //     三期: 请求未指定药房时回退处方绑定药房(开方按科室默认/手选落库); 两者都空维持全院FIFO(兼容存量)
        Long pharmacyId = req.getPharmacyId();
        if (pharmacyId == null) {
            pharmacyId = toLong(pres.get("pharmacy_id"));
        }
        Long warehouseId = null;
        if (pharmacyId != null) {
            HisPharmacyDef pharmacyDef = pharmacyDefService.requireEnabled(pharmacyId, orgId);
            warehouseId = pharmacyDef.getStockLocationId() != null
                    ? pharmacyDef.getStockLocationId() : pharmacyDef.getWarehouseId();
        }

        // 4.3 P1 智能分窗: 药房确定后按 特殊标志/科室规则/策略/兜底 分配窗口, 签到型窗口发药前校验已签到
        //     (无窗口配置则 windowId=null, 不影响既有发药; 分配异常不阻断发药仅记日志)
        Long windowId = null;
        if (pharmacyId != null) {
            try {
                java.util.Set<String> specials = new java.util.HashSet<>();
                if (req.getSpecialTypes() != null) {
                    for (String s : req.getSpecialTypes()) {
                        if (StringUtils.hasText(s)) {
                            specials.add(s.trim().toUpperCase());
                        }
                    }
                }
                windowId = windowDispatchService.assignWindow(pharmacyId, toLong(pres.get("dept_id")),
                        str(pres.get("rx_type")), specials.isEmpty() ? null : specials);
                if (windowId != null && windowDispatchService.isSigninRequired(windowId)
                        && !pharmacyWindowService.isSignedIn(windowId, toLong(pres.get("patient_id")))) {
                    throw new BizException("该窗口要求患者先签到后方可发药, 请引导患者扫码/刷卡签到");
                }
            } catch (BizException be) {
                throw be;
            } catch (Exception e) {
                log.warn("智能分窗分配异常(不阻断发药): prescriptionId={}, err={}", prescriptionId, e.getMessage());
                windowId = null;
            }
        }

        // 4.4 P3 追溯码发药闭环: 窗口级 trace_required 或 药品级 trace_flag 命中时, 发药前校验扫描完整性
        //     (不足/不匹配/重复则抛错, 事务回滚复原 dispense_status), 通过后于发药落库后绑定物理追溯码
        boolean traceRequired = false;
        List<String> traceBound = new ArrayList<>();
        Map<String, Object> traceReq = scanVerifyService.requirement(prescriptionId, windowId, req.getTraceCodes(), pharmacyId);
        if (Boolean.TRUE.equals(traceReq.get("traceRequired"))) {
            traceRequired = true;
            traceBound = scanVerifyService.assertComplete(prescriptionId, windowId, req.getTraceCodes());
        }

        // 5. 创建处方发药出库单并确认: 确认时按有效期 FIFO 乐观扣减库存并回填批次, 单次扣减可追溯
        StockOutReq outReq = new StockOutReq();
        outReq.setOrgId(orgId);
        // 透传药房关联药库: 为空时出库不限库(全院FIFO), 非空则仅从该药库批次扣减
        outReq.setWarehouseId(warehouseId);
        outReq.setOutType(1);
        outReq.setRefId(prescriptionId);
        outReq.setRefNo(str(pres.get("rx_no")));
        outReq.setRemark("处方发药: " + str(pres.get("rx_no")));
        List<StockOutItemReq> outItems = new ArrayList<>();
        for (Map<String, Object> it : items) {
            StockOutItemReq oi = new StockOutItemReq();
            oi.setDrugCatalogId(toLong(it.get("drug_id")));
            String code = str(it.get("item_code"));
            oi.setDrugCode(StringUtils.hasText(code) ? code : "DRUG" + it.get("drug_id"));
            oi.setDrugName(str(it.get("item_name")));
            oi.setSpec(str(it.get("spec")));
            oi.setQty(toBd(it.get("quantity")));
            outItems.add(oi);
        }
        outReq.setItems(outItems);
        // 4.2 绑定药房的发药前诊断: 逐药需/存缺口清单(软校验; 真正防超扣仍由确认出库乐观锁兑底)
        if (warehouseId != null && orgId != null) {
            List<String> lacks = new ArrayList<>();
            for (Map<String, Object> it : items) {
                BigDecimal need = toBd(it.get("quantity"));
                if (need == null) {
                    continue;
                }
                BigDecimal avail = stockQtyOf(orgId, warehouseId, toLong(it.get("drug_id")));
                if (avail.compareTo(need) < 0) {
                    lacks.add(str(it.get("item_name")) + " 需" + plain(need) + "/仅存" + plain(avail));
                }
            }
            if (!lacks.isEmpty()) {
                throw new BizException("药房库存不足: " + String.join("; ", lacks) + "。可改派至有库存药房后发药");
            }
        }
        // 二期: 发药批次价差容差闸(仅启用且已绑药房库存位可预估时生效): 超阈按 block 拒绝 / override 需主管放行留痕。
        // 位置在缺药硬校验之后、真正出库扣减之前: 抛错则整个事务回滚(dispense_status 复原), 不会误扣库存。
        String priceDiffNote = null;
        ToleranceCfg tcfg = readToleranceCfg();
        if (tcfg.enabled && warehouseId != null) {
            BigDecimal estAmt = estimateStockAmount(orgId, warehouseId, items);
            PriceDiffVerdict v = assessPriceDiff(tcfg, toBd(pres.get("total_amount")), estAmt);
            if (v.exceeded) {
                boolean allowed = "override".equalsIgnoreCase(v.action)
                        && Boolean.TRUE.equals(req.getPriceDiffOverride())
                        && StringUtils.hasText(req.getOverrideBy())
                        && StringUtils.hasText(req.getOverrideReason());
                if (!allowed) {
                    throw new BizException(priceDiffBlockMsg(v));
                }
                priceDiffNote = "价差超阈经主管放行: 放行人=" + req.getOverrideBy().trim()
                        + ", 理由=" + req.getOverrideReason().trim() + " | " + priceDiffBlockMsg(v);
                log.warn("发药价差超阈经主管放行: prescriptionId={}, dispenseBy={}, overrideBy={}, diff={}, rate={}",
                        prescriptionId, currentUserName(), req.getOverrideBy().trim(), v.diff, v.rate);
            }
        }

        HisStockOut stockOut = drugStockService.createStockOut(outReq);
        drugStockService.confirmStockOut(stockOut.getId());

        // 5.1 实发批次零售金额(对账口径与出库单一致: 零售价优先、空回退进价; 发药完成后按实扣批次汇总)
        BigDecimal stockAmount = jdbcTemplate.queryForObject(
                "SELECT IFNULL(SUM(i.qty * IFNULL(i.retail_price, IFNULL(i.cost_price, 0))), 0) FROM his_stock_out_item i"
                        + " WHERE i.stock_out_id = ? AND i.deleted = 0", BigDecimal.class, stockOut.getId());

        // 6. 落发药记录(一步发药到位: status=2已发药, 双签发药人/核对时间)
        HisDispense dispense = new HisDispense();
        dispense.setOrgId(orgId);
        dispense.setPharmacyId(pharmacyId);
        dispense.setWindowId(windowId);
        // P1 签到型窗口发药即已签到(前置守卫已校验), 其余窗口置未签到
        dispense.setSigninStatus(windowId != null && windowDispatchService.isSigninRequired(windowId) ? 1 : 0);
        dispense.setDispenseNo(generateNo("FY"));
        dispense.setVisitId(toLong(pres.get("visit_id")));
        dispense.setPrescriptionId(prescriptionId);
        dispense.setPatientId(toLong(pres.get("patient_id")));
        dispense.setPatientName(str(pres.get("patient_name")));
        dispense.setDoctorName(str(pres.get("doctor_name")));
        dispense.setDeptName(str(pres.get("dept_name")));
        dispense.setStatus(2);
        dispense.setDispenseBy(currentUserName());
        dispense.setDispenseTime(LocalDateTime.now());
        if (StringUtils.hasText(req.getCheckBy())) {
            dispense.setCheckBy(req.getCheckBy().trim());
            dispense.setCheckTime(LocalDateTime.now());
        }
        BigDecimal total = toBd(pres.get("total_amount"));
        dispense.setTotalAmount(total == null ? BigDecimal.ZERO : total);
        // 三期: 价差对账落账(实发批次金额-计费, 不向患者补退) + 改派来源药房留痕(自发药链路透传)
        dispense.setStockAmount(stockAmount);
        if (stockAmount != null) {
            dispense.setPriceDiff(stockAmount.subtract(dispense.getTotalAmount()).setScale(2, RoundingMode.HALF_UP));
        }
        dispense.setTransferFromPharmacyId(toLong(pres.get("transfer_from_pharmacy_id")));
        // P3: 本单追溯强制标志落账(发药后绑定成功再回写已绑数)
        dispense.setTraceRequired(traceRequired ? 1 : 0);
        dispense.setTraceScanned(0);
        dispense.setRemark(composePriceDiffRemark(req.getRemark(), priceDiffNote));
        dispenseMapper.insert(dispense);

        // 三期 C2: 落发药明细行(行级部分退粒度), billed_price/billed_amount 直引划价快照原价, 保证后续退药按原价不退不平
        writeDispenseItems(dispense, items);

        // P3 发药后绑定: 将已校验在库物理追溯码置已发药并绑定本次发药(患者/就诊/发药记录), 回写已绑数
        if (traceRequired && !traceBound.isEmpty()) {
            int bound = scanVerifyService.bindForDispense(dispense.getId(), toLong(pres.get("patient_id")),
                    toLong(pres.get("visit_id")), traceBound);
            dispense.setTraceScanned(bound);
            dispenseMapper.updateById(dispense);
        }

        log.info("发药完成: dispenseNo={}, prescriptionId={}, patient={}, items={}, total={}, stockAmount={}, priceDiff={}, pharmacyId={}, warehouseId={}",
                dispense.getDispenseNo(), prescriptionId, dispense.getPatientName(),
                items.size(), dispense.getTotalAmount(), stockAmount, dispense.getPriceDiff(), pharmacyId, warehouseId);
        return dispense;
    }

    /* ================= 三期: 库存不足诊断 / 价差预览 / 处方改派 ================= */

    /**
     * 解析处方应绑定的发药药房: 医生手选(须启用且属该机构)优先, 未手选按科室默认(科室×中西药渠道);
     * 均未命中返回 null(不绑药房, 发药走全院FIFO, 兼容存量流程)。
     */
    public Long resolvePharmacyForPrescribe(Long deptId, String rxType, Long handPickPharmacyId, Long orgId) {
        if (handPickPharmacyId != null) {
            pharmacyDefService.requireEnabled(handPickPharmacyId, orgId);
            return handPickPharmacyId;
        }
        return pharmacyDefService.resolveDefaultPharmacyId(deptId, rxType);
    }

    /** 库存不足预检(不扣减): 处方逐药"需求量/该房可用量"缺口清单, 供发药工作站与改派面板展示 */
    public Map<String, Object> shortageInfo(Long prescriptionId, Long pharmacyId) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        Map<String, Object> pres = presRow(prescriptionId);
        Long effPharmacy = pharmacyId != null ? pharmacyId : toLong(pres.get("pharmacy_id"));
        Long wh = warehouseOfPharmacy(effPharmacy);
        Long orgId = toLong(pres.get("org_id"));
        if (orgId == null) {
            HisPharmacyDef def0 = pharmacyDefService.find(effPharmacy);
            orgId = def0 == null ? currentOrgId() : def0.getOrgId();
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        boolean allEnough = true;
        for (Map<String, Object> it : requiredItems(prescriptionId)) {
            BigDecimal need = nvlBd(toBd(it.get("quantity")));
            BigDecimal avail = stockQtyOf(orgId, wh, toLong(it.get("drug_id")));
            boolean enough = avail.compareTo(need) >= 0;
            if (!enough) {
                allEnough = false;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("drugName", str(it.get("item_name")));
            line.put("spec", str(it.get("spec")));
            line.put("needQty", plain(need));
            line.put("availQty", plain(avail));
            line.put("shortfall", plain(need.subtract(avail).max(BigDecimal.ZERO)));
            line.put("enough", enough);
            lines.add(line);
        }
        HisPharmacyDef def = pharmacyDefService.find(effPharmacy);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prescriptionId", prescriptionId);
        out.put("pharmacyId", effPharmacy);
        out.put("pharmacyName", def == null ? "全院库存" : def.getName());
        out.put("allSufficient", allEnough);
        out.put("lines", lines);
        return out;
    }

    /* ================= P2: 发药前多重校验 ================= */

    /**
     * 发药前多重校验(供发药工作站展示): 汇总硬阻断项(blocks)与提醒项(warns)。
     * blocks: 处方被药师驳回 / 已发药或已作废 / 就诊未收费 / 存在皮试阳性药品;
     * warns : 处方尚未审核通过(待审) / 需皮试药品未见阴性记录(疑未做) / 库存缺口 / 同就诊存在他药房未发药处方。
     * 返回 {prescriptionId, canDispense, blocks:[{type,message}], warns:[{type,message}]}。doDispense 仅对 blocks 抛错。
     */
    public Map<String, Object> preDispenseCheck(Long prescriptionId) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.id, p.visit_id, p.dispense_status, p.status, p.audit_status, p.reject_reason, p.pharmacy_id,"
                        + " v.charge_status FROM his_prescription p LEFT JOIN his_visit v ON p.visit_id = v.id AND v.deleted = 0"
                        + " WHERE p.id = ? AND p.tenant_id = ? AND p.deleted = 0", prescriptionId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "处方不存在");
        }
        Map<String, Object> pres = rows.get(0);
        Long visitId = toLong(pres.get("visit_id"));
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<Map<String, Object>> warns = new ArrayList<>();
        Map<String, Object> priceDiffInfo = null;

        Number rxStatus = (Number) pres.get("status");
        Number ds = (Number) pres.get("dispense_status");
        if (rxStatus != null && rxStatus.intValue() < 0) {
            blocks.add(checkItem("cancelled", "该处方已作废, 不可发药"));
        } else if (ds != null && ds.intValue() != 0) {
            blocks.add(checkItem("dispensed", "该处方已发药或已退药, 请勿重复发药"));
        }
        Number cs = (Number) pres.get("charge_status");
        if (cs == null || cs.intValue() != 1) {
            blocks.add(checkItem("uncharged", "该处方所属就诊未收费, 不可发药"));
        }
        Number as = (Number) pres.get("audit_status");
        int auditStatus = as == null ? 0 : as.intValue();
        if (auditStatus == 3) {
            blocks.add(checkItem("rx_rejected", "该处方已被药师驳回, 不可发药: " + nvlStr(pres.get("reject_reason"))));
        } else if (auditStatus == 1) {
            warns.add(checkItem("rx_pending", "提醒: 该处方尚未审核通过(待审), 建议先完成审方再发药"));
        }

        // 皮试校验: 目录需皮试药品×就诊执行皮试结果
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT drug_id, item_name FROM his_prescription_item"
                        + " WHERE prescription_id = ? AND tenant_id = ? AND drug_id IS NOT NULL AND deleted = 0 ORDER BY id",
                prescriptionId, tenantId());
        List<String> skinPos = new ArrayList<>();
        List<String> skinPending = new ArrayList<>();
        collectSkinTestStatus(visitId, items, skinPos, skinPending);
        if (!skinPos.isEmpty()) {
            blocks.add(checkItem("skin_positive", "存在皮试阳性药品, 不可发药: " + String.join("、", skinPos)));
        }
        if (!skinPending.isEmpty()) {
            warns.add(checkItem("skin_pending", "提醒: 以下药品需皮试但未见阴性记录: " + String.join("、", skinPending)));
        }

        // 缺药软校验(复用 shortageInfo)
        try {
            Map<String, Object> shortInfo = shortageInfo(prescriptionId, toLong(pres.get("pharmacy_id")));
            if (!Boolean.TRUE.equals(shortInfo.get("allSufficient"))) {
                warns.add(checkItem("shortage", "提醒: 当前药房存在库存缺口, 请核对或改派药房"));
            }
        } catch (Exception e) {
            log.warn("发药前缺药校验异常(忽略): prescriptionId={}, err={}", prescriptionId, e.getMessage());
        }

        // 二期: 价差容差预检(软提示): 复用 dispensePreview 预估, 启用且超阈则计入阻断项供前端禁用发药按钮并展示处置口径
        try {
            ToleranceCfg tcfg = readToleranceCfg();
            if (tcfg.enabled) {
                Map<String, Object> pv = dispensePreview(prescriptionId);
                if (Boolean.TRUE.equals(pv.get("estReliable"))) {
                    PriceDiffVerdict v = assessPriceDiff(tcfg,
                            (BigDecimal) pv.get("billingAmount"), (BigDecimal) pv.get("estStockAmount"));
                    if (v.exceeded) {
                        blocks.add(checkItem("price_over_tolerance", priceDiffBlockMsg(v)));
                        priceDiffInfo = new LinkedHashMap<>();
                        priceDiffInfo.put("billingAmount", pv.get("billingAmount"));
                        priceDiffInfo.put("estStockAmount", pv.get("estStockAmount"));
                        priceDiffInfo.put("estPriceDiff", pv.get("estPriceDiff"));
                        priceDiffInfo.put("diffRate", v.rate);
                        priceDiffInfo.put("action", v.action);
                        priceDiffInfo.put("overridable", "override".equalsIgnoreCase(v.action));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("发药前价差容差校验异常(忽略): prescriptionId={}, err={}", prescriptionId, e.getMessage());
        }

        // 同就诊跨药房未发药提醒
        if (visitId != null) {
            Long otherUndispensed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_prescription WHERE visit_id = ? AND tenant_id = ? AND deleted = 0"
                            + " AND dispense_status = 0 AND status > 0 AND (pharmacy_id IS NULL OR pharmacy_id <> ?)",
                    Long.class, visitId, tenantId(), pres.get("pharmacy_id"));
            if (otherUndispensed != null && otherUndispensed > 0) {
                warns.add(checkItem("other_pharmacy", "提醒: 同一就诊另有 " + otherUndispensed + " 张未发药处方归属其他/未绑定药房"));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prescriptionId", prescriptionId);
        out.put("canDispense", blocks.isEmpty());
        out.put("blocks", blocks);
        out.put("warns", warns);
        if (priceDiffInfo != null) {
            out.put("priceDiffInfo", priceDiffInfo);
        }
        return out;
    }

    /** doDispense 前置硬阻断断言: 驳回处方与皮试阳性拒绝发药(其余为提醒, 交由前端展示, 不在此拦截)。 */
    private void assertDispensable(Map<String, Object> pres, Long visitId, List<Map<String, Object>> items) {
        Number as = (Number) pres.get("audit_status");
        if (as != null && as.intValue() == 3) {
            throw new BizException("该处方已被药师驳回, 不可发药: " + nvlStr(pres.get("reject_reason")));
        }
        List<String> skinPos = new ArrayList<>();
        collectSkinTestStatus(visitId, items, skinPos, new ArrayList<>());
        if (!skinPos.isEmpty()) {
            throw new BizException("存在皮试阳性药品, 不可发药: " + String.join("、", skinPos));
        }
    }

    /** 皮试状态归集: 对目录 skin_test_flag=1 的药品, 按就诊执行记录判定阳性(result=2)/疑未做(无阴性 result=1)。 */
    private void collectSkinTestStatus(Long visitId, List<Map<String, Object>> items,
                                       List<String> positiveOut, List<String> pendingOut) {
        if (items == null || items.isEmpty()) {
            return;
        }
        // 1. 筛出需皮试药品(drugId→name)
        Map<Long, String> needSkin = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            Long drugId = toLong(it.get("drug_id"));
            if (drugId == null) {
                continue;
            }
            Integer flag = jdbcTemplate.queryForObject(
                    "SELECT skin_test_flag FROM his_drug_catalog WHERE id = ? AND deleted = 0",
                    Integer.class, drugId);
            if (flag != null && flag == 1) {
                needSkin.put(drugId, nvlStr(it.get("item_name")));
            }
        }
        if (needSkin.isEmpty() || visitId == null) {
            // 需皮试但无就诊上下文: 全部按疑未做提醒(阳性无从判定)
            if (visitId == null) {
                pendingOut.addAll(needSkin.values());
            }
            return;
        }
        // 2. 就诊执行皮试记录(his_skin_test JOIN his_nurse_exec.visit_id)按药品归集最高优先级结果
        List<Map<String, Object>> tests = jdbcTemplate.queryForList(
                "SELECT st.drug_id AS drugId, st.result AS result FROM his_skin_test st"
                        + " JOIN his_nurse_exec ne ON st.exec_id = ne.id AND ne.deleted = 0"
                        + " WHERE ne.visit_id = ? AND st.tenant_id = ? AND st.deleted = 0", visitId, tenantId());
        Map<Long, Integer> best = new HashMap<>();
        for (Map<String, Object> t : tests) {
            Long did = toLong(t.get("drugId"));
            Number rn = (Number) t.get("result");
            if (did == null || rn == null) {
                continue;
            }
            // 优先级: 阳性(2) > 观察中(0)/未做(3) > 阴性(1): 只要出现阳性即判阳性
            int r = rn.intValue();
            Integer cur = best.get(did);
            if (cur == null || (r == 2) || (cur == 1 && r != 1)) {
                best.put(did, r);
            }
        }
        for (Map.Entry<Long, String> e : needSkin.entrySet()) {
            Integer r = best.get(e.getKey());
            if (r != null && r == 2) {
                positiveOut.add(e.getValue());
            } else if (r == null || r != 1) {
                // 无记录 或 观察中/未做 → 疑未做提醒
                pendingOut.add(e.getValue());
            }
        }
    }

    private static Map<String, Object> checkItem(String type, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("message", message);
        return m;
    }

    private static String nvlStr(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 发药前价差预览: 按处方绑定药房对在库批次 FIFO(有效期升序)估算实发零售金额,
     * 与处方计费金额对比得价差(计费按药房覆盖价重算, 实发按批次零售价, 同房批次价差同样会出现)。
     */
    public Map<String, Object> dispensePreview(Long prescriptionId) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        Map<String, Object> pres = presRow(prescriptionId);
        Long pharmacyId = toLong(pres.get("pharmacy_id"));
        Long wh = warehouseOfPharmacy(pharmacyId);
        Long orgId = toLong(pres.get("org_id"));
        if (orgId == null) {
            HisPharmacyDef def0 = pharmacyDefService.find(pharmacyId);
            orgId = def0 == null ? currentOrgId() : def0.getOrgId();
        }
        BigDecimal billing = BigDecimal.ZERO;
        BigDecimal est = BigDecimal.ZERO;
        boolean hasShortage = false;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> it : requiredItems(prescriptionId)) {
            BigDecimal need = nvlBd(toBd(it.get("quantity")));
            billing = billing.add(nvlBd(toBd(it.get("price"))).multiply(need));
            Map<String, Object> e = estimateFifo(orgId, wh, toLong(it.get("drug_id")), need);
            est = est.add((BigDecimal) e.get("stockAmount"));
            if (Boolean.TRUE.equals(e.get("shortage"))) {
                hasShortage = true;
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("drugName", str(it.get("item_name")));
                line.put("needQty", plain(need));
                line.put("availQty", plain((BigDecimal) e.get("available")));
                lines.add(line);
            }
        }
        BigDecimal stockAmount = est.setScale(2, RoundingMode.HALF_UP);
        BigDecimal priceDiff = stockAmount.subtract(billing.setScale(2, RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
        HisPharmacyDef def = pharmacyDefService.find(pharmacyId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prescriptionId", prescriptionId);
        out.put("pharmacyId", pharmacyId);
        out.put("pharmacyName", def == null ? null : def.getName());
        out.put("billingAmount", billing.setScale(2, RoundingMode.HALF_UP));
        out.put("estStockAmount", stockAmount);
        out.put("estPriceDiff", priceDiff);
        out.put("hasPriceDiff", priceDiff.compareTo(BigDecimal.ZERO) != 0);
        out.put("hasShortage", hasShortage);
        out.put("estReliable", wh != null);
        out.put("shortageLines", lines);
        out.put("note", wh == null ? "处方未绑定药房, 发药按全院FIFO, 实发金额以出库为准" : null);
        return out;
    }

    /**
     * 改派可选药房: 本机构启用药房逐房 FIFO 估算"是否全满足/缺口明细/预估实发金额与价差"。
     * 仅未作废且未发药(dispense_status=0)的处方有改派意义; 已绑药房出现在列表中供"改回"。
     */
    public List<Map<String, Object>> transferOptions(Long prescriptionId) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        Map<String, Object> pres = presRow(prescriptionId);
        assertTransferrable(pres);
        Long orgId = toLong(pres.get("org_id"));
        if (orgId == null) {
            orgId = currentOrgId();
        }
        if (orgId == null) {
            throw new BizException(400, "无法确定处方归属机构, 不可改派");
        }
        BigDecimal billing = BigDecimal.ZERO;
        List<Map<String, Object>> drugs = new ArrayList<>();
        Set<Long> drugIds = new HashSet<>();
        for (Map<String, Object> it : requiredItems(prescriptionId)) {
            BigDecimal need = nvlBd(toBd(it.get("quantity")));
            billing = billing.add(nvlBd(toBd(it.get("price"))).multiply(need));
            drugs.add(it);
            drugIds.add(toLong(it.get("drug_id")));
        }
        billing = billing.setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> out = new ArrayList<>();
        for (HisPharmacyDef def : pharmacyDefService.list(orgId)) {
            Long wh = warehouseOfPharmacy(def.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pharmacyId", def.getId());
            row.put("pharmacyName", def.getName());
            row.put("pharmacyType", def.getPharmacyType());
            row.put("current", def.getId().equals(toLong(pres.get("pharmacy_id"))));
            if (wh == null) {
                row.put("allSufficient", false);
                row.put("shortages", Collections.emptyList());
                row.put("estStockAmount", null);
                row.put("estPriceDiff", null);
                row.put("note", "该药房无库存位, 发药将回落全院FIFO");
                out.add(row);
                continue;
            }
            BigDecimal est = BigDecimal.ZERO;
            boolean allOk = true;
            List<Map<String, Object>> shortages = new ArrayList<>();
            for (Map<String, Object> it : drugs) {
                BigDecimal need = nvlBd(toBd(it.get("quantity")));
                Map<String, Object> e = estimateFifo(orgId, wh, toLong(it.get("drug_id")), need);
                est = est.add((BigDecimal) e.get("stockAmount"));
                if (Boolean.TRUE.equals(e.get("shortage"))) {
                    allOk = false;
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("drugName", str(it.get("item_name")));
                    s.put("needQty", plain(need));
                    s.put("availQty", plain((BigDecimal) e.get("available")));
                    shortages.add(s);
                }
            }
            row.put("allSufficient", allOk);
            row.put("shortages", shortages);
            row.put("estStockAmount", est.setScale(2, RoundingMode.HALF_UP));
            row.put("estPriceDiff", est.setScale(2, RoundingMode.HALF_UP).subtract(billing).setScale(2, RoundingMode.HALF_UP));
            out.add(row);
        }
        return out;
    }

    /**
     * 执行改派: 守卫(未作废 + dispense_status=0 未发药 + 所属就诊已收费 charge_status=1 + 目标房启用且非当前房),
     * 乐观 UPDATE 换绑药房并记录来源房(transfer_from 仅留首次)。改派不动费用、不动发票(价差仅对账不补退)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> transferPrescription(TransferReq req) {
        if (req == null || req.getPrescriptionId() == null || req.getToPharmacyId() == null) {
            throw new BizException(400, "处方与目标药房不能为空");
        }
        Map<String, Object> pres = presRow(req.getPrescriptionId());
        assertTransferrable(pres);
        Map<String, Object> visit = jdbcTemplate.queryForMap(
                "SELECT v.charge_status, v.patient_name FROM his_visit v"
                        + " WHERE v.id = ? AND v.tenant_id = ? AND v.deleted = 0",
                pres.get("visit_id"), tenantId());
        Number cs = (Number) visit.get("charge_status");
        if (cs == null || cs.intValue() != 1) {
            throw new BizException("仅已收费未发药的处方(就诊已收费)可改派药房");
        }
        Long orgId = toLong(pres.get("org_id"));
        HisPharmacyDef to = pharmacyDefService.requireEnabled(req.getToPharmacyId(), orgId);
        if (to.getId().equals(toLong(pres.get("pharmacy_id")))) {
            throw new BizException("目标药房与当前绑定药房相同, 无需改派");
        }
        // P1 跨药房配置: 源药房已建立跨药房白名单(受控)时, 改派目标必须在启用的白名单内; 未配置则保持旧行为不受限
        Long fromPharmacyId = toLong(pres.get("pharmacy_id"));
        if (fromPharmacyId != null && pharmacyWindowService.isCrossControlled(fromPharmacyId)
                && !pharmacyWindowService.isCrossAllowed(fromPharmacyId, to.getId())) {
            throw new BizException("源药房未配置到目标药房[" + to.getName() + "]的跨药房发药白名单, 不允许改派");
        }
        long tenantId = tenantId();
        int affected = jdbcTemplate.update(
                "UPDATE his_prescription SET pharmacy_id = ?, transfer_from_pharmacy_id = COALESCE(transfer_from_pharmacy_id, ?),"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND dispense_status = 0 AND status > 0",
                to.getId(), toLong(pres.get("pharmacy_id")), currentUserName(), req.getPrescriptionId(), tenantId);
        if (affected == 0) {
            throw new BizException("处方状态已变更(已发药/已作废), 改派失败");
        }
        log.info("处方改派发药药房: prescriptionId={}, rxNo={}, from={}, to={}, orgId={}, actor={}",
                req.getPrescriptionId(), str(pres.get("rx_no")), pres.get("pharmacy_id"), to.getId(), orgId, currentUserName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prescriptionId", req.getPrescriptionId());
        out.put("rxNo", str(pres.get("rx_no")));
        out.put("fromPharmacyId", toLong(pres.get("pharmacy_id")));
        out.put("toPharmacyId", to.getId());
        out.put("toPharmacyName", to.getName());
        out.put("billingAmount", toBd(pres.get("total_amount")));
        return out;
    }

    /* ================= 发药记录 ================= */

    /** 发药记录分页(机构/药房/状态/发药日期区间/发药单号或患者关键字) */
    public IPage<HisDispense> dispensePage(Long orgId, Long pharmacyId, Integer status, String startDate, String endDate,
                                           String keyword, long page, long size) {
        LambdaQueryWrapper<HisDispense> w = Wrappers.<HisDispense>lambdaQuery()
                .eq(orgId != null, HisDispense::getOrgId, orgId)
                .eq(pharmacyId != null, HisDispense::getPharmacyId, pharmacyId)
                .eq(status != null, HisDispense::getStatus, status);
        LocalDateTime from = parseStart(startDate);
        LocalDateTime to = parseEnd(endDate);
        w.ge(from != null, HisDispense::getDispenseTime, from)
                .le(to != null, HisDispense::getDispenseTime, to);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(HisDispense::getDispenseNo, kw).or().like(HisDispense::getPatientName, kw));
        }
        w.orderByDesc(HisDispense::getId);
        return dispenseMapper.selectPage(new Page<>(safePage(page), safeSize(size)), w);
    }

    /** 发药记录导出数据 {head, rows}: 与列表同机构/药房/日期口径, 上限5000行 */
    public Map<String, Object> exportDispense(Long orgId, Long pharmacyId, String startDate, String endDate) {
        LambdaQueryWrapper<HisDispense> w = Wrappers.<HisDispense>lambdaQuery()
                .eq(orgId != null, HisDispense::getOrgId, orgId)
                .eq(pharmacyId != null, HisDispense::getPharmacyId, pharmacyId);
        LocalDateTime from = parseStart(startDate);
        LocalDateTime to = parseEnd(endDate);
        w.ge(from != null, HisDispense::getDispenseTime, from)
                .le(to != null, HisDispense::getDispenseTime, to)
                .orderByDesc(HisDispense::getId)
                .last("LIMIT 5000");
        List<HisDispense> list = dispenseMapper.selectList(w);
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"发药单号", "患者姓名", "医生姓名", "科室名称", "总金额", "状态",
                "发药人", "发药时间", "核对人", "核对时间", "备注"}) {
            head.add(Collections.singletonList(h));
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<List<Object>> rows = new ArrayList<>();
        for (HisDispense d : list) {
            rows.add(Arrays.asList(
                    nz(d.getDispenseNo()), nz(d.getPatientName()), nz(d.getDoctorName()), nz(d.getDeptName()),
                    d.getTotalAmount() == null ? "0.00" : d.getTotalAmount().toPlainString(),
                    dispenseStatusText(d.getStatus()),
                    nz(d.getDispenseBy()),
                    d.getDispenseTime() == null ? "" : d.getDispenseTime().format(fmt),
                    nz(d.getCheckBy()),
                    d.getCheckTime() == null ? "" : d.getCheckTime().format(fmt),
                    nz(d.getRemark())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    /* ================= 退药 ================= */

    /** 退药申请: 已发药(status=2)的发药记录可申请, 生成退药单(TY)待审核。
     * 三期 C2: 有发药明细行 -> 行级(按 dispenseItemId+returnQty 逐行校验不超剩余可退, items 缺省=一键整退全部剩余行, 金额按划价原价);
     *           无明细行(改造前存量发药) -> 回落旧整方退(退整单金额, 任一待审核/已退药均拒重复)。
     * 行锁发药记录: 并发双击申请时后到事务阻塞后看到已有申请被拒。 */
    @Transactional(rollbackFor = Exception.class)
    public HisDrugReturn returnApply(DrugReturnReq req) {
        if (req == null || req.getDispenseId() == null) {
            throw new BizException(400, "发药记录ID不能为空");
        }
        HisDispense dispense = dispenseMapper.selectOne(Wrappers.<HisDispense>lambdaQuery()
                .eq(HisDispense::getId, req.getDispenseId())
                .last("FOR UPDATE"));
        if (dispense == null) {
            throw new BizException(400, "发药记录不存在");
        }
        if (dispense.getStatus() == null || dispense.getStatus() != 2) {
            throw new BizException("仅已发药的记录可申请退药");
        }

        List<HisDispenseItem> lines = dispenseItemMapper.selectList(Wrappers.<HisDispenseItem>lambdaQuery()
                .eq(HisDispenseItem::getDispenseId, dispense.getId())
                .orderByAsc(HisDispenseItem::getId));

        // ---- 旧整方路径(改造前无明细行的发药): 保持改造前语义 ----
        if (lines.isEmpty()) {
            Long legacyPending = returnMapper.selectCount(Wrappers.<HisDrugReturn>lambdaQuery()
                    .eq(HisDrugReturn::getDispenseId, dispense.getId())
                    .in(HisDrugReturn::getStatus, 0, 1));
            if (legacyPending != null && legacyPending > 0) {
                throw new BizException("该发药记录已有退药申请(待审核/已退药), 请勿重复申请");
            }
            HisDrugReturn dr = newReturnShell(dispense, req.getReason());
            dr.setReturnAmount(dispense.getTotalAmount() == null ? BigDecimal.ZERO : dispense.getTotalAmount());
            returnMapper.insert(dr);
            log.info("退药申请(整方): returnNo={}, dispenseId={}, patient={}, pharmacyId={}",
                    dr.getReturnNo(), dr.getDispenseId(), dr.getPatientName(), dispense.getPharmacyId());
            return dr;
        }

        // ---- 行级路径: 允许已退药后多轮再退, 但同一时刻仅一笔待审核 ----
        Long pending = returnMapper.selectCount(Wrappers.<HisDrugReturn>lambdaQuery()
                .eq(HisDrugReturn::getDispenseId, dispense.getId())
                .eq(HisDrugReturn::getStatus, 0));
        if (pending != null && pending > 0) {
            throw new BizException("该发药记录已有待审核退药申请, 请先处理后再申请");
        }

        // 归并对同一发药行的多次入参(累加), 逐行校验不超剩余可退量
        Map<Long, BigDecimal> wantQty = new LinkedHashMap<>();
        if (req.getItems() == null || req.getItems().isEmpty()) {
            // items 缺省 -> 一键整退: 展开为全部仍有剩余可退量的行(走行级, 按原价退)
            for (HisDispenseItem ln : lines) {
                BigDecimal remain = bdOrZero(ln.getDispenseQty()).subtract(bdOrZero(ln.getReturnedQty()));
                if (remain.compareTo(BigDecimal.ZERO) > 0) {
                    wantQty.put(ln.getId(), remain);
                }
            }
            if (wantQty.isEmpty()) {
                throw new BizException("该发药记录已全部退药, 无剩余可退");
            }
        } else {
            Map<Long, HisDispenseItem> byId = new HashMap<>();
            for (HisDispenseItem ln : lines) {
                byId.put(ln.getId(), ln);
            }
            for (DrugReturnReq.Item it : req.getItems()) {
                if (it == null || it.getDispenseItemId() == null) {
                    throw new BizException(400, "行级退药明细缺少发药行ID");
                }
                BigDecimal q = it.getReturnQty();
                if (q == null || q.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException(400, "退药数量须大于0");
                }
                if (!byId.containsKey(it.getDispenseItemId())) {
                    throw new BizException("退药行不属于该发药记录: " + it.getDispenseItemId());
                }
                wantQty.merge(it.getDispenseItemId(), q, BigDecimal::add);
            }
            for (HisDispenseItem ln : lines) {
                BigDecimal w = wantQty.get(ln.getId());
                if (w == null) {
                    continue;
                }
                BigDecimal remain = bdOrZero(ln.getDispenseQty()).subtract(bdOrZero(ln.getReturnedQty()));
                if (w.compareTo(remain) > 0) {
                    throw new BizException("退药数量超出该行剩余可退量: " + ln.getDrugName()
                            + "(发药" + plain(bdOrZero(ln.getDispenseQty())) + ", 已退" + plain(bdOrZero(ln.getReturnedQty())) + ")");
                }
            }
        }

        // 落退药单(待审核) + 退药明细行(金额按该行划价原价; 本行退完用原价总额精确兜底)
        HisDrugReturn dr = newReturnShell(dispense, req.getReason());
        BigDecimal totalRet = BigDecimal.ZERO;
        List<HisDrugReturnItem> retItems = new ArrayList<>();
        for (HisDispenseItem ln : lines) {
            BigDecimal w = wantQty.get(ln.getId());
            if (w == null) {
                continue;
            }
            BigDecimal lineAmt = lineReturnAmount(ln, w);
            totalRet = totalRet.add(lineAmt);
            HisDrugReturnItem ri = new HisDrugReturnItem();
            ri.setOrgId(dispense.getOrgId());
            ri.setDispenseItemId(ln.getId());
            ri.setDrugCatalogId(ln.getDrugCatalogId());
            ri.setDrugCode(ln.getDrugCode());
            ri.setDrugName(ln.getDrugName());
            ri.setSpec(ln.getSpec());
            ri.setUnit(ln.getUnit());
            ri.setReturnQty(w);
            ri.setReturnAmount(lineAmt);
            retItems.add(ri);
        }
        dr.setReturnAmount(totalRet);
        returnMapper.insert(dr);
        for (HisDrugReturnItem ri : retItems) {
            ri.setReturnId(dr.getId());
            returnItemMapper.insert(ri);
        }
        log.info("退药申请(行级): returnNo={}, dispenseId={}, lines={}, returnAmount={}",
                dr.getReturnNo(), dr.getDispenseId(), retItems.size(), totalRet);
        return dr;
    }

    /**
     * 退药审批: 通过 -> 按发药出库明细逐批次创建退药回库入库单(in_type=2)并确认(确认时按批次 upsert 库存加量)
     * -> 发药记录置已退药(status=3) -> 处方置已退药(dispense_status=2); 驳回 -> 退药记录置已驳回(status=2)。
     * 行锁退药记录: 并发双击审批时后到事务阻塞后读到已处理状态被拒, 不会双倍回补库存。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisDrugReturn returnApprove(Long id, boolean approved) {
        if (id == null) {
            throw new BizException(400, "退药记录ID不能为空");
        }
        HisDrugReturn dr = returnMapper.selectOne(Wrappers.<HisDrugReturn>lambdaQuery()
                .eq(HisDrugReturn::getId, id)
                .last("FOR UPDATE"));
        if (dr == null) {
            throw new BizException(400, "退药记录不存在");
        }
        if (dr.getStatus() != null && dr.getStatus() != 0) {
            throw new BizException("该退药申请已处理, 请勿重复审批");
        }
        if (!approved) {
            dr.setStatus(2);
            dr.setApproveBy(currentUserName());
            dr.setApproveTime(LocalDateTime.now());
            returnMapper.updateById(dr);
            log.info("退药驳回: returnNo={}", dr.getReturnNo());
            return dr;
        }

        // 1. 查原发药记录与发药出库明细(批次级回库依据)
        HisDispense dispense = dispenseMapper.selectById(dr.getDispenseId());
        if (dispense == null) {
            throw new BizException(400, "原发药记录不存在");
        }
        if (dispense.getPrescriptionId() == null) {
            throw new BizException("原发药记录缺少处方关联, 无法回库");
        }
        // 原发药药房库存位(退药回库对称回到药房自有库存; stock_location_id 空则回退旧 warehouse_id)
        Long warehouseId = null;
        HisPharmacyDef pharmacyDef = pharmacyDefService.find(dispense.getPharmacyId());
        if (pharmacyDef != null) {
            warehouseId = pharmacyDef.getStockLocationId() != null
                    ? pharmacyDef.getStockLocationId() : pharmacyDef.getWarehouseId();
        }
        List<Map<String, Object>> outItems = dispenseOutItems(dispense.getPrescriptionId());
        if (outItems.isEmpty()) {
            throw new BizException("未找到发药出库明细, 无法回库");
        }

        // 三期 C2: 有退药明细行 -> 行级回库路径; 无(改造前存量整方退) -> 回落旧整方全额回库路径
        List<HisDrugReturnItem> retItems = returnItemMapper.selectList(Wrappers.<HisDrugReturnItem>lambdaQuery()
                .eq(HisDrugReturnItem::getReturnId, dr.getId())
                .orderByAsc(HisDrugReturnItem::getId));
        if (retItems.isEmpty()) {
            returnApproveLegacy(dr, dispense, warehouseId, outItems);
            return dr;
        }

        // 2a. 加载发药行 + 逐行复核剩余可退 + 权威金额按划价原价重算(消除与申请时舍入漂移, 不退不平) + 归并各药本事务退量
        Map<Long, HisDispenseItem> itemById = new HashMap<>();
        for (HisDispenseItem di : dispenseItemMapper.selectList(Wrappers.<HisDispenseItem>lambdaQuery()
                .eq(HisDispenseItem::getDispenseId, dispense.getId()))) {
            itemById.put(di.getId(), di);
        }
        Map<Long, BigDecimal> txByDrug = new LinkedHashMap<>();
        Map<Long, BigDecimal> newQtyByItem = new HashMap<>();
        Map<Long, BigDecimal> newAmtByItem = new HashMap<>();
        BigDecimal totalRet = BigDecimal.ZERO;
        for (HisDrugReturnItem ri : retItems) {
            HisDispenseItem di = itemById.get(ri.getDispenseItemId());
            if (di == null) {
                throw new BizException("退药明细行对应发药行不存在: dispenseItemId=" + ri.getDispenseItemId());
            }
            BigDecimal q = bdOrZero(ri.getReturnQty());
            BigDecimal remain = bdOrZero(di.getDispenseQty()).subtract(bdOrZero(di.getReturnedQty()));
            if (q.compareTo(remain) > 0) {
                throw new BizException("退药数量超出该行剩余可退量, 请刷新后重试: " + di.getDrugName());
            }
            BigDecimal amt = lineReturnAmount(di, q);
            ri.setReturnAmount(amt);
            returnItemMapper.updateById(ri);
            totalRet = totalRet.add(amt);
            if (di.getDrugCatalogId() != null) {
                txByDrug.merge(di.getDrugCatalogId(), q, BigDecimal::add);
            }
            newQtyByItem.merge(di.getId(), q, BigDecimal::add);
            newAmtByItem.merge(di.getId(), amt, BigDecimal::add);
        }

        // 3a. 回库: 按药累计区间[已退,已退+本次) 映射到该药出库批次流, 保证不超批、回库对称、部分退只回本次量
        List<StockInItemReq> inItems = buildLineRestore(txByDrug, itemById, outItems);
        StockInReq inReq = new StockInReq();
        inReq.setOrgId(dispense.getOrgId());
        inReq.setWarehouseId(warehouseId);
        inReq.setInType(2);
        inReq.setRemark("退药回库(行级): " + dr.getReturnNo() + ", 发药单 " + dispense.getDispenseNo());
        inReq.setItems(inItems);
        HisStockIn stockIn = drugStockService.createStockIn(inReq);
        drugStockService.confirmStockIn(stockIn.getId());

        // 4a. 累加各发药行 returned(条件更新: returned_qty 乐观校验防并发丢失)
        for (HisDispenseItem di : itemById.values()) {
            BigDecimal dq = newQtyByItem.get(di.getId());
            if (dq == null) {
                continue;
            }
            BigDecimal before = bdOrZero(di.getReturnedQty());
            BigDecimal beforeAmt = bdOrZero(di.getReturnedAmount());
            int up = dispenseItemMapper.update(null, Wrappers.<HisDispenseItem>lambdaUpdate()
                    .set(HisDispenseItem::getReturnedQty, before.add(dq))
                    .set(HisDispenseItem::getReturnedAmount, beforeAmt.add(newAmtByItem.get(di.getId())))
                    .eq(HisDispenseItem::getId, di.getId())
                    .eq(HisDispenseItem::getReturnedQty, before));
            if (up != 1) {
                throw new BizException("发药行状态已变化, 请刷新后重试");
            }
        }

        // 5a. 整单是否全部退完: 全退 -> 发药记录 status 2->3 且处方 dispense_status 1->2; 部分 -> 维持(仍可继续退)
        boolean allReturned = true;
        for (HisDispenseItem di : itemById.values()) {
            BigDecimal rAfter = bdOrZero(di.getReturnedQty()).add(newQtyByItem.getOrDefault(di.getId(), BigDecimal.ZERO));
            if (rAfter.compareTo(bdOrZero(di.getDispenseQty())) < 0) {
                allReturned = false;
                break;
            }
        }
        if (allReturned) {
            int dispenseUpdated = dispenseMapper.update(null, Wrappers.<HisDispense>lambdaUpdate()
                    .set(HisDispense::getStatus, 3)
                    .eq(HisDispense::getId, dispense.getId())
                    .eq(HisDispense::getStatus, 2));
            if (dispenseUpdated != 1) {
                throw new BizException("发药记录状态已变化, 请刷新后重试");
            }
            jdbcTemplate.update("UPDATE his_prescription SET dispense_status = 2"
                            + " WHERE id = ? AND tenant_id = ? AND dispense_status = 1 AND deleted = 0",
                    dispense.getPrescriptionId(), tenantId());
        }

        // 6a. 退药记录置已退药(status=1), 金额回写本事务权威退药额
        dr.setStatus(1);
        dr.setReturnAmount(totalRet);
        dr.setApproveBy(currentUserName());
        dr.setApproveTime(LocalDateTime.now());
        returnMapper.updateById(dr);

        log.info("退药回库完成(行级): returnNo={}, dispenseNo={}, lines={}, returnAmount={}, allReturned={}, pharmacyId={}, warehouseId={}",
                dr.getReturnNo(), dispense.getDispenseNo(), retItems.size(), totalRet, allReturned,
                dispense.getPharmacyId(), warehouseId);
        return dr;
    }

    /** 退药记录分页(机构/状态/申请日期区间) */
    public IPage<HisDrugReturn> returnPage(Long orgId, Integer status, String startDate, String endDate,
                                          long page, long size) {
        LambdaQueryWrapper<HisDrugReturn> w = Wrappers.<HisDrugReturn>lambdaQuery()
                .eq(orgId != null, HisDrugReturn::getOrgId, orgId)
                .eq(status != null, HisDrugReturn::getStatus, status);
        LocalDateTime from = parseStart(startDate);
        LocalDateTime to = parseEnd(endDate);
        w.ge(from != null, HisDrugReturn::getCreateTime, from)
                .le(to != null, HisDrugReturn::getCreateTime, to)
                .orderByDesc(HisDrugReturn::getId);
        return returnMapper.selectPage(new Page<>(safePage(page), safeSize(size)), w);
    }

    /* ================= 内部实现 ================= */

    /** 发药出库单明细(out_type=1处方发药, ref_id=处方ID, 已确认), 批次级回库依据 */
    private List<Map<String, Object>> dispenseOutItems(Long prescriptionId) {
        return jdbcTemplate.queryForList(
                "SELECT soi.drug_catalog_id, soi.drug_code, soi.drug_name, soi.spec, soi.batch_no,"
                + " soi.qty, soi.cost_price, soi.retail_price"
                + " FROM his_stock_out_item soi"
                + " JOIN his_stock_out so ON so.id = soi.stock_out_id AND so.deleted = 0"
                + " WHERE so.ref_id = ? AND so.out_type = 1 AND so.status = 1"
                + " AND so.tenant_id = ? AND soi.deleted = 0"
                + " ORDER BY soi.id", prescriptionId, tenantId());
    }

    /* ================= 三期 C2: 行级部分退 内部实现 ================= */

    /** BigDecimal 空值兜底为 0。 */
    private static BigDecimal bdOrZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 发药落库后写 his_dispense_item(行级), 单价/金额取处方明细划价快照原价, 供行级部分退不退不平。 */
    private void writeDispenseItems(HisDispense dispense, List<Map<String, Object>> items) {
        Long orgId = dispense.getOrgId();
        for (Map<String, Object> it : items) {
            HisDispenseItem di = new HisDispenseItem();
            di.setOrgId(orgId);
            di.setDispenseId(dispense.getId());
            di.setPrescriptionItemId(toLong(it.get("id")));
            di.setDrugCatalogId(toLong(it.get("drug_id")));
            String code = str(it.get("item_code"));
            di.setDrugCode(StringUtils.hasText(code) ? code : "DRUG" + it.get("drug_id"));
            di.setDrugName(str(it.get("item_name")));
            di.setSpec(str(it.get("spec")));
            di.setUnit(str(it.get("unit")));
            di.setDispenseQty(bdOrZero(toBd(it.get("quantity"))));
            di.setBilledPrice(bdOrZero(toBd(it.get("price"))));
            di.setBilledAmount(bdOrZero(toBd(it.get("amount"))));
            di.setReturnedQty(BigDecimal.ZERO);
            di.setReturnedAmount(BigDecimal.ZERO);
            dispenseItemMapper.insert(di);
        }
    }

    /** 退药单公共字段填充(整方/行级共用)。 */
    private HisDrugReturn newReturnShell(HisDispense dispense, String reason) {
        HisDrugReturn dr = new HisDrugReturn();
        dr.setOrgId(dispense.getOrgId());
        dr.setReturnNo(generateNo("TY"));
        dr.setDispenseId(dispense.getId());
        dr.setVisitId(dispense.getVisitId());
        dr.setPatientId(dispense.getPatientId());
        dr.setPatientName(dispense.getPatientName());
        dr.setReason(reason);
        dr.setStatus(0);
        return dr;
    }

    /** 单行本次退药金额(按划价原价)。退完该行用"原价总额-已退金额"精确兜底, 消除逐次舍入累计误差(不退不平)。 */
    private BigDecimal lineReturnAmount(HisDispenseItem ln, BigDecimal qty) {
        BigDecimal disp = bdOrZero(ln.getDispenseQty());
        BigDecimal returned = bdOrZero(ln.getReturnedQty());
        BigDecimal billedAmt = bdOrZero(ln.getBilledAmount()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal returnedAmt = bdOrZero(ln.getReturnedAmount()).setScale(2, RoundingMode.HALF_UP);
        if (returned.add(qty).compareTo(disp) >= 0) {
            BigDecimal rem = billedAmt.subtract(returnedAmt);
            return rem.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : rem;
        }
        return bdOrZero(ln.getBilledPrice()).multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    /** 行级回库批次分配: 对每个药品, 把本事务退量映射到该药出库批次流的累计区间[prior, prior+this),
     *  prior=该药各发药行已退量之和(本事务未累加前), 保证任一批次累计回库不超其出库量、回库对称、部分退只回本次量。 */
    private List<StockInItemReq> buildLineRestore(Map<Long, BigDecimal> txByDrug,
                                                  Map<Long, HisDispenseItem> itemById,
                                                  List<Map<String, Object>> outItems) {
        Map<Long, List<Map<String, Object>>> flowByDrug = new LinkedHashMap<>();
        for (Map<String, Object> oi : outItems) {
            Long dc = toLong(oi.get("drug_catalog_id"));
            if (dc == null) {
                continue;
            }
            flowByDrug.computeIfAbsent(dc, k -> new ArrayList<>()).add(oi);
        }
        List<StockInItemReq> inItems = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : txByDrug.entrySet()) {
            Long dc = e.getKey();
            BigDecimal thisTx = bdOrZero(e.getValue());
            if (thisTx.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal prior = BigDecimal.ZERO;
            for (HisDispenseItem di : itemById.values()) {
                if (dc.equals(di.getDrugCatalogId())) {
                    prior = prior.add(bdOrZero(di.getReturnedQty()));
                }
            }
            List<Map<String, Object>> flow = flowByDrug.get(dc);
            if (flow == null || flow.isEmpty()) {
                throw new BizException("药品无发药出库批次, 无法回库: drugCatalogId=" + dc);
            }
            BigDecimal cumulative = prior.add(thisTx);
            BigDecimal cursor = BigDecimal.ZERO;
            BigDecimal allocated = BigDecimal.ZERO;
            for (Map<String, Object> oi : flow) {
                if (allocated.compareTo(thisTx) >= 0) {
                    break;
                }
                BigDecimal batchQty = bdOrZero(toBd(oi.get("qty")));
                BigDecimal segStart = cursor;
                BigDecimal segEnd = cursor.add(batchQty);
                cursor = segEnd;
                BigDecimal lo = prior.max(segStart);
                BigDecimal hi = cumulative.min(segEnd);
                BigDecimal take = hi.subtract(lo);
                if (take.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                StockInItemReq ii = new StockInItemReq();
                ii.setDrugCatalogId(dc);
                ii.setDrugCode(str(oi.get("drug_code")));
                ii.setDrugName(str(oi.get("drug_name")));
                ii.setSpec(str(oi.get("spec")));
                ii.setBatchNo(str(oi.get("batch_no")));
                ii.setQty(take);
                BigDecimal cost = toBd(oi.get("cost_price"));
                BigDecimal retail = toBd(oi.get("retail_price"));
                ii.setCostPrice(cost);
                ii.setRetailPrice(retail);
                BigDecimal amount = retail != null ? take.multiply(retail).setScale(2, RoundingMode.HALF_UP)
                        : (cost != null ? take.multiply(cost).setScale(2, RoundingMode.HALF_UP) : null);
                ii.setAmount(amount);
                inItems.add(ii);
                allocated = allocated.add(take);
            }
            if (allocated.compareTo(thisTx) < 0) {
                throw new BizException("回库数量超出该药出库批次总量, 无法回库: drugCatalogId=" + dc);
            }
        }
        return inItems;
    }

    /** 旧整方退路径(改造前无明细行的发药): 按全部发药出库明细逐批次全额回库 -> 发药记录/处方整单置已退药 -> 退药单置已退药。 */
    private void returnApproveLegacy(HisDrugReturn dr, HisDispense dispense, Long warehouseId,
                                     List<Map<String, Object>> outItems) {
        StockInReq inReq = new StockInReq();
        inReq.setOrgId(dispense.getOrgId());
        inReq.setWarehouseId(warehouseId);
        inReq.setInType(2);
        inReq.setRemark("退药回库: " + dr.getReturnNo() + ", 发药单 " + dispense.getDispenseNo());
        List<StockInItemReq> inItems = new ArrayList<>();
        for (Map<String, Object> oi : outItems) {
            StockInItemReq ii = new StockInItemReq();
            ii.setDrugCatalogId(toLong(oi.get("drug_catalog_id")));
            ii.setDrugCode(str(oi.get("drug_code")));
            ii.setDrugName(str(oi.get("drug_name")));
            ii.setSpec(str(oi.get("spec")));
            ii.setBatchNo(str(oi.get("batch_no")));
            BigDecimal qty = toBd(oi.get("qty"));
            ii.setQty(qty);
            ii.setCostPrice(toBd(oi.get("cost_price")));
            ii.setRetailPrice(toBd(oi.get("retail_price")));
            BigDecimal amount = null;
            if (qty != null && toBd(oi.get("retail_price")) != null) {
                amount = qty.multiply(toBd(oi.get("retail_price"))).setScale(2, RoundingMode.HALF_UP);
            } else if (qty != null && toBd(oi.get("cost_price")) != null) {
                amount = qty.multiply(toBd(oi.get("cost_price"))).setScale(2, RoundingMode.HALF_UP);
            }
            ii.setAmount(amount);
            inItems.add(ii);
        }
        inReq.setItems(inItems);
        HisStockIn stockIn = drugStockService.createStockIn(inReq);
        drugStockService.confirmStockIn(stockIn.getId());

        int dispenseUpdated = dispenseMapper.update(null, Wrappers.<HisDispense>lambdaUpdate()
                .set(HisDispense::getStatus, 3)
                .eq(HisDispense::getId, dispense.getId())
                .eq(HisDispense::getStatus, 2));
        if (dispenseUpdated != 1) {
            throw new BizException("发药记录状态已变化, 请刷新后重试");
        }
        jdbcTemplate.update("UPDATE his_prescription SET dispense_status = 2"
                        + " WHERE id = ? AND tenant_id = ? AND dispense_status = 1 AND deleted = 0",
                dispense.getPrescriptionId(), tenantId());
        dr.setStatus(1);
        dr.setApproveBy(currentUserName());
        dr.setApproveTime(LocalDateTime.now());
        returnMapper.updateById(dr);
        log.info("退药回库完成(整方): returnNo={}, dispenseNo={}, items={}, returnAmount={}",
                dr.getReturnNo(), dispense.getDispenseNo(), outItems.size(), dr.getReturnAmount());
    }

    /** 药品库存总量: warehouseId 非空按药房库存位维度, 为空按机构全院 SUM 各批次 */
    private BigDecimal stockQtyOf(Long orgId, Long warehouseId, Long drugCatalogId) {
        if (orgId == null || drugCatalogId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal qty;
        if (warehouseId != null) {
            qty = jdbcTemplate.queryForObject(
                    "SELECT IFNULL(SUM(qty), 0) FROM his_drug_stock"
                            + " WHERE tenant_id = ? AND org_id = ? AND warehouse_id = ? AND drug_catalog_id = ? AND status = 1 AND deleted = 0",
                    BigDecimal.class, tenantId(), orgId, warehouseId, drugCatalogId);
        } else {
            qty = jdbcTemplate.queryForObject(
                    "SELECT IFNULL(SUM(qty), 0) FROM his_drug_stock"
                            + " WHERE tenant_id = ? AND org_id = ? AND drug_catalog_id = ? AND status = 1 AND deleted = 0",
                    BigDecimal.class, tenantId(), orgId, drugCatalogId);
        }
        return qty == null ? BigDecimal.ZERO : qty;
    }

    /** 药房库存位(两级库存记账维度): stock_location_id 优先, 空回退旧 warehouse_id; 药房空/无库位返回 null(全院口径) */
    private Long warehouseOfPharmacy(Long pharmacyId) {
        HisPharmacyDef def = pharmacyDefService.find(pharmacyId);
        if (def == null) {
            return null;
        }
        return def.getStockLocationId() != null ? def.getStockLocationId() : def.getWarehouseId();
    }

    /** 处方快照(含改派守卫所需 dispense_status/status/charge_status 上下文); org_id 经就诊科室(his_dept)归属推导(his_visit 无 org_id 列) */
    private Map<String, Object> presRow(Long prescriptionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.id, p.rx_no, p.visit_id, p.patient_name, p.dept_name, p.total_amount,"
                        + " p.dispense_status, p.status, p.rx_type, p.pharmacy_id, p.transfer_from_pharmacy_id,"
                        + " v.dept_id, v.charge_status, d.org_id"
                        + " FROM his_prescription p"
                        + " LEFT JOIN his_visit v ON p.visit_id = v.id AND v.deleted = 0"
                        + " LEFT JOIN his_dept d ON v.dept_id = d.id AND d.deleted = 0"
                        + " WHERE p.id = ? AND p.tenant_id = ? AND p.deleted = 0",
                prescriptionId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "处方不存在");
        }
        return rows.get(0);
    }

    /** 处方需发药药品行(drug_id 非空) */
    private List<Map<String, Object>> requiredItems(Long prescriptionId) {
        return jdbcTemplate.queryForList(
                "SELECT " + DISPENSE_ITEM_COLS + " FROM his_prescription_item"
                        + " WHERE prescription_id = ? AND drug_id IS NOT NULL AND deleted = 0 ORDER BY id",
                prescriptionId);
    }

    /** 改派前置校验: 未作废(status>0)且未发药(dispense_status=0) */
    private void assertTransferrable(Map<String, Object> pres) {
        Number status = (Number) pres.get("status");
        if (status != null && status.intValue() < 0) {
            throw new BizException("处方已作废, 不可改派");
        }
        Number ds = (Number) pres.get("dispense_status");
        if (ds == null || ds.intValue() != 0) {
            throw new BizException("仅待发药状态的处方可改派药房");
        }
    }

    /** FIFO 估算(不扣减): 按有效期升序吃批次, 实发金额口径与出库确认一致(批次零售价优先, 空回退进价) */
    private Map<String, Object> estimateFifo(Long orgId, Long warehouseId, Long drugId, BigDecimal need) {
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal availSum = stockQtyOf(orgId, warehouseId, drugId);
        BigDecimal remaining = need;
        if (warehouseId != null && drugId != null && remaining.compareTo(BigDecimal.ZERO) > 0) {
            List<Map<String, Object>> batches = jdbcTemplate.queryForList(
                    "SELECT qty, IFNULL(retail_price, IFNULL(cost_price, 0)) price FROM his_drug_stock"
                            + " WHERE tenant_id = ? AND org_id = ? AND warehouse_id = ? AND drug_catalog_id = ?"
                            + " AND status = 1 AND deleted = 0 AND qty > 0"
                            + " ORDER BY COALESCE(exp_date, '9999-12-31'), id",
                    tenantId(), orgId, warehouseId, drugId);
            for (Map<String, Object> b : batches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal bq = nvlBd(toBd(b.get("qty")));
                if (bq.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal take = bq.min(remaining);
                amount = amount.add(take.multiply(nvlBd(toBd(b.get("price")))));
                remaining = remaining.subtract(take);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stockAmount", amount);
        out.put("available", availSum);
        out.put("shortage", availSum.compareTo(need) < 0);
        return out;
    }

    /* ================= 二期: 发药批次价差容差闸 ================= */

    /** 容差闸配置(读 sys_param, 四级作用域; 缺省=关闭以保持既有发药行为) */
    private static final class ToleranceCfg {
        boolean enabled;
        BigDecimal rate;    // 差率阈值(%), null/<=0 表示不按差率约束
        BigDecimal amount;  // 绝对额阈值(元), null/<=0 表示不按绝对额约束
        String action;      // block/override
    }

    /** 价差判定结果(有符号差额=预估实发-划价快照) */
    private static final class PriceDiffVerdict {
        boolean exceeded;
        BigDecimal billing;
        BigDecimal est;
        BigDecimal diff;
        BigDecimal rate;
        BigDecimal rateLimit;
        BigDecimal amountLimit;
        String action;
    }

    /** 读容差闸配置: 先读开关, 关闭则不再读阈值(每请求省参数查询) */
    private ToleranceCfg readToleranceCfg() {
        ToleranceCfg c = new ToleranceCfg();
        c.action = "block";
        c.enabled = "true".equalsIgnoreCase(nvlStr(systemParamResolver.resolve("pharmacy.price_diff_tolerance_enabled")));
        if (!c.enabled) {
            return c;
        }
        c.rate = parseCfgNum(systemParamResolver.resolve("pharmacy.price_diff_tolerance_rate"));
        c.amount = parseCfgNum(systemParamResolver.resolve("pharmacy.price_diff_tolerance_amount"));
        String a = systemParamResolver.resolve("pharmacy.price_diff_action");
        if (StringUtils.hasText(a)) {
            c.action = a.trim().toLowerCase();
        }
        return c;
    }

    /** 预估实发批次零售金额: 逐药按有效期升序 FIFO 吃批次(不扣减), 与 estimateFifo/出库确认同口径 */
    private BigDecimal estimateStockAmount(Long orgId, Long warehouseId, List<Map<String, Object>> items) {
        BigDecimal est = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            BigDecimal need = nvlBd(toBd(it.get("quantity")));
            if (need.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Map<String, Object> e = estimateFifo(orgId, warehouseId, toLong(it.get("drug_id")), need);
            est = est.add(nvlBd((BigDecimal) e.get("stockAmount")));
        }
        return est.setScale(2, RoundingMode.HALF_UP);
    }

    /** 依配置对"划价快照 vs 预估实发"判超阈(差率/绝对额任一越线即超) */
    private PriceDiffVerdict assessPriceDiff(ToleranceCfg cfg, BigDecimal billing, BigDecimal est) {
        PriceDiffVerdict v = new PriceDiffVerdict();
        BigDecimal b = nvlBd(billing).setScale(2, RoundingMode.HALF_UP);
        BigDecimal e = nvlBd(est).setScale(2, RoundingMode.HALF_UP);
        v.billing = b;
        v.est = e;
        v.diff = e.subtract(b);
        v.rateLimit = cfg.rate;
        v.amountLimit = cfg.amount;
        v.action = cfg.action;
        BigDecimal abs = v.diff.abs();
        if (b.compareTo(BigDecimal.ZERO) > 0) {
            v.rate = abs.multiply(BigDecimal.valueOf(100)).divide(b, 2, RoundingMode.HALF_UP);
        } else {
            v.rate = abs.compareTo(BigDecimal.ZERO) > 0 ? new BigDecimal("100.00") : BigDecimal.ZERO;
        }
        boolean rateBreach = cfg.rate != null && cfg.rate.compareTo(BigDecimal.ZERO) > 0 && v.rate.compareTo(cfg.rate) > 0;
        boolean absBreach = cfg.amount != null && cfg.amount.compareTo(BigDecimal.ZERO) > 0 && abs.compareTo(cfg.amount) > 0;
        v.exceeded = rateBreach || absBreach;
        return v;
    }

    /** 超阈拦截/提示文案(含划价/预估实发/差额/阈值/处置口径) */
    private String priceDiffBlockMsg(PriceDiffVerdict v) {
        StringBuilder sb = new StringBuilder("发药批次价差超容差: 划价 ").append(plain(v.billing))
                .append(" 元, 预估实发 ").append(plain(v.est)).append(" 元, 差额 ").append(plain(v.diff))
                .append("(率 ").append(plain(v.rate)).append("%)");
        if (v.rateLimit != null && v.rateLimit.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(", 差率阈值 ").append(plain(v.rateLimit)).append("%");
        }
        if (v.amountLimit != null && v.amountLimit.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(", 绝对额阈值 ").append(plain(v.amountLimit)).append(" 元");
        }
        if ("override".equalsIgnoreCase(v.action)) {
            sb.append("；按策略需主管放行(填写放行人与理由)后方可发药");
        } else {
            sb.append("；按策略已禁止发药，请改派有相近批次库存的药房或调整批次后重试");
        }
        return sb.toString();
    }

    /** 参数数值解析(空/非法返回 null) */
    private static BigDecimal parseCfgNum(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 将价差超阈放行留痕追写到发药备注(无留痕时保持原备注不变) */
    private static String composePriceDiffRemark(String originRemark, String priceDiffNote) {
        if (priceDiffNote == null) {
            return originRemark;
        }
        return StringUtils.hasText(originRemark) ? originRemark + " | " + priceDiffNote : priceDiffNote;
    }

    /** BigDecimal 展示文本(去科学计数法, 错误提示/清单用) */
    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nvlBd(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 单号生成: 前缀+yyyyMMdd+4位序号(FY发药/TY退药), synchronized 唯一, 跨日重置时DB回读当日最大序号兜底重启 */
    private synchronized String generateNo(String prefix) {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(prefix, today);
        }
        seqNo++;
        String no = prefix + today + String.format("%04d", seqNo);
        // 租户内唯一键兜底: 若序号已被占用(极端并发/脏数据)则继续自增直至可用
        while (noExists(prefix, no)) {
            seqNo++;
            no = prefix + today + String.format("%04d", seqNo);
        }
        return no;
    }

    /** 查当日已有单号最大序号(重启后防撞号) */
    private int maxSeqFromDb(String prefix, String today) {
        String like = prefix + today + "%";
        String maxNo;
        if ("FY".equals(prefix)) {
            HisDispense one = dispenseMapper.selectOne(Wrappers.<HisDispense>lambdaQuery()
                    .likeRight(HisDispense::getDispenseNo, like)
                    .orderByDesc(HisDispense::getDispenseNo)
                    .last("LIMIT 1"));
            maxNo = one == null ? null : one.getDispenseNo();
        } else {
            HisDrugReturn one = returnMapper.selectOne(Wrappers.<HisDrugReturn>lambdaQuery()
                    .likeRight(HisDrugReturn::getReturnNo, like)
                    .orderByDesc(HisDrugReturn::getReturnNo)
                    .last("LIMIT 1"));
            maxNo = one == null ? null : one.getReturnNo();
        }
        if (maxNo == null || maxNo.length() < prefix.length() + 12) {
            return 0;
        }
        try {
            return Integer.parseInt(maxNo.substring(prefix.length() + 8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 单号是否已存在(FY查发药记录, 其余查退药记录) */
    private boolean noExists(String prefix, String no) {
        if ("FY".equals(prefix)) {
            return dispenseMapper.selectCount(Wrappers.<HisDispense>lambdaQuery()
                    .eq(HisDispense::getDispenseNo, no)) > 0;
        }
        return returnMapper.selectCount(Wrappers.<HisDrugReturn>lambdaQuery()
                .eq(HisDrugReturn::getReturnNo, no)) > 0;
    }

    /* ================= 工具 ================= */

    private Long currentOrgId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getOrgId();
    }

    /** 当前操作人姓名(优先真名) */
    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }

    private static LocalDate parseDate(String d) {
        if (!StringUtils.hasText(d)) {
            return null;
        }
        try {
            return LocalDate.parse(d.trim());
        } catch (Exception e) {
            throw new BizException(400, "日期格式不正确(yyyy-MM-dd): " + d);
        }
    }

    private static LocalDateTime parseStart(String d) {
        LocalDate x = parseDate(d);
        return x == null ? null : x.atStartOfDay();
    }

    private static LocalDateTime parseEnd(String d) {
        LocalDate x = parseDate(d);
        return x == null ? null : x.atTime(23, 59, 59);
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
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

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String dispenseStatusText(Integer status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case 0: return "待发药";
            case 1: return "已调配";
            case 2: return "已发药";
            case 3: return "已退药";
            default: return String.valueOf(status);
        }
    }
}
