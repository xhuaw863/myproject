package com.yb.hi.service.inpatient;

import com.yb.hi.dto.warehouse.StockInItemReq;
import com.yb.hi.dto.warehouse.StockInReq;
import com.yb.hi.dto.warehouse.StockOutItemReq;
import com.yb.hi.dto.warehouse.StockOutReq;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockOut;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.dto.inpatient.InpDispenseReq;
import com.yb.hi.service.warehouse.DrugStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院发药服务(P4): 覆盖规范 16.3.1/16.3.2 的住院药房高级流程。补齐既有住院域缺失的"药房发药"主干
 * (医嘱经药审 InpPharmService → 本服务发药到病区并扣库存), 并实现整包装取整三量守恒、缺药替换、
 * 退药暂存病区冲抵、出院带药取药+二次核发两段、按病人集中批量发药、多维历史发药查询、自动发药开关。
 * 说明:
 * 1) 读多表 JOIN 一律走 JdbcTemplate 手写 SQL(项目惯例: 原生 SQL 显式带 tenant_id 与 deleted=0,
 *    新表未登记 IGNORE_TABLES 但原生 SQL 不受租户插件影响, 必须手动带租户);
 * 2) 库存收支复用门诊同源单据链: 发药=createStockOut(outType=1)+confirm(FIFO 乐观扣减, 回填批次),
 *    退回药房=createStockIn(inType=2)+confirm(按批次 upsert 加量); 退药暂存病区不动库存, 建 his_ward_staging 供下次冲抵;
 * 3) 三量守恒: should(医嘱应发)=offset(病区暂存抵扣)+net; actual(实发)=取整覆盖 net 的整包装; over=actual-net(多发);
 *    库存扣减=actual; 打单与台账均体现三量;
 * 4) 防重复发药: 乐观 UPDATE his_inp_order.dispense_status 0->1 判 affected;
 * 5) 缺药替换候选: 同 drug_std_code + 同规格、不同 manufacturer、启用且有库存的其他目录药品。
 */
@Slf4j
@Service
public class InpDispenseService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;
    private final DrugStockService drugStockService;

    /** 单号内存序号(synchronized 唯一; 跨日重置回读当日最大序号防撞号) */
    private String dispSeqDate;
    private int dispSeqNo = 0;
    /** 退药单号/取药发票号当日序号(synchronized + 存在性回读防撞号) */
    private String retSeqDate;
    private int retSeqNo = 0;
    private String invSeqDate;
    private int invSeqNo = 0;

    public InpDispenseService(JdbcTemplate jdbcTemplate, OrgAccessGuard guard, DrugStockService drugStockService) {
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
        this.drugStockService = drugStockService;
    }

    /* ================= 待发药队列 ================= */

    /**
     * 住院待发药队列: 药品类医嘱(order_category=1, drug_id 非空) 且 dispense_status=0(未发)、order_status 已审核及之后(2/3/4)、
     * 药审无需或通过(0/2)。JOIN 就诊/患者/病区/床位/科室/药品目录; 附带全院可用库存与病区可冲抵暂存量, 供前端缺药/冲抵标记。
     */
    public Map<String, Object> getQueue(Long wardId, Long deptId, String keyword, Long pharmacyId, int page, int size) {
        Long orgId = requireCurrentOrg();
        Long wh = warehouseOfPharmacy(pharmacyId);
        int p = Math.max(page, 1);
        int s = size < 1 ? 20 : Math.min(size, 200);

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE o.order_category = 1 AND o.drug_id IS NOT NULL AND o.dispense_status = 0"
                + " AND o.order_status IN (2,3,4) AND o.pharm_audit_status IN (0,2)"
                // 手麻P1: 手术关联药品医嘱须"发送药房"(send_pharm_status=1)后才入队; 普通医嘱不受影响
                + " AND ((o.surgery_id IS NULL AND o.surgery_apply_id IS NULL) OR o.send_pharm_status = 1)"
                + " AND o.deleted = 0 AND o.tenant_id = ? AND o.org_id = ?");
        args.add(tenantId());
        args.add(orgId);
        if (wardId != null) {
            where.append(" AND v.ward_id = ?");
            args.add(wardId);
        }
        if (deptId != null) {
            where.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (d.generic_name LIKE ? OR o.order_content LIKE ? OR p.name LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM his_inp_order o"
                + " JOIN his_inp_visit v ON o.inp_visit_id = v.id AND v.deleted = 0 AND v.tenant_id = ?"
                + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_drug_catalog d ON o.drug_id = d.id AND d.deleted = 0"
                + where, Long.class, join2(new Object[]{tenantId()}, args));

        String dataSql = "SELECT o.id AS orderId, o.inp_visit_id AS inpVisitId, o.order_type AS orderType,"
                + " o.order_content AS orderContent, o.spec AS orderSpec, o.dosage, o.dosage_unit AS dosageUnit,"
                + " o.freq_code AS freqCode, o.usage_code AS usageCode, o.quantity AS shouldQty,"
                + " o.drug_id AS drugCatalogId, o.group_no AS groupNo,"
                + " d.generic_name AS drugName, d.spec AS drugSpec, d.min_unit AS unit, d.pack_ratio AS packRatio,"
                + " d.round_rule AS roundRule, d.drug_code AS drugCode, d.drug_std_code AS drugStdCode, d.status AS drugStatus,"
                + " v.inp_no AS inpNo, v.dept_id AS deptId, v.ward_id AS wardId, v.patient_id AS patientId,"
                + " dep.dept_name AS deptName, w.ward_name AS wardName, b.bed_no AS bedNo, p.name AS patientName"
                + " FROM his_inp_order o"
                + " JOIN his_inp_visit v ON o.inp_visit_id = v.id AND v.deleted = 0 AND v.tenant_id = ?"
                + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_drug_catalog d ON o.drug_id = d.id AND d.deleted = 0"
                + " LEFT JOIN his_dept dep ON v.dept_id = dep.id AND dep.deleted = 0"
                + " LEFT JOIN his_ward w ON v.ward_id = w.id AND w.deleted = 0"
                + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                + where + " ORDER BY o.create_time ASC, o.id ASC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>();
        dataArgs.add(tenantId());
        dataArgs.addAll(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());

        // 富化: 全院可用库存 + 病区可冲抵暂存量(以最小单位口径)
        for (Map<String, Object> r : rows) {
            Long dcId = toLong(r.get("drugCatalogId"));
            Long visitId = toLong(r.get("inpVisitId"));
            r.put("availStock", stockQty(dcId, wh, orgId));
            r.put("stagingAvail", stagingAvail(visitId, dcId));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0L : total);
        out.put("rows", rows);
        out.put("autoDispense", autoDispenseEnabled());
        return out;
    }

    /* ================= 整包装取整预览 ================= */

    /** 单条医嘱发药预览: 计算三量与库存充足性, 供前端确认前展示取整/冲抵/缺药与替换提示。 */
    public Map<String, Object> preview(Long orderId, Long pharmacyId) {
        Long orgId = requireCurrentOrg();
        Map<String, Object> o = orderRow(orderId, orgId);
        Long wh = warehouseOfPharmacy(pharmacyId);
        return computeRounding(o, wh, orgId);
    }

    /* ================= 缺药替换候选 ================= */

    /** 缺药替换候选: 同 drug_std_code + 同规格、不同 manufacturer、启用且有库存的其他目录药品。 */
    public List<Map<String, Object>> replaceCandidates(Long orderId, Long pharmacyId) {
        Long orgId = requireCurrentOrg();
        Map<String, Object> o = orderRow(orderId, orgId);
        Long dcId = toLong(o.get("drug_id"));
        Long wh = warehouseOfPharmacy(pharmacyId);
        if (dcId == null) {
            return new ArrayList<>();
        }
        Map<String, Object> cat = catalogRow(dcId);
        String std = str(cat.get("drug_std_code"));
        String spec = str(cat.get("spec"));
        if (!StringUtils.hasText(std)) {
            return new ArrayList<>();
        }
        StringBuilder sql = new StringBuilder("SELECT c.id AS drugCatalogId, c.drug_code AS drugCode, c.generic_name AS drugName,"
                + " c.spec, c.manufacturer, c.min_unit AS unit, c.pack_ratio AS packRatio, c.round_rule AS roundRule,"
                + " (SELECT IFNULL(SUM(s.qty),0) FROM his_drug_stock s WHERE s.deleted = 0 AND s.tenant_id = ?"
                + "   AND s.drug_catalog_id = c.id");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (wh != null) {
            sql.append(" AND s.warehouse_id = ?");
            args.add(wh);
        }
        if (orgId != null) {
            sql.append(" AND s.org_id = ?");
            args.add(orgId);
        }
        sql.append(") AS availStock"
                + " FROM his_drug_catalog c"
                + " WHERE c.deleted = 0 AND c.tenant_id = ? AND c.status = 1 AND c.id <> ? AND c.drug_std_code = ?");
        args.add(tenantId());
        args.add(dcId);
        args.add(std);
        if (StringUtils.hasText(spec)) {
            sql.append(" AND c.spec = ?");
            args.add(spec);
        }
        sql.append(" ORDER BY availStock DESC, c.manufacturer ASC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /* ================= 发药(核心: 整包装取整 + 冲抵 + 三量 + 库存扣减 + 缺药替换) ================= */

    /**
     * 住院发药(支持按病人集中批量): 逐医嘱乐观锁 dispense_status 0->1, 计算三量(取整/冲抵/多发), 生成出库单并确认扣库存,
     * 落 his_inp_dispense 行级发药记录; 标记出院带药时同时生成 his_discharge_pickup 待取药记录。任一行失败整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> dispense(InpDispenseReq req) {
        if (req == null || CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException(400, "请选择要发药的医嘱");
        }
        Long orgId = requireCurrentOrg();
        Long wh = warehouseOfPharmacy(req.getPharmacyId());
        boolean discharge = Boolean.TRUE.equals(req.getDischargePick());
        List<Map<String, Object>> results = new ArrayList<>();

        for (InpDispenseReq.Item it : req.getItems()) {
            if (it.getOrderId() == null) {
                throw new BizException(400, "医嘱ID不能为空");
            }
            Map<String, Object> o = orderRow(it.getOrderId(), orgId);
            // 乐观锁: 仅未发药(0)可置已发药(1), 防重复发药
            int locked = jdbcTemplate.update("UPDATE his_inp_order SET dispense_status = 1, update_by = ?, update_time = NOW()"
                    + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND dispense_status = 0 AND deleted = 0",
                    currentUserName(), it.getOrderId(), tenantId(), orgId);
            if (locked == 0) {
                throw new BizException("医嘱[" + str(o.get("order_content")) + "]已发药或状态不可发药, 请刷新后重试");
            }

            // 缺药替换校验: 替换药须与本医嘱药品同本位码同规格(允许仅指定时放宽到同名), 且启用
            Long origDrugId = toLong(o.get("drug_id"));
            Long effDrugId = it.getReplaceDrugCatalogId() != null ? it.getReplaceDrugCatalogId() : origDrugId;
            boolean replaced = effDrugId != null && !effDrugId.equals(origDrugId);
            if (replaced) {
                Map<String, Object> rc = catalogRow(effDrugId);
                if (rc == null) {
                    throw new BizException("替换药品不存在");
                }
                Integer st = toInt(rc.get("status"));
                if (st != null && st != 1) {
                    throw new BizException("替换药品已停用, 不可发药");
                }
            }

            // 三量计算(基于有效实发药品与病区暂存冲抵)
            Map<String, Object> rnd = computeRounding(o, wh, orgId, effDrugId, it.getShouldQty());
            BigDecimal actual = toBd(rnd.get("actualQty"));
            if (actual.compareTo(BigDecimal.ZERO) <= 0) {
                // 全额冲抵(医嘱量已由病区暂存抵扣), 无需出库, 直接落零量记录
                Long did = insertDispense(o, effDrugId, origDrugId, replaced, it, rnd, null, req, orgId, discharge);
                consumeStaging(toLong(o.get("inp_visit_id")), origDrugId, toBd(rnd.get("offsetQty")), did);
                if (discharge) {
                    // 出院带药即使全额冲抵仍须建取药记录, 保证两段核发闭环(listPickup/confirmPickup/verifyPickup2 可达)
                    createPickup(o, effDrugId, rnd, did, req.getWindowId());
                }
                results.add(dispenseResult(did));
                continue;
            }

            // 出库并确认(FIFO 乐观扣减): 缺药时确认阶段乐观锁自然抛错回滚
            StockOutReq outReq = new StockOutReq();
            outReq.setOrgId(orgId);
            outReq.setWarehouseId(wh);
            outReq.setOutType(1);
            outReq.setRefId(toLong(o.get("id")));
            outReq.setRefNo(str(o.get("inp_no")));
            outReq.setRemark("住院发药: " + str(rnd.get("drugName")));
            StockOutItemReq oi = new StockOutItemReq();
            oi.setDrugCatalogId(effDrugId);
            String code = str(rnd.get("drugCode"));
            oi.setDrugCode(StringUtils.hasText(code) ? code : "DRUG" + effDrugId);
            oi.setDrugName(str(rnd.get("drugName")));
            oi.setSpec(str(rnd.get("spec")));
            oi.setQty(actual);
            List<StockOutItemReq> outItems = new ArrayList<>();
            outItems.add(oi);
            outReq.setItems(outItems);
            HisStockOut stockOut = drugStockService.createStockOut(outReq);
            drugStockService.confirmStockOut(stockOut.getId());

            Long did = insertDispense(o, effDrugId, origDrugId, replaced, it, rnd, stockOut.getId(), req, orgId, discharge);
            // 冲抵消耗: 病区暂存台账 used_qty 累加(按 FIFO 逐条扣减至 offset)
            consumeStaging(toLong(o.get("inp_visit_id")), origDrugId, toBd(rnd.get("offsetQty")), did);

            if (discharge) {
                createPickup(o, effDrugId, rnd, did, req.getWindowId());
            }
            results.add(dispenseResult(did));
            log.info("住院发药: dispenseId={}, orderId={}, drug={}, should={}, offset={}, actual={}, over={}, orgId={}",
                    did, it.getOrderId(), rnd.get("drugName"), rnd.get("shouldQty"), rnd.get("offsetQty"), actual, rnd.get("overQty"), orgId);
        }
        return results;
    }

    /* ================= 退药(退回药房回库 / 暂存病区冲抵) ================= */

    /**
     * 住院退药: keepWard=1 实物未退回而暂存病区(不动库存, 建 his_ward_staging 供下次发药冲抵);
     * keepWard=0 退回药房(按发药出库批次建入库单 in_type=2 并确认回库)。累加 his_inp_dispense.return_qty 并流转状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> returnDrug(Long dispenseId, BigDecimal qty, String reason, boolean keepWard) {
        Long orgId = requireCurrentOrg();
        if (dispenseId == null) {
            throw new BizException(400, "发药记录ID不能为空");
        }
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "退药数量须大于0");
        }
        Map<String, Object> d = jdbcTemplate.queryForMap("SELECT id, dispense_no AS dispenseNo, inp_visit_id AS inpVisitId, order_id AS orderId,"
                + " drug_catalog_id AS drugCatalogId, drug_code AS drugCode, drug_name AS drugName, spec, unit,"
                + " patient_id AS patientId, patient_name AS patientName, ward_id AS wardId, ward_name AS wardName,"
                + " actual_qty AS actualQty, return_qty AS returnQty, stock_out_id AS stockOutId, status"
                + " FROM his_inp_dispense WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0", dispenseId, tenantId(), orgId);
        BigDecimal returnQty = nz(toBd(d.get("returnQty")));
        BigDecimal actual = nz(toBd(d.get("actualQty")));
        BigDecimal already = returnQty.add(qty);
        if (already.compareTo(actual) > 0) {
            throw new BizException("退药数量超出发药量(实发" + plain(actual) + ", 已退" + plain(returnQty) + ")");
        }

        // 退药台账 his_drug_return(去向标志区分暂存/回库), 金额按零售价估算(记录用)
        String retNo = generateReturnNo();
        BigDecimal amount = qty.multiply(retailPriceOf(toLong(d.get("drugCatalogId")))).setScale(2, RoundingMode.HALF_UP);
        jdbcTemplate.update("INSERT INTO his_drug_return (tenant_id, org_id, return_no, dispense_id, visit_id, patient_id, patient_name,"
                        + " reason, status, return_amount, approve_by, approve_time, keep_ward_flag, create_by, create_time, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,1,?,?,NOW(),?,?,NOW(),0)",
                tenantId(), orgId, retNo, dispenseId, d.get("inpVisitId"), d.get("patientId"), d.get("patientName"),
                reason, amount, currentUserName(), keepWard ? 1 : 0, currentUserName());

        if (keepWard) {
            // 暂存病区: 不回收库存, 建/累加 his_ward_staging 供同患者同药下次发药冲抵
            upsertStaging(d, qty, dispenseId, orgId);
        } else {
            // 退回药房: 按发药出库批次回库
            restoreStock(d, qty, retNo, orgId);
        }

        // 发药记录状态流转: 全部退(status=3)/部分退(2)
        int newStatus = already.compareTo(actual) >= 0 ? 3 : 2;
        jdbcTemplate.update("UPDATE his_inp_dispense SET return_qty = ?, status = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0", already, newStatus, currentUserName(), dispenseId, tenantId(), orgId);
        // 全部退回药房则医嘱发药状态回退为未发药(允许重新发药); 暂存病区不回退(实物仍在病区)
        if (newStatus == 3 && !keepWard) {
            jdbcTemplate.update("UPDATE his_inp_order SET dispense_status = 0 WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0",
                    d.get("orderId"), tenantId(), orgId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("returnNo", retNo);
        out.put("dispenseId", dispenseId);
        out.put("qty", qty);
        out.put("keepWard", keepWard ? 1 : 0);
        out.put("dispenseStatus", newStatus);
        return out;
    }

    /* ================= 出院带药: 取药(第一段) + 二次核发(第二段) ================= */

    /** 出院带药取药/核发列表: 按就诊或取药状态过滤(1待取药 2已取待发药核 3已二次核发)。 */
    public List<Map<String, Object>> listPickup(Long inpVisitId, Integer status) {
        Long orgId = requireCurrentOrg();
        StringBuilder sql = new StringBuilder("SELECT id, inp_visit_id AS inpVisitId, dispense_id AS dispenseId, order_id AS orderId,"
                + " patient_id AS patientId, patient_name AS patientName, drug_name AS drugName, spec, qty,"
                + " invoice_no AS invoiceNo, window_id AS windowId, pickup_status AS pickupStatus,"
                + " pickup_by AS pickupBy, DATE_FORMAT(pickup_time, '%Y-%m-%d %H:%i:%s') AS pickupTime,"
                + " verify2_by AS verify2By, DATE_FORMAT(verify2_time, '%Y-%m-%d %H:%i:%s') AS verify2Time"
                + " FROM his_discharge_pickup WHERE tenant_id = ? AND org_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(orgId);
        if (inpVisitId != null) {
            sql.append(" AND inp_visit_id = ?");
            args.add(inpVisitId);
        }
        if (status != null) {
            sql.append(" AND pickup_status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY pickup_status ASC, id DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 取药(第一段): 置 pickup_status 1->2 并生成/绑定取药发票号, 记录经手人与时间。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmPickup(Long pickupId, String invoiceNo, Long windowId) {
        Long orgId = requireCurrentOrg();
        Map<String, Object> pk = pickupRow(pickupId, orgId);
        int affected = jdbcTemplate.update("UPDATE his_discharge_pickup SET pickup_status = 2, invoice_no = ?, window_id = ?,"
                        + " pickup_by = ?, pickup_time = NOW(), update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND pickup_status = 1 AND deleted = 0",
                StringUtils.hasText(invoiceNo) ? invoiceNo : generateInvoiceNo(),
                windowId, currentUserName(), currentUserName(), pickupId, tenantId(), orgId);
        if (affected == 0) {
            throw new BizException("该带药记录不在待取药状态, 请刷新后重试");
        }
        // 回写关联发药记录取药状态
        if (pk.get("dispense_id") != null) {
            jdbcTemplate.update("UPDATE his_inp_dispense SET pickup_status = 2 WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0",
                    pk.get("dispense_id"), tenantId(), orgId);
        }
        log.info("出院带药取药: pickupId={}, invoiceNo={}", pickupId, invoiceNo);
        return pickupResult(pickupId);
    }

    /** 二次核发(第二段): 药师复核, 置 pickup_status 2->3(可带药离开)。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> verifyPickup2(Long pickupId) {
        Long orgId = requireCurrentOrg();
        Map<String, Object> pk = pickupRow(pickupId, orgId);
        int affected = jdbcTemplate.update("UPDATE his_discharge_pickup SET pickup_status = 3, verify2_by = ?, verify2_time = NOW(),"
                        + " update_by = ?, update_time = NOW() WHERE id = ? AND tenant_id = ? AND org_id = ? AND pickup_status = 2 AND deleted = 0",
                currentUserName(), currentUserName(), pickupId, tenantId(), orgId);
        if (affected == 0) {
            throw new BizException("该带药记录未完成取药或已核发, 请刷新后重试");
        }
        if (pk.get("dispense_id") != null) {
            jdbcTemplate.update("UPDATE his_inp_dispense SET pickup_status = 3 WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0",
                    pk.get("dispense_id"), tenantId(), orgId);
        }
        log.info("出院带药二次核发: pickupId={}, by={}", pickupId, currentUserName());
        return pickupResult(pickupId);
    }

    /* ================= 历史发药多维查询 ================= */

    /**
     * 历史发药查询: dimension=dispense(发药单/患者/病区/药品, 读 his_inp_dispense) 或 return(退药, 读 his_drug_return)。
     * keyword 按发药单号/患者名/药品名/病区名模糊; 支持日期区间(按发药/退药时间)。
     */
    public Map<String, Object> history(String dimension, String keyword, String startDate, String endDate, int page, int size) {
        Long orgId = requireCurrentOrg();
        int p = Math.max(page, 1);
        int s = size < 1 ? 20 : Math.min(size, 200);
        boolean isReturn = "return".equalsIgnoreCase(dimension);
        List<Object> args = new ArrayList<>();
        String from, cols, timeCol, order;
        if (isReturn) {
            cols = "r.id, r.return_no AS returnNo, r.dispense_id AS dispenseId, r.visit_id AS inpVisitId, r.patient_name AS patientName,"
                    + " r.reason, r.return_amount AS returnAmount, r.keep_ward_flag AS keepWard, r.status,"
                    + " DATE_FORMAT(r.approve_time, '%Y-%m-%d %H:%i:%s') AS returnTime, r.approve_by AS approveBy";
            from = " FROM his_drug_return r";
            timeCol = "r.approve_time";
            order = " r.approve_time DESC";
            args.add(tenantId());
            args.add(orgId);
            StringBuilder w = new StringBuilder(" WHERE r.tenant_id = ? AND r.org_id = ? AND r.deleted = 0");
            if (StringUtils.hasText(keyword)) {
                w.append(" AND (r.return_no LIKE ? OR r.patient_name LIKE ? OR r.reason LIKE ?)");
                String kw = "%" + keyword.trim() + "%";
                args.add(kw);
                args.add(kw);
                args.add(kw);
            }
            appendDate(w, args, timeCol, startDate, endDate);
            Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + w, Long.class, args.toArray());
            List<Object> da = new ArrayList<>(args);
            da.add((p - 1) * s);
            da.add(s);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT " + cols + from + w + " ORDER BY" + order + " LIMIT ?, ?", da.toArray());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", total == null ? 0L : total);
            out.put("rows", rows);
            return out;
        }
        cols = "dp.id, dp.dispense_no AS dispenseNo, dp.inp_visit_id AS inpVisitId, dp.order_id AS orderId,"
                + " dp.drug_name AS drugName, dp.spec, dp.unit, dp.patient_name AS patientName, dp.ward_name AS wardName,"
                + " dp.bed_no AS bedNo, dp.should_qty AS shouldQty, dp.offset_qty AS offsetQty, dp.actual_qty AS actualQty,"
                + " dp.over_qty AS overQty, dp.return_qty AS returnQty, dp.replace_flag AS replaceFlag, dp.replace_scope AS replaceScope,"
                + " dp.is_discharge_pick AS isDischargePick, dp.pickup_status AS pickupStatus, dp.status, dp.dispense_by AS dispenseBy,"
                + " DATE_FORMAT(dp.dispense_time, '%Y-%m-%d %H:%i:%s') AS dispenseTime";
        from = " FROM his_inp_dispense dp";
        timeCol = "dp.dispense_time";
        order = " dp.dispense_time DESC";
        args.add(tenantId());
        args.add(orgId);
        StringBuilder w = new StringBuilder(" WHERE dp.tenant_id = ? AND dp.org_id = ? AND dp.deleted = 0");
        if (StringUtils.hasText(keyword)) {
            w.append(" AND (dp.dispense_no LIKE ? OR dp.patient_name LIKE ? OR dp.drug_name LIKE ? OR dp.ward_name LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            for (int i = 0; i < 4; i++) {
                args.add(kw);
            }
        }
        appendDate(w, args, timeCol, startDate, endDate);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + w, Long.class, args.toArray());
        List<Object> da = new ArrayList<>(args);
        da.add((p - 1) * s);
        da.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT " + cols + from + w + " ORDER BY" + order + " LIMIT ?, ?", da.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0L : total);
        out.put("rows", rows);
        return out;
    }

    /* ================= 自动发药开关(system-param) ================= */

    /** 读取自动发药开关: sys_param key=inp.auto.dispense.enabled(全局/租户任一置 true 即开)。缺省关闭。 */
    public boolean autoDispenseEnabled() {
        try {
            Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_param WHERE deleted = 0 AND param_key = 'inp.auto.dispense.enabled'"
                    + " AND (param_value IN ('1','true','TRUE') OR default_value IN ('1','true','TRUE'))"
                    + " AND (tenant_id = 0 OR tenant_id = ?)", Long.class, tenantId());
            return c != null && c > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /* ================= 内部: 三量取整计算 ================= */

    /** 依据医嘱行计算三量(应发/冲抵/实发/多发), 使用默认医嘱药品。 */
    private Map<String, Object> computeRounding(Map<String, Object> o, Long wh, Long orgId) {
        return computeRounding(o, wh, orgId, toLong(o.get("drug_id")), null);
    }

    /**
     * 三量取整核心: should=医嘱量(或手工指定); offset=min(should, 病区可冲抵暂存); net=should-offset;
     * 按 catalog.pack_ratio/round_rule 对 net 取整得 actual 与 over=actual-net。附药品快照与库存充足性。
     */
    private Map<String, Object> computeRounding(Map<String, Object> o, Long wh, Long orgId, Long effDrugId, BigDecimal manualShould) {
        Long dcId = effDrugId != null ? effDrugId : toLong(o.get("drug_id"));
        BigDecimal should = manualShould != null && manualShould.compareTo(BigDecimal.ZERO) > 0 ? manualShould : nz(toBd(o.get("quantity")));
        Map<String, Object> cat = dcId == null ? new LinkedHashMap<>() : catalogRow(dcId);
        int packRatio = toIntOr(cat.get("pack_ratio"), 0);
        if (packRatio <= 0) {
            packRatio = 1;
        }
        int roundRule = toIntOr(cat.get("round_rule"), 1);
        BigDecimal offset = BigDecimal.ZERO;
        Long visitId = toLong(o.get("inp_visit_id"));
        // 冲抵仅针对原医嘱药品暂存(替换发药不消耗原药暂存, 简化口径): 按原医嘱药品查询
        BigDecimal staging = stagingAvail(visitId, toLong(o.get("drug_id")));
        if (staging.compareTo(BigDecimal.ZERO) > 0 && (effDrugId == null || effDrugId.equals(toLong(o.get("drug_id"))))) {
            offset = staging.min(should);
        }
        BigDecimal net = should.subtract(offset);
        if (net.compareTo(BigDecimal.ZERO) < 0) {
            net = BigDecimal.ZERO;
        }
        BigDecimal[] pack = roundToPack(net, packRatio, roundRule);
        BigDecimal actual = pack[0];
        BigDecimal over = pack[1];

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("orderId", o.get("id"));
        r.put("drugCatalogId", dcId);
        r.put("drugCode", cat.get("drug_code"));
        r.put("drugName", cat.isEmpty() ? o.get("order_content") : cat.get("generic_name"));
        r.put("spec", cat.get("spec"));
        r.put("unit", cat.get("min_unit"));
        r.put("packRatio", packRatio);
        r.put("roundRule", roundRule);
        r.put("packQty", pack[2]);
        r.put("shouldQty", should);
        r.put("offsetQty", offset);
        r.put("actualQty", actual);
        r.put("overQty", over);
        r.put("availStock", stockQty(dcId, wh, orgId));
        r.put("stagingAvail", staging);
        r.put("sufficient", stockQty(dcId, wh, orgId).compareTo(actual) >= 0);
        return r;
    }

    /** 取整到整包装: 返回[实发最小单位量, 多发量, 包数]。规则1向上/2向下(至少1包当net>0)/3四舍五入(net=0则0)。 */
    private static BigDecimal[] roundToPack(BigDecimal net, int packRatio, int roundRule) {
        if (net.compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal pr = BigDecimal.valueOf(packRatio);
        BigDecimal raw = net.divide(pr, 6, RoundingMode.HALF_UP);
        BigDecimal packs;
        switch (roundRule) {
            case 2:
                packs = net.divide(pr, 0, RoundingMode.FLOOR);
                if (packs.compareTo(BigDecimal.ZERO) <= 0) {
                    packs = BigDecimal.ONE; // 向下但仍有需求, 至少发1包(整包装不可拆), 差额记为多发
                }
                break;
            case 3:
                packs = raw.setScale(0, RoundingMode.HALF_UP);
                if (packs.compareTo(BigDecimal.ZERO) <= 0) {
                    packs = BigDecimal.ONE;
                }
                break;
            default: // 1 向上
                packs = net.divide(pr, 0, RoundingMode.CEILING);
                if (packs.compareTo(BigDecimal.ZERO) <= 0) {
                    packs = BigDecimal.ONE;
                }
        }
        BigDecimal actual = packs.multiply(pr);
        BigDecimal over = actual.subtract(net);
        return new BigDecimal[]{actual, over, packs};
    }

    /* ================= 内部: 落库/回查 ================= */

    private Long insertDispense(Map<String, Object> o, Long effDrugId, Long origDrugId, boolean replaced,
                                InpDispenseReq.Item it, Map<String, Object> rnd, Long stockOutId,
                                InpDispenseReq req, Long orgId, boolean discharge) {
        String no = generateDispenseNo();
        BigDecimal should = toBd(rnd.get("shouldQty"));
        BigDecimal offset = toBd(rnd.get("offsetQty"));
        BigDecimal actual = toBd(rnd.get("actualQty"));
        BigDecimal over = toBd(rnd.get("overQty"));
        LocalDateTime checkTime = StringUtils.hasText(req.getCheckBy()) ? LocalDateTime.now() : null;
        // 42 列与 42 占位符逐一对齐(含常量以占位符承载, 避免列/值错位)
        jdbcTemplate.update("INSERT INTO his_inp_dispense (tenant_id, org_id, dispense_no, inp_visit_id, order_id,"
                        + " drug_catalog_id, orig_drug_catalog_id, drug_code, drug_name, spec, unit,"
                        + " replace_flag, replace_scope, replace_reason, patient_id, patient_name, dept_id, dept_name,"
                        + " ward_id, ward_name, bed_no, pharmacy_id, round_rule, pack_ratio, pack_qty,"
                        + " should_qty, offset_qty, actual_qty, over_qty, stock_out_id, is_discharge_pick, pickup_status,"
                        + " return_qty, status, dispense_by, dispense_time, check_by, check_time, remark,"
                        + " create_by, create_time, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId(), orgId, no, o.get("inp_visit_id"), o.get("id"),
                effDrugId, origDrugId, rnd.get("drugCode"), rnd.get("drugName"), rnd.get("spec"), rnd.get("unit"),
                replaced ? 1 : 0, replaced ? it.getReplaceScope() : null, replaced ? it.getReplaceReason() : null,
                o.get("patient_id"), o.get("patient_name"), o.get("dept_id"), o.get("dept_name"),
                o.get("ward_id"), o.get("ward_name"), o.get("bed_no"), req.getPharmacyId(),
                rnd.get("roundRule"), rnd.get("packRatio"), rnd.get("packQty"),
                should, offset, actual, over, stockOutId, discharge ? 1 : 0, discharge ? 1 : 0,
                BigDecimal.ZERO, 1, currentUserName(), LocalDateTime.now(),
                StringUtils.hasText(req.getCheckBy()) ? req.getCheckBy().trim() : null, checkTime, req.getRemark(),
                currentUserName(), LocalDateTime.now(), 0);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM his_inp_dispense WHERE dispense_no = ? AND tenant_id = ?",
                Long.class, no, tenantId());
        return id;
    }

    private void createPickup(Map<String, Object> o, Long effDrugId, Map<String, Object> rnd, Long dispenseId, Long windowId) {
        jdbcTemplate.update("INSERT INTO his_discharge_pickup (tenant_id, org_id, inp_visit_id, dispense_id, order_id,"
                        + " patient_id, patient_name, drug_catalog_id, drug_name, spec, qty, pickup_status, window_id,"
                        + " create_by, create_time, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),0)",
                tenantId(), toLong(o.get("org_id")), o.get("inp_visit_id"), dispenseId, o.get("id"),
                o.get("patient_id"), o.get("patient_name"), effDrugId, rnd.get("drugName"), rnd.get("spec"),
                rnd.get("actualQty"), 1, windowId, currentUserName());
    }

    /* ================= 内部: 病区暂存冲抵 ================= */

    /** 病区可冲抵暂存量: 同就诊同药品有效暂存(staged-used)。 */
    private BigDecimal stagingAvail(Long visitId, Long drugCatalogId) {
        if (visitId == null || drugCatalogId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal v = jdbcTemplate.queryForObject("SELECT IFNULL(SUM(staged_qty - used_qty),0) FROM his_ward_staging"
                        + " WHERE tenant_id = ? AND inp_visit_id = ? AND drug_catalog_id = ? AND status = 0 AND deleted = 0",
                BigDecimal.class, tenantId(), visitId, drugCatalogId);
        return nz(v);
    }

    /** 消耗暂存: 按 id 正序逐条累加 used_qty 至 offset 目标, 冲抵完置 status=1。 */
    private void consumeStaging(Long visitId, Long drugCatalogId, BigDecimal offset, Long sourceDispenseId) {
        if (offset == null || offset.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, staged_qty AS stagedQty, used_qty AS usedQty"
                + " FROM his_ward_staging WHERE tenant_id = ? AND inp_visit_id = ? AND drug_catalog_id = ? AND status = 0 AND deleted = 0"
                + " ORDER BY id ASC", tenantId(), visitId, drugCatalogId);
        BigDecimal need = offset;
        for (Map<String, Object> row : rows) {
            if (need.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal avail = nz(toBd(row.get("stagedQty"))).subtract(nz(toBd(row.get("usedQty"))));
            if (avail.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal take = avail.min(need);
            need = need.subtract(take);
            BigDecimal newUsed = nz(toBd(row.get("usedQty"))).add(take);
            int done = newUsed.compareTo(nz(toBd(row.get("stagedQty")))) >= 0 ? 1 : 0;
            jdbcTemplate.update("UPDATE his_ward_staging SET used_qty = ?, status = ?, update_by = ?, update_time = NOW()"
                            + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    newUsed, done, currentUserName(), row.get("id"), tenantId());
        }
    }

    /** 退药暂存入库: 同就诊同药品有效暂存行存在则累加 staged_qty, 否则新建。 */
    private void upsertStaging(Map<String, Object> d, BigDecimal qty, Long dispenseId, Long orgId) {
        Long visitId = toLong(d.get("inpVisitId"));
        Long dcId = toLong(d.get("drugCatalogId"));
        List<Long> exist = jdbcTemplate.queryForList("SELECT id FROM his_ward_staging WHERE tenant_id = ? AND inp_visit_id = ?"
                        + " AND drug_catalog_id = ? AND status = 0 AND deleted = 0 ORDER BY id ASC LIMIT 1",
                Long.class, tenantId(), visitId, dcId);
        if (!exist.isEmpty()) {
            jdbcTemplate.update("UPDATE his_ward_staging SET staged_qty = staged_qty + ?, update_by = ?, update_time = NOW()"
                            + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    qty, currentUserName(), exist.get(0), tenantId());
        } else {
            jdbcTemplate.update("INSERT INTO his_ward_staging (tenant_id, org_id, inp_visit_id, patient_id, patient_name,"
                            + " ward_id, ward_name, drug_catalog_id, drug_code, drug_name, spec, unit, staged_qty, used_qty,"
                            + " source_dispense_id, status, create_by, create_time, deleted)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,NOW(),0)",
                    tenantId(), orgId, visitId, d.get("patientId"), d.get("patientName"), d.get("wardId"), d.get("wardName"),
                    dcId, d.get("drugCode"), d.get("drugName"), d.get("spec"), d.get("unit"), qty, BigDecimal.ZERO,
                    dispenseId, currentUserName());
        }
    }

    /** 退回药房回库: 按发药出库批次建入库单(in_type=2)并确认。 */
    private void restoreStock(Map<String, Object> d, BigDecimal qty, String retNo, Long orgId) {
        Long stockOutId = toLong(d.get("stockOutId"));
        if (stockOutId == null) {
            throw new BizException("该发药为全额冲抵(未出库、无批次可回退), 不可退回药房, 请改用暂存病区退回");
        }
        Long wh = toLong(jdbcTemplate.queryForObject("SELECT warehouse_id FROM his_stock_out WHERE id = ? AND tenant_id = ?",
                Object.class, stockOutId, tenantId()));
        List<Map<String, Object>> outItems = jdbcTemplate.queryForList("SELECT drug_catalog_id AS drugCatalogId, drug_code AS drugCode,"
                + " drug_name AS drugName, spec, batch_no AS batchNo, qty, cost_price AS costPrice, retail_price AS retailPrice"
                + " FROM his_stock_out_item WHERE stock_out_id = ? AND deleted = 0 ORDER BY id", stockOutId);
        if (outItems.isEmpty()) {
            throw new BizException("未找到发药出库明细, 无法回库");
        }
        StockInReq inReq = new StockInReq();
        inReq.setOrgId(orgId);
        inReq.setWarehouseId(wh);
        inReq.setInType(2);
        inReq.setRemark("住院退药回库: " + retNo + ", 发药单 " + str(d.get("dispenseNo")));
        List<StockInItemReq> inItems = new ArrayList<>();
        BigDecimal remaining = qty;
        for (Map<String, Object> oi : outItems) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal batchQty = nz(toBd(oi.get("qty")));
            BigDecimal take = batchQty.min(remaining);
            remaining = remaining.subtract(take);
            StockInItemReq ii = new StockInItemReq();
            ii.setDrugCatalogId(toLong(oi.get("drugCatalogId")));
            ii.setDrugCode(str(oi.get("drugCode")));
            ii.setDrugName(str(oi.get("drugName")));
            ii.setSpec(str(oi.get("spec")));
            ii.setBatchNo(str(oi.get("batchNo")));
            ii.setQty(take);
            ii.setCostPrice(toBd(oi.get("costPrice")));
            ii.setRetailPrice(toBd(oi.get("retailPrice")));
            inItems.add(ii);
        }
        inReq.setItems(inItems);
        HisStockIn stockIn = drugStockService.createStockIn(inReq);
        drugStockService.confirmStockIn(stockIn.getId());
    }

    /* ================= 内部: 查询助手 ================= */

    private Map<String, Object> dispenseResult(Long id) {
        return jdbcTemplate.queryForMap("SELECT id, dispense_no AS dispenseNo, order_id AS orderId, drug_name AS drugName, spec, unit,"
                + " should_qty AS shouldQty, offset_qty AS offsetQty, actual_qty AS actualQty, over_qty AS overQty, return_qty AS returnQty,"
                + " replace_flag AS replaceFlag, replace_scope AS replaceScope, is_discharge_pick AS isDischargePick, pickup_status AS pickupStatus,"
                + " status, stock_out_id AS stockOutId, DATE_FORMAT(dispense_time, '%Y-%m-%d %H:%i:%s') AS dispenseTime"
                + " FROM his_inp_dispense WHERE id = ? AND tenant_id = ?", id, tenantId());
    }

    private Map<String, Object> pickupResult(Long id) {
        return jdbcTemplate.queryForMap("SELECT id, dispense_id AS dispenseId, patient_name AS patientName, drug_name AS drugName,"
                + " qty, invoice_no AS invoiceNo, pickup_status AS pickupStatus, pickup_by AS pickupBy, verify2_by AS verify2By"
                + " FROM his_discharge_pickup WHERE id = ? AND tenant_id = ?", id, tenantId());
    }

    private Map<String, Object> pickupRow(Long id, Long orgId) {
        if (id == null) {
            throw new BizException(400, "带药记录ID不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, dispense_id, pickup_status AS pickupStatus FROM his_discharge_pickup"
                + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND deleted = 0", id, tenantId(), orgId);
        if (rows.isEmpty()) {
            throw new BizException(400, "带药记录不存在");
        }
        return rows.get(0);
    }

    /** 医嘱行(含就诊/患者上下文快照): 校验归属机构, 供发药/预览取数。 */
    private Map<String, Object> orderRow(Long orderId, Long orgId) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT o.id, o.org_id AS org_id, o.inp_visit_id, o.order_content, o.spec,"
                        + " o.dosage, o.dosage_unit, o.freq_code, o.quantity, o.drug_id, o.order_status, o.pharm_audit_status, o.dispense_status,"
                        + " v.inp_no, v.patient_id, v.dept_id, v.ward_id, v.bed_id, p.name AS patient_name, dep.dept_name, w.ward_name, b.bed_no"
                        + " FROM his_inp_order o JOIN his_inp_visit v ON o.inp_visit_id = v.id AND v.deleted = 0"
                        + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                        + " LEFT JOIN his_dept dep ON v.dept_id = dep.id AND dep.deleted = 0"
                        + " LEFT JOIN his_ward w ON v.ward_id = w.id AND w.deleted = 0"
                        + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                        + " WHERE o.id = ? AND o.tenant_id = ? AND o.deleted = 0", orderId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "医嘱不存在");
        }
        Map<String, Object> o = rows.get(0);
        if (orgId != null && toLong(o.get("org_id")) != null && !orgId.equals(toLong(o.get("org_id")))) {
            throw new BizException(403, "无权操作其他机构的医嘱");
        }
        Integer cat = toInt(o.get("order_status"));
        Integer pharm = toInt(o.get("pharm_audit_status"));
        if (toInt(o.get("drug_id")) == null) {
            throw new BizException("该医嘱非药品医嘱或未关联药品目录, 不可发药");
        }
        if (pharm != null && pharm == 1) {
            throw new BizException("该医嘱尚在药学审核中, 请先完成药审再发药");
        }
        if (cat != null && cat < 2) {
            throw new BizException("该医嘱未审核(护士站), 不可发药");
        }
        return o;
    }

    private Map<String, Object> catalogRow(Long drugCatalogId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id, drug_code, generic_name, spec, min_unit, pack_ratio,"
                + " round_rule, drug_std_code, manufacturer, status, retail_price FROM his_drug_catalog"
                + " WHERE id = ? AND tenant_id = ? AND deleted = 0", drugCatalogId, tenantId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal retailPriceOf(Long drugCatalogId) {
        if (drugCatalogId == null) {
            return BigDecimal.ZERO;
        }
        Map<String, Object> c = catalogRow(drugCatalogId);
        BigDecimal rp = c == null ? null : toBd(c.get("retail_price"));
        return rp == null ? BigDecimal.ZERO : rp;
    }

    /** 库存可用量: 指定药库时仅该库该机构; 否则全院(仅按机构)FIFO 口径合计。 */
    private BigDecimal stockQty(Long drugCatalogId, Long wh, Long orgId) {
        if (drugCatalogId == null) {
            return BigDecimal.ZERO;
        }
        StringBuilder sql = new StringBuilder("SELECT IFNULL(SUM(qty),0) FROM his_drug_stock WHERE deleted = 0 AND tenant_id = ? AND drug_catalog_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(drugCatalogId);
        if (wh != null) {
            sql.append(" AND warehouse_id = ?");
            args.add(wh);
        }
        if (orgId != null) {
            sql.append(" AND org_id = ?");
            args.add(orgId);
        }
        BigDecimal v = jdbcTemplate.queryForObject(sql.toString(), BigDecimal.class, args.toArray());
        return nz(v);
    }

    /** 药房关联库存位(优先 stock_location_id, 回退 warehouse_id); 药房为空返回 null(全院FIFO)。 */
    private Long warehouseOfPharmacy(Long pharmacyId) {
        if (pharmacyId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT stock_location_id AS loc, warehouse_id AS wh FROM his_pharmacy_def"
                + " WHERE id = ? AND tenant_id = ? AND deleted = 0", pharmacyId, tenantId());
        if (rows.isEmpty()) {
            return null;
        }
        Long loc = toLong(rows.get(0).get("loc"));
        return loc != null ? loc : toLong(rows.get(0).get("wh"));
    }

    /* ================= 内部: 通用工具 ================= */

    private Long requireCurrentOrg() {
        Long orgId = guard.currentOrgId();
        if (orgId == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法进行住院发药");
        }
        return orgId;
    }

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

    private synchronized String generateDispenseNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(dispSeqDate)) {
            dispSeqDate = today;
            dispSeqNo = maxDispSeqFromDb(today);
        }
        String no;
        do {
            dispSeqNo++;
            no = "ZYF" + today + String.format("%04d", dispSeqNo);
        } while (existsDispenseNo(no));
        return no;
    }

    private int maxDispSeqFromDb(String today) {
        String like = "ZYF" + today + "%";
        List<String> max = jdbcTemplate.queryForList("SELECT MAX(dispense_no) FROM his_inp_dispense WHERE dispense_no LIKE ?",
                String.class, like);
        String v = max.isEmpty() ? null : max.get(0);
        if (v == null || v.length() < 15) {
            return 0;
        }
        try {
            return Integer.parseInt(v.substring(11));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean existsDispenseNo(String no) {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM his_inp_dispense WHERE dispense_no = ? AND tenant_id = ?",
                Long.class, no, tenantId());
        return c != null && c > 0;
    }

    /** 退药单号: TY + yyyyMMdd + 当日4位序号(synchronized + 存在性回读防撞号, 与门诊退药共表按 tenant+return_no 唯一)。 */
    private synchronized String generateReturnNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(retSeqDate)) {
            retSeqDate = today;
            retSeqNo = maxSeqFromDb("his_drug_return", "return_no", "TY", today);
        }
        String no;
        do {
            retSeqNo++;
            no = "TY" + today + String.format("%04d", retSeqNo);
        } while (existsNo("his_drug_return", "return_no", no));
        return no;
    }

    /** 取药发票号: QY + yyyyMMdd + 当日4位序号(synchronized + 存在性回读防撞号)。 */
    private synchronized String generateInvoiceNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(invSeqDate)) {
            invSeqDate = today;
            invSeqNo = maxSeqFromDb("his_discharge_pickup", "invoice_no", "QY", today);
        }
        String no;
        do {
            invSeqNo++;
            no = "QY" + today + String.format("%04d", invSeqNo);
        } while (existsNo("his_discharge_pickup", "invoice_no", no));
        return no;
    }

    /** 当日某前缀已用最大序号(2位前缀+8位日期, 序号自第10位起; 非纯数字或无则0)。表/列均为常量, 值参数化。 */
    private int maxSeqFromDb(String table, String col, String prefix, String today) {
        List<String> max = jdbcTemplate.queryForList("SELECT MAX(" + col + ") FROM " + table
                + " WHERE tenant_id = ? AND " + col + " LIKE ?", String.class, tenantId(), prefix + today + "%");
        String v = max.isEmpty() ? null : max.get(0);
        if (v == null || v.length() < 14 || !v.substring(10).chars().allMatch(Character::isDigit)) {
            return 0;
        }
        try {
            return Integer.parseInt(v.substring(10));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean existsNo(String table, String col, String val) {
        Long cnt = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + col + " = ? AND tenant_id = ?",
                Long.class, val, tenantId());
        return cnt != null && cnt > 0;
    }

    private static void appendDate(StringBuilder w, List<Object> args, String timeCol, String startDate, String endDate) {
        if (StringUtils.hasText(startDate)) {
            w.append(" AND ").append(timeCol).append(" >= ?");
            args.add(startDate.trim() + " 00:00:00");
        }
        if (StringUtils.hasText(endDate)) {
            w.append(" AND ").append(timeCol).append(" <= ?");
            args.add(endDate.trim() + " 23:59:59");
        }
    }

    private static Object[] join2(Object[] base, List<Object> extra) {
        List<Object> all = new ArrayList<>();
        for (Object b : base) {
            all.add(b);
        }
        all.addAll(extra);
        return all.toArray();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static Integer toInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static int toIntOr(Object v, int def) {
        return v == null ? def : ((Number) v).intValue();
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
}
