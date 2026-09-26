package com.yb.hi.service.pharmacy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.pharmacy.DispenseReq;
import com.yb.hi.dto.pharmacy.DrugReturnReq;
import com.yb.hi.dto.warehouse.StockInItemReq;
import com.yb.hi.dto.warehouse.StockInReq;
import com.yb.hi.dto.warehouse.StockOutItemReq;
import com.yb.hi.dto.warehouse.StockOutReq;
import com.yb.hi.entity.pharmacy.HisDispense;
import com.yb.hi.entity.pharmacy.HisDrugReturn;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockOut;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.pharmacy.HisDispenseMapper;
import com.yb.hi.mapper.pharmacy.HisDrugReturnMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final DrugStockService drugStockService;
    private final PharmacyDefService pharmacyDefService;
    private final JdbcTemplate jdbcTemplate;

    /** 单号内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public PharmacyService(HisDispenseMapper dispenseMapper, HisDrugReturnMapper returnMapper,
                           DrugStockService drugStockService, PharmacyDefService pharmacyDefService,
                           JdbcTemplate jdbcTemplate) {
        this.dispenseMapper = dispenseMapper;
        this.returnMapper = returnMapper;
        this.drugStockService = drugStockService;
        this.pharmacyDefService = pharmacyDefService;
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
        String joins = " FROM his_prescription p JOIN his_visit v ON p.visit_id = v.id AND v.deleted = 0";
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

    /** 发药详情: 处方信息 + 药品明细(含当前库存匹配, 供发药前核对) */
    public Map<String, Object> dispenseDetail(Long prescriptionId) {
        if (prescriptionId == null) {
            throw new BizException(400, "处方ID不能为空");
        }
        List<Map<String, Object>> presRows = jdbcTemplate.queryForList(
                "SELECT p.id, p.rx_no, p.visit_id, p.patient_id, p.patient_name, p.dr_name AS doctor_name,"
                + " p.dept_name, p.total_amount, p.dispense_status,"
                + " DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i:%s') AS create_time, v.ipt_otp_no AS visit_no"
                + " FROM his_prescription p LEFT JOIN his_visit v ON p.visit_id = v.id AND v.deleted = 0"
                + " WHERE p.id = ? AND p.deleted = 0", prescriptionId);
        if (presRows.isEmpty()) {
            throw new BizException(400, "处方不存在");
        }
        Map<String, Object> pres = presRows.get(0);
        Long orgId = currentOrgId();

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> itemRows = jdbcTemplate.queryForList(
                "SELECT pi.id, pi.drug_id, pi.item_code AS drug_code, pi.item_name AS drug_name, pi.spec, pi.unit,"
                + " pi.quantity AS qty, pi.price, pi.amount, pi.usage_method, pi.frequency,"
                + " pi.dosage, pi.dosage_unit, pi.administration"
                + " FROM his_prescription_item pi WHERE pi.prescription_id = ? AND pi.deleted = 0 ORDER BY pi.id",
                prescriptionId);
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
            BigDecimal stockQty = stockQtyOf(orgId, toLong(r.get("drug_id")));
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
                "SELECT id, visit_id, rx_no, patient_id, patient_name, dr_name AS doctor_name, dept_name,"
                + " total_amount, dispense_status, status FROM his_prescription"
                + " WHERE id = ? AND tenant_id = ? AND deleted = 0", prescriptionId, tenantId);
        if (rows.isEmpty()) {
            throw new BizException(400, "处方不存在");
        }
        Map<String, Object> pres = rows.get(0);
        Number rxStatus = (Number) pres.get("status");
        if (rxStatus != null && rxStatus.intValue() < 0) {
            throw new BizException("该处方已作废, 不可发药");
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
                "SELECT drug_id, item_code, item_name, spec, quantity"
                        + " FROM his_prescription_item"
                        + " WHERE prescription_id = ? AND drug_id IS NOT NULL AND deleted = 0 ORDER BY id",
                prescriptionId);
        if (items.isEmpty()) {
            throw new BizException("该处方无药品明细, 无需发药");
        }

        // 4. 确定发药机构(空则当前登录用户机构)
        Long orgId = req.getOrgId() != null ? req.getOrgId() : currentOrgId();
        if (orgId == null) {
            throw new BizException(400, "机构不能为空");
        }

        // 4.1 药房: 非空时校验归属机构/启停, 并解析关联药库(发药必须从该药房供药的药库扣减)
        Long pharmacyId = req.getPharmacyId();
        Long warehouseId = null;
        if (pharmacyId != null) {
            warehouseId = pharmacyDefService.requireEnabled(pharmacyId, orgId).getWarehouseId();
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
        HisStockOut stockOut = drugStockService.createStockOut(outReq);
        drugStockService.confirmStockOut(stockOut.getId());

        // 6. 落发药记录(一步发药到位: status=2已发药, 双签发药人/核对时间)
        HisDispense dispense = new HisDispense();
        dispense.setOrgId(orgId);
        dispense.setPharmacyId(pharmacyId);
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
        dispense.setRemark(req.getRemark());
        dispenseMapper.insert(dispense);

        log.info("发药完成: dispenseNo={}, prescriptionId={}, patient={}, items={}, total={}, pharmacyId={}, warehouseId={}",
                dispense.getDispenseNo(), prescriptionId, dispense.getPatientName(),
                items.size(), dispense.getTotalAmount(), pharmacyId, warehouseId);
        return dispense;
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

    /** 退药申请: 已发药(status=2)的发药记录可申请, 生成退药单(TY)待审核 */
    @Transactional(rollbackFor = Exception.class)
    public HisDrugReturn returnApply(DrugReturnReq req) {
        if (req == null || req.getDispenseId() == null) {
            throw new BizException(400, "发药记录ID不能为空");
        }
        HisDispense dispense = dispenseMapper.selectById(req.getDispenseId());
        if (dispense == null) {
            throw new BizException(400, "发药记录不存在");
        }
        if (dispense.getStatus() == null || dispense.getStatus() != 2) {
            throw new BizException("仅已发药的记录可申请退药");
        }
        HisDrugReturn dr = new HisDrugReturn();
        dr.setOrgId(dispense.getOrgId());
        dr.setReturnNo(generateNo("TY"));
        dr.setDispenseId(dispense.getId());
        dr.setVisitId(dispense.getVisitId());
        dr.setPatientId(dispense.getPatientId());
        dr.setPatientName(dispense.getPatientName());
        dr.setReason(req.getReason());
        dr.setStatus(0);
        dr.setReturnAmount(dispense.getTotalAmount() == null ? BigDecimal.ZERO : dispense.getTotalAmount());
        returnMapper.insert(dr);
        log.info("退药申请: returnNo={}, dispenseId={}, patient={}, pharmacyId={}",
                dr.getReturnNo(), dr.getDispenseId(), dr.getPatientName(), dispense.getPharmacyId());
        return dr;
    }

    /**
     * 退药审批: 通过 -> 按发药出库明细逐批次创建退药回库入库单(in_type=2)并确认(确认时按批次 upsert 库存加量)
     * -> 发药记录置已退药(status=3) -> 处方置已退药(dispense_status=2); 驳回 -> 退药记录置已驳回(status=2)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisDrugReturn returnApprove(Long id, boolean approved) {
        if (id == null) {
            throw new BizException(400, "退药记录ID不能为空");
        }
        HisDrugReturn dr = returnMapper.selectById(id);
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
        // 原发药药房关联药库(药库参数待药库单支持 warehouseId 后透传; 药房已停用不影响历史单据回溯)
        Long warehouseId = null;
        HisPharmacyDef pharmacyDef = pharmacyDefService.find(dispense.getPharmacyId());
        if (pharmacyDef != null) {
            warehouseId = pharmacyDef.getWarehouseId();
        }
        List<Map<String, Object>> outItems = dispenseOutItems(dispense.getPrescriptionId());
        if (outItems.isEmpty()) {
            throw new BizException("未找到发药出库明细, 无法回库");
        }

        // 2. 创建退药回库入库单并确认: 确认时按 (机构+药品+批次) upsert 库存加量, 单次回补可追溯
        StockInReq inReq = new StockInReq();
        inReq.setOrgId(dispense.getOrgId());
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
            // 回库小计: 优先 数量*零售价, 零售价空则 数量*进价(与出库单金额口径一致)
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

        // 3. 发药记录置已退药(status=3)
        HisDispense updDispense = new HisDispense();
        updDispense.setId(dispense.getId());
        updDispense.setStatus(3);
        dispenseMapper.updateById(updDispense);

        // 4. 处方置已退药(dispense_status=2)
        jdbcTemplate.update("UPDATE his_prescription SET dispense_status = 2"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                dispense.getPrescriptionId(), tenantId());

        // 5. 退药记录置已退药(status=1)
        dr.setStatus(1);
        dr.setApproveBy(currentUserName());
        dr.setApproveTime(LocalDateTime.now());
        returnMapper.updateById(dr);

        log.info("退药回库完成: returnNo={}, dispenseNo={}, items={}, returnAmount={}, pharmacyId={}, warehouseId={}",
                dr.getReturnNo(), dispense.getDispenseNo(), outItems.size(), dr.getReturnAmount(),
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

    /** 药品当前库存总量(按机构 SUM 各批次, 批次无关) */
    private BigDecimal stockQtyOf(Long orgId, Long drugCatalogId) {
        if (orgId == null || drugCatalogId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal qty = jdbcTemplate.queryForObject(
                "SELECT IFNULL(SUM(qty), 0) FROM his_drug_stock"
                        + " WHERE tenant_id = ? AND org_id = ? AND drug_catalog_id = ? AND deleted = 0",
                BigDecimal.class, tenantId(), orgId, drugCatalogId);
        return qty == null ? BigDecimal.ZERO : qty;
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
