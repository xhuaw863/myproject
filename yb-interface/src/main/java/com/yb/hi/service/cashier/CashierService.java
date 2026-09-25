package com.yb.hi.service.cashier;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.common.YbResponse;
import com.yb.hi.config.TenantYbConfigResolver;
import com.yb.hi.config.YbRuntimeConfig;
import com.yb.hi.dto.SetlCancelReq;
import com.yb.hi.dto.SettlementReq;
import com.yb.hi.dto.cashier.ChargeReq;
import com.yb.hi.dto.cashier.RefundReq;
import com.yb.hi.entity.cashier.HisChargeBill;
import com.yb.hi.entity.cashier.HisChargeBillItem;
import com.yb.hi.entity.cashier.HisDailySettle;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.cashier.HisChargeBillItemMapper;
import com.yb.hi.mapper.cashier.HisChargeBillMapper;
import com.yb.hi.mapper.cashier.HisDailySettleMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.OutpatientService;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 收费结算服务(收费员工作台): 待收费列表 / 费用明细 / 医保与自费收费 / 退费 / 收费记录 / 门诊日结。
 * 说明:
 * 1) 待收费与费用明细跨表汇总(就诊+挂号+患者+处方明细+检查单明细)统一走 JdbcTemplate 手动租户过滤
 *    (MyBatis-Plus 租户插件仅作用于 Mapper 语句, 原生 SQL 需显式 tenant_id);
 * 2) 医保收费编排现有 OutpatientService: 2206 预结算 -> 2207 结算(取 setl_id), Mock 模式下自动由本地模拟返回;
 *    Mock 金额拆分: 基金70% / 个账10% / 自付20%(余数归自付, 保证三分守恒);
 * 3) his_visit.charge_status(0未收/1已收/2已退) 通过原生 UPDATE 维护, 与就诊实体解耦。
 */
@Slf4j
@Service
public class CashierService {

    /** 单号序号(同日单号后4位, 与唯一键 tenant+bill_no 联合防重) */
    private static final AtomicInteger SEQ = new AtomicInteger(0);
    /** 内存序号当前对应日期(空=尚未恢复; 服务重启后首次生成单号时回读 DB 当天最大序号, 防回绕冲突) */
    private static volatile String SEQ_DAY = "";

    private static final DateTimeFormatter BILL_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HisChargeBillMapper billMapper;
    private final HisChargeBillItemMapper billItemMapper;
    private final HisDailySettleMapper dailySettleMapper;
    private final JdbcTemplate jdbcTemplate;
    private final OutpatientService outpatientService;
    private final TenantYbConfigResolver ybConfigResolver;
    private final OrgAccessGuard orgAccessGuard;

    public CashierService(HisChargeBillMapper billMapper, HisChargeBillItemMapper billItemMapper,
                          HisDailySettleMapper dailySettleMapper, JdbcTemplate jdbcTemplate,
                          OutpatientService outpatientService, TenantYbConfigResolver ybConfigResolver,
                          OrgAccessGuard orgAccessGuard) {
        this.billMapper = billMapper;
        this.billItemMapper = billItemMapper;
        this.dailySettleMapper = dailySettleMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.outpatientService = outpatientService;
        this.ybConfigResolver = ybConfigResolver;
        this.orgAccessGuard = orgAccessGuard;
    }

    // ==================== 待收费 ====================

    /**
     * 待收费列表: 已完成接诊(visit_status=3)且未收费(charge_status=0)的就诊,
     * 含处方/检查单明细金额实时汇总; keyword 匹配患者姓名/挂号单号/院内就诊号。
     */
    public IPage<Map<String, Object>> todoPage(Long orgId, String keyword, long page, long size) {
        long p = safePage(page);
        long s = safeSize(size);
        StringBuilder where = new StringBuilder(" WHERE v.visit_status = 3 AND v.charge_status = 0 AND v.deleted = 0 AND v.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (orgId != null) {
            where.append(" AND d.org_id = ?");
            args.add(orgId);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (v.patient_name LIKE ? OR v.reg_no LIKE ? OR v.patient_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        String joins = " FROM his_visit v"
                + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());

        String dataSql = "SELECT v.id AS visit_id, v.ipt_otp_no AS visit_no, v.reg_no, v.patient_no,"
                + " v.patient_name, p.id_card, v.gender, v.age, v.insutype,"
                + " v.dept_name, v.dr_name AS doctor_name,"
                + " DATE_FORMAT(v.work_date, '%Y-%m-%d') AS work_date,"
                + " DATE_FORMAT(v.visit_time, '%Y-%m-%d %H:%i:%s') AS visit_time,"
                + " DATE_FORMAT(v.finish_time, '%Y-%m-%d %H:%i:%s') AS finish_time,"
                + " IFNULL((SELECT SUM(pi.amount) FROM his_prescription_item pi"
                + "         JOIN his_prescription pr ON pr.id = pi.prescription_id AND pr.deleted = 0"
                + "         WHERE pr.visit_id = v.id AND pi.deleted = 0), 0)"
                + " + IFNULL((SELECT SUM(oi.amount) FROM his_order_item oi"
                + "           JOIN his_order o ON o.id = oi.order_id AND o.deleted = 0"
                + "           WHERE o.visit_id = v.id AND oi.deleted = 0), 0) AS total_amount"
                + joins + where + " ORDER BY v.finish_time DESC, v.id DESC LIMIT ?, ?";
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
     * 费用明细: 汇总某次就诊的处方明细(药品, itemType=1) + 检查单明细(检查/检验=2, 治疗=3),
     * 医保编码优先取明细自身 med_list_codg, 缺失时按 drug_id/item_id 关联目录兜底;
     * 医保名称实时回查标准字典(std_drug.reg_name / std_med_service.loc_item_name)。
     */
    public Map<String, Object> billDetail(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        List<Map<String, Object>> visitRows = jdbcTemplate.queryForList(
                "SELECT v.id, v.reg_no, v.ipt_otp_no, v.patient_no, v.mdtrt_id, v.insutype,"
                        + " v.dept_name, v.dr_name, v.patient_name, v.gender, v.age, p.id_card"
                        + " FROM his_visit v LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " WHERE v.id = ? AND v.deleted = 0", visitId);
        if (visitRows.isEmpty()) {
            throw new BizException(400, "就诊记录不存在");
        }
        Map<String, Object> v = visitRows.get(0);
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("name", v.get("patient_name"));
        patient.put("gender", v.get("gender"));
        patient.put("age", v.get("age"));
        patient.put("idCard", v.get("id_card"));
        patient.put("insuType", v.get("insutype"));
        patient.put("patientNo", v.get("patient_no"));
        patient.put("regNo", v.get("reg_no"));
        patient.put("visitNo", v.get("ipt_otp_no"));
        patient.put("mdtrtId", v.get("mdtrt_id"));
        patient.put("deptName", v.get("dept_name"));
        patient.put("drName", v.get("dr_name"));

        List<Map<String, Object>> items = new ArrayList<>();
        // 药品明细(处方)
        List<Map<String, Object>> drugRows = jdbcTemplate.queryForList(
                "SELECT pi.id AS ref_id, pi.item_code, pi.item_name, pi.spec,"
                        + " pi.quantity AS qty, pi.price, pi.amount,"
                        + " COALESCE(pi.med_list_codg, dc.yb_drug_code) AS med_list_codg,"
                        + " COALESCE((SELECT sd.reg_name FROM std_drug sd"
                        + "           WHERE sd.drug_code = COALESCE(pi.med_list_codg, dc.yb_drug_code) LIMIT 1),"
                        + "          dc.generic_name) AS med_list_name,"
                        + " IFNULL(dc.selfpay_prop, 0) AS ratio"
                        + " FROM his_prescription_item pi"
                        + " JOIN his_prescription pr ON pr.id = pi.prescription_id"
                        + " LEFT JOIN his_drug_catalog dc ON dc.id = pi.drug_id"
                        + " WHERE pr.visit_id = ? AND pi.deleted = 0 AND pr.deleted = 0"
                        + " ORDER BY pi.id", visitId);
        for (Map<String, Object> r : drugRows) {
            items.add(buildItem(1, "prescription_item", r));
        }
        // 检查/治疗明细(医嘱单)
        List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                "SELECT oi.id AS ref_id, oi.item_code, oi.item_name, oi.spec,"
                        + " oi.quantity AS qty, oi.price, oi.amount,"
                        + " COALESCE(oi.med_list_codg, ci.med_list_codg) AS med_list_codg,"
                        + " (SELECT sms.loc_item_name FROM std_med_service sms"
                        + "  WHERE sms.nat_item_code = COALESCE(oi.med_list_codg, ci.med_list_codg) LIMIT 1) AS med_list_name,"
                        + " IFNULL(ci.selfpay_prop, 0) AS ratio, o.order_type"
                        + " FROM his_order_item oi"
                        + " JOIN his_order o ON o.id = oi.order_id"
                        + " LEFT JOIN his_charge_item ci ON ci.id = oi.item_id"
                        + " WHERE o.visit_id = ? AND oi.deleted = 0 AND o.deleted = 0"
                        + " ORDER BY oi.id", visitId);
        for (Map<String, Object> r : orderRows) {
            items.add(buildItem(orderItemType(str(r.get("order_type"))), "order_item", r));
        }

        // 汇总
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal drugAmount = BigDecimal.ZERO;
        BigDecimal examAmount = BigDecimal.ZERO;
        BigDecimal treatAmount = BigDecimal.ZERO;
        BigDecimal materialAmount = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            BigDecimal amt = toBd(it.get("amount"));
            if (amt == null) {
                amt = BigDecimal.ZERO;
            }
            Integer t = (Integer) it.get("itemType");
            if (t == null) {
                t = 3;
            }
            switch (t) {
                case 1: drugAmount = drugAmount.add(amt); break;
                case 2: examAmount = examAmount.add(amt); break;
                case 3: treatAmount = treatAmount.add(amt); break;
                default: materialAmount = materialAmount.add(amt);
            }
            totalAmount = totalAmount.add(amt);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAmount", totalAmount);
        summary.put("drugAmount", drugAmount);
        summary.put("examAmount", examAmount);
        summary.put("treatAmount", treatAmount);
        summary.put("materialAmount", materialAmount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visitId", visitId);
        result.put("patient", patient);
        result.put("items", items);
        result.put("summary", summary);
        return result;
    }

    /** 组装统一明细行(itemType/refType 固定, 其余取 SQL 别名列) */
    private Map<String, Object> buildItem(int itemType, String refType, Map<String, Object> r) {
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("itemType", itemType);
        it.put("refType", refType);
        it.put("refId", toLong(r.get("ref_id")));
        it.put("itemCode", r.get("item_code"));
        it.put("itemName", r.get("item_name"));
        it.put("spec", r.get("spec"));
        it.put("qty", toBd(r.get("qty")));
        it.put("price", toBd(r.get("price")));
        it.put("amount", toBd(r.get("amount")));
        it.put("medListCodg", r.get("med_list_codg"));
        it.put("medListName", r.get("med_list_name"));
        it.put("ratio", toBd(r.get("ratio")));
        return it;
    }

    /** 医嘱单类型 -> 收费明细类型: 检查/检验=2, 治疗/其余=3 */
    private static int orderItemType(String orderType) {
        if ("检查".equals(orderType) || "检验".equals(orderType)) {
            return 2;
        }
        return 3;
    }

    // ==================== 收费 ====================

    /** 医保收费(2206预结算+2207结算, Mock模式自动模拟返回) */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> charge(ChargeReq req) {
        boolean ybFlow = req == null || !"self".equalsIgnoreCase(req.getPayType());
        return doCharge(req, ybFlow);
    }

    /** 自费收费(不走医保, 全额自付现金) */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> selfPayCharge(ChargeReq req) {
        return doCharge(req, false);
    }

    /** 收费主流程: 校验就诊 -> 建单/落明细 -> 金额拆分(医保结算或全额自付) -> 回写状态 */
    private Map<String, Object> doCharge(ChargeReq req, boolean ybFlow) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        long tenantId = tenantId();
        // 1. 校验就诊: 已完成接诊且未收费
        List<Map<String, Object>> visitRows = jdbcTemplate.queryForList(
                "SELECT id, registration_id, patient_id, patient_name, mdtrt_id, psn_no, insutype,"
                        + " visit_status, charge_status"
                        + " FROM his_visit WHERE id = ? AND tenant_id = ? AND deleted = 0",
                req.getVisitId(), tenantId);
        if (visitRows.isEmpty()) {
            throw new BizException(400, "就诊记录不存在");
        }
        Map<String, Object> visit = visitRows.get(0);
        Integer visitStatus = toInt(visit.get("visit_status"));
        Integer chargeStatus = toInt(visit.get("charge_status"));
        if (visitStatus == null || visitStatus != 3) {
            throw new BizException("该就诊未完成接诊, 不能收费");
        }
        if (chargeStatus != null && chargeStatus == 1) {
            throw new BizException("该就诊已收费, 请勿重复收费");
        }
        if (chargeStatus != null && chargeStatus == 2) {
            throw new BizException("该就诊已退费, 不能再次收费");
        }

        // 2. 汇总费用明细
        Map<String, Object> detail = billDetail(req.getVisitId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        if (CollectionUtils.isEmpty(items)) {
            throw new BizException("该就诊无待收费费用明细");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) detail.get("summary");
        BigDecimal total = toBd(summary.get("totalAmount"));
        if (total == null) {
            total = BigDecimal.ZERO;
        }

        // 3. 创建收费单(先待收费, 结算成功后转已收费)
        Long orgId = req.getOrgId() != null ? req.getOrgId() : currentOrgId();
        if (orgId == null) {
            throw new BizException(400, "收费机构不能为空");
        }
        HisChargeBill bill = new HisChargeBill();
        bill.setOrgId(orgId);
        bill.setBillNo(generateBillNo("SF"));
        bill.setVisitId(req.getVisitId());
        bill.setRegistrationId(toLong(visit.get("registration_id")));
        bill.setPatientId(toLong(visit.get("patient_id")));
        bill.setPatientName(str(visit.get("patient_name")));
        bill.setBillType(1);
        bill.setTotalAmount(total);
        bill.setSelfPay(BigDecimal.ZERO);
        bill.setFundPay(BigDecimal.ZERO);
        bill.setCashPay(BigDecimal.ZERO);
        bill.setAcctPay(BigDecimal.ZERO);
        bill.setStatus(0);
        billMapper.insert(bill);

        // 4. 明细落库
        for (Map<String, Object> it : items) {
            HisChargeBillItem bi = new HisChargeBillItem();
            bi.setBillId(bill.getId());
            bi.setItemType(toInt(it.get("itemType")));
            bi.setRefType(str(it.get("refType")));
            bi.setRefId(toLong(it.get("refId")));
            bi.setItemCode(str(it.get("itemCode")));
            bi.setItemName(str(it.get("itemName")));
            bi.setSpec(str(it.get("spec")));
            bi.setQty(toBd(it.get("qty")));
            bi.setPrice(toBd(it.get("price")));
            bi.setAmount(toBd(it.get("amount")));
            bi.setMedListCodg(str(it.get("medListCodg")));
            bi.setMedListName(str(it.get("medListName")));
            bi.setRatio(toBd(it.get("ratio")));
            billItemMapper.insert(bi);
        }

        // 5. 金额拆分(四分: 自付/基金/现金/个账)
        BigDecimal fundPay = BigDecimal.ZERO;
        BigDecimal acctPay = BigDecimal.ZERO;
        BigDecimal selfPay = total;
        String setlId = null;
        if (ybFlow) {
            String mdtrtId = str(visit.get("mdtrt_id"));
            String psnNo = str(visit.get("psn_no"));
            if (!StringUtils.hasText(mdtrtId) || !StringUtils.hasText(psnNo)) {
                throw new BizException("该就诊无医保就诊信息, 请使用自费收费");
            }
            SettlementReq setlReq = new SettlementReq();
            setlReq.setPsnNo(psnNo);
            setlReq.setMdtrtId(mdtrtId);
            setlReq.setInsutype(str(visit.get("insutype")));
            setlReq.setMedType("11");
            setlReq.setMedfeeSumamt(total);
            setlReq.setPsnSetlway("01");
            setlReq.setMdtrtCertType("02");
            // 5.1 预结算(2206)
            YbResponse preResp = outpatientService.preSettlement(setlReq);
            if (preResp == null || !preResp.isSuccess()) {
                throw new BizException("医保预结算失败: " + (preResp == null ? "医保无响应" : preResp.getErrMsg()));
            }
            // 5.2 正式结算(2207), 取结算ID
            YbResponse setlResp = outpatientService.settlement(setlReq);
            if (setlResp == null || !setlResp.isSuccess()) {
                throw new BizException("医保结算失败: " + (setlResp == null ? "医保无响应" : setlResp.getErrMsg()));
            }
            JSONObject setlinfo = setlResp.getOutputNode("setlinfo");
            if (setlinfo != null) {
                setlId = setlinfo.getString("setl_id");
            }
            YbRuntimeConfig cfg = ybConfigResolver.resolve();
            if (cfg.isMockEnabled()) {
                // 模拟模式: 基金70% / 个账10% / 自付20%, 余数归自付保证三分守恒
                fundPay = total.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.HALF_UP);
                acctPay = total.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
                selfPay = total.subtract(fundPay).subtract(acctPay);
            } else {
                // 真实模式: 从结算报文 setlinfo 取四分金额
                fundPay = firstNonNull(bd(setlinfo, "fund_pay_sumamt"), bd(setlinfo, "hifp_pay"));
                acctPay = firstNonNull(bd(setlinfo, "acct_pay"), bd(setlinfo, "acct_mulaid_pay"));
                selfPay = firstNonNull(bd(setlinfo, "psn_part_amt"), bd(setlinfo, "psn_cash_pay"));
                if (fundPay == null) {
                    fundPay = BigDecimal.ZERO;
                }
                if (acctPay == null) {
                    acctPay = BigDecimal.ZERO;
                }
                if (selfPay == null) {
                    selfPay = total.subtract(fundPay).subtract(acctPay);
                }
            }
            bill.setSetlId(setlId);
        }

        // 6. 更新收费单为已收费
        bill.setFundPay(fundPay);
        bill.setAcctPay(acctPay);
        bill.setSelfPay(selfPay);
        bill.setCashPay(selfPay);
        bill.setStatus(1);
        bill.setChargeBy(UserContext.username());
        bill.setChargeTime(LocalDateTime.now());
        billMapper.updateById(bill);

        // 7. 回写就诊收费状态
        jdbcTemplate.update("UPDATE his_visit SET charge_status = 1 WHERE id = ? AND tenant_id = ? AND deleted = 0",
                req.getVisitId(), tenantId);

        log.info("收费完成: billNo={}, visitId={}, total={}, fund={}, acct={}, self={}, setlId={}",
                bill.getBillNo(), req.getVisitId(), total, fundPay, acctPay, selfPay, setlId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bill", bill);
        result.put("receipt", billReceipt(bill.getId()));
        return result;
    }

    /**
     * 退费: 校验原单与日结 -> 医保退费(2208结算撤销) -> 生成退费单(bill_type=2, 明细复制)
     * -> 原单标记已退费 -> 回写就诊 charge_status=2。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisChargeBill refund(RefundReq req) {
        if (req == null || req.getBillId() == null) {
            throw new BizException(400, "收费单ID不能为空");
        }
        HisChargeBill origin = billMapper.selectById(req.getBillId());
        if (origin == null) {
            throw new BizException(400, "收费单不存在");
        }
        if (origin.getBillType() != null && origin.getBillType() == 2) {
            throw new BizException("退费单不能再次退费");
        }
        if (origin.getStatus() == null || origin.getStatus() != 1) {
            throw new BizException("该收费单当前状态不允许退费");
        }
        // 机构隔离: 非牵头机构用户仅可退本机构收费单(牵头机构可退全医共体)
        LoginUser lu = UserContext.get();
        if (lu != null && lu.getOrgId() != null && origin.getOrgId() != null
                && !origin.getOrgId().equals(lu.getOrgId()) && !orgAccessGuard.isLead()) {
            throw new BizException(403, "仅可退本机构收费单");
        }
        // 已日结不可退费
        if (origin.getChargeTime() != null && origin.getOrgId() != null) {
            Long settled = dailySettleMapper.selectCount(Wrappers.<HisDailySettle>lambdaQuery()
                    .eq(HisDailySettle::getOrgId, origin.getOrgId())
                    .eq(HisDailySettle::getSettleDate, origin.getChargeTime().toLocalDate())
                    .eq(HisDailySettle::getStatus, 1));
            if (settled != null && settled > 0) {
                throw new BizException("该笔收费已日结, 不能退费");
            }
        }

        // 医保退费: 有结算ID则撤销结算(2208)
        if (StringUtils.hasText(origin.getSetlId()) && origin.getVisitId() != null) {
            String mdtrtId = null;
            String psnNo = null;
            List<Map<String, Object>> visitRows = jdbcTemplate.queryForList(
                    "SELECT mdtrt_id, psn_no FROM his_visit WHERE id = ? AND deleted = 0", origin.getVisitId());
            if (!visitRows.isEmpty()) {
                mdtrtId = str(visitRows.get(0).get("mdtrt_id"));
                psnNo = str(visitRows.get(0).get("psn_no"));
            }
            SetlCancelReq cancelReq = new SetlCancelReq();
            cancelReq.setSetlId(origin.getSetlId());
            cancelReq.setMdtrtId(mdtrtId);
            cancelReq.setPsnNo(psnNo);
            YbResponse resp = outpatientService.cancelSettlement(cancelReq);
            if (resp == null || !resp.isSuccess()) {
                throw new BizException("医保退费失败: " + (resp == null ? "医保无响应" : resp.getErrMsg()));
            }
        }

        // 生成退费单(关联原单: 备注记录原单号与原因)
        HisChargeBill refundBill = new HisChargeBill();
        refundBill.setOrgId(origin.getOrgId());
        refundBill.setBillNo(generateBillNo("TF"));
        refundBill.setVisitId(origin.getVisitId());
        refundBill.setRegistrationId(origin.getRegistrationId());
        refundBill.setPatientId(origin.getPatientId());
        refundBill.setPatientName(origin.getPatientName());
        refundBill.setBillType(2);
        refundBill.setTotalAmount(origin.getTotalAmount());
        refundBill.setSelfPay(origin.getSelfPay());
        refundBill.setFundPay(origin.getFundPay());
        refundBill.setCashPay(origin.getCashPay());
        refundBill.setAcctPay(origin.getAcctPay());
        refundBill.setSetlId(origin.getSetlId());
        refundBill.setStatus(1);
        refundBill.setChargeBy(UserContext.username());
        refundBill.setChargeTime(LocalDateTime.now());
        refundBill.setRemark("原单:" + origin.getBillNo()
                + (StringUtils.hasText(req.getReason()) ? "; 退费原因:" + req.getReason() : ""));
        billMapper.insert(refundBill);

        // 复制原单明细, 保证退费单可单独打印收据
        List<HisChargeBillItem> originItems = billItemMapper.selectList(
                Wrappers.<HisChargeBillItem>lambdaQuery()
                        .eq(HisChargeBillItem::getBillId, origin.getId())
                        .orderByAsc(HisChargeBillItem::getId));
        for (HisChargeBillItem oi : originItems) {
            HisChargeBillItem ni = new HisChargeBillItem();
            ni.setBillId(refundBill.getId());
            ni.setItemType(oi.getItemType());
            ni.setRefType(oi.getRefType());
            ni.setRefId(oi.getRefId());
            ni.setItemCode(oi.getItemCode());
            ni.setItemName(oi.getItemName());
            ni.setSpec(oi.getSpec());
            ni.setQty(oi.getQty());
            ni.setPrice(oi.getPrice());
            ni.setAmount(oi.getAmount());
            ni.setMedListCodg(oi.getMedListCodg());
            ni.setMedListName(oi.getMedListName());
            ni.setRatio(oi.getRatio());
            billItemMapper.insert(ni);
        }

        // 原单标记已退费
        origin.setStatus(2);
        billMapper.updateById(origin);

        // 回写就诊收费状态
        if (origin.getVisitId() != null) {
            jdbcTemplate.update("UPDATE his_visit SET charge_status = 2 WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    origin.getVisitId(), tenantId());
        }

        log.info("退费完成: 原单={}, 退费单={}, visitId={}", origin.getBillNo(), refundBill.getBillNo(), origin.getVisitId());
        return refundBill;
    }

    // ==================== 收费记录 ====================

    /** 收费记录分页(机构/状态/单据类型/收费日期区间/单号或患者关键字) */
    public IPage<HisChargeBill> billPage(Long orgId, Integer status, Integer billType, String startDate, String endDate,
                                         String keyword, long page, long size) {
        LambdaQueryWrapper<HisChargeBill> w = Wrappers.<HisChargeBill>lambdaQuery()
                .eq(orgId != null, HisChargeBill::getOrgId, orgId)
                .eq(status != null, HisChargeBill::getStatus, status)
                .eq(billType != null, HisChargeBill::getBillType, billType);
        LocalDateTime from = parseStart(startDate);
        LocalDateTime to = parseEnd(endDate);
        w.ge(from != null, HisChargeBill::getChargeTime, from)
                .le(to != null, HisChargeBill::getChargeTime, to);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(q -> q.like(HisChargeBill::getBillNo, kw).or().like(HisChargeBill::getPatientName, kw));
        }
        w.orderByDesc(HisChargeBill::getId);
        return billMapper.selectPage(new Page<>(safePage(page), safeSize(size)), w);
    }

    /** 收据数据: 收费单 + 明细列表 + 患者信息 */
    public Map<String, Object> billReceipt(Long billId) {
        HisChargeBill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new BizException(400, "收费单不存在");
        }
        List<HisChargeBillItem> items = billItemMapper.selectList(
                Wrappers.<HisChargeBillItem>lambdaQuery()
                        .eq(HisChargeBillItem::getBillId, billId)
                        .orderByAsc(HisChargeBillItem::getId));
        Map<String, Object> patient = new LinkedHashMap<>();
        if (bill.getVisitId() != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT v.patient_no, v.reg_no, v.mdtrt_id, v.insutype, v.dept_name, v.dr_name,"
                            + " v.patient_name, v.gender, v.age, p.id_card"
                            + " FROM his_visit v LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                            + " WHERE v.id = ? AND v.deleted = 0", bill.getVisitId());
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                patient.put("name", r.get("patient_name"));
                patient.put("gender", r.get("gender"));
                patient.put("age", r.get("age"));
                patient.put("idCard", r.get("id_card"));
                patient.put("insuType", r.get("insutype"));
                patient.put("patientNo", r.get("patient_no"));
                patient.put("regNo", r.get("reg_no"));
                patient.put("mdtrtId", r.get("mdtrt_id"));
                patient.put("deptName", r.get("dept_name"));
                patient.put("drName", r.get("dr_name"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bill", bill);
        result.put("items", items);
        result.put("patient", patient);
        return result;
    }

    /** 收费记录导出数据(head/rows, 与列表同筛选口径, 上限5000行) */
    public Map<String, Object> exportBills(Long orgId, String startDate, String endDate) {
        LambdaQueryWrapper<HisChargeBill> w = Wrappers.<HisChargeBill>lambdaQuery()
                .eq(orgId != null, HisChargeBill::getOrgId, orgId);
        LocalDateTime from = parseStart(startDate);
        LocalDateTime to = parseEnd(endDate);
        w.ge(from != null, HisChargeBill::getChargeTime, from)
                .le(to != null, HisChargeBill::getChargeTime, to)
                .orderByDesc(HisChargeBill::getId)
                .last("LIMIT 5000");
        List<HisChargeBill> bills = billMapper.selectList(w);
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"收费单号", "类型", "患者姓名", "总金额", "自付金额", "基金支付",
                "个账支付", "现金支付", "状态", "收费时间", "收费员", "医保结算ID", "备注"}) {
            head.add(java.util.Collections.singletonList(h));
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<List<Object>> rows = new ArrayList<>();
        for (HisChargeBill b : bills) {
            List<Object> row = new ArrayList<>();
            row.add(b.getBillNo());
            row.add(b.getBillType() != null && b.getBillType() == 2 ? "门诊退费" : "门诊收费");
            row.add(b.getPatientName());
            row.add(plain(b.getTotalAmount()));
            row.add(plain(b.getSelfPay()));
            row.add(plain(b.getFundPay()));
            row.add(plain(b.getAcctPay()));
            row.add(plain(b.getCashPay()));
            row.add(statusText(b.getStatus()));
            row.add(b.getChargeTime() == null ? "" : b.getChargeTime().format(fmt));
            row.add(b.getChargeBy());
            row.add(b.getSetlId());
            row.add(b.getRemark());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("head", head);
        result.put("rows", rows);
        result.put("total", rows.size());
        return result;
    }

    // ==================== 日结 ====================

    /**
     * 执行日结(幂等: 同机构同日已日结直接返回): 汇总当日收费/退费笔数金额与
     * 现金/基金/个账净额(收费-退费), 落 his_daily_settle(status=1)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisDailySettle dailySettle(Long orgId, String date) {
        LocalDate d = parseDate(date);
        if (d == null) {
            throw new BizException(400, "日结日期不能为空或格式不正确(yyyy-MM-dd)");
        }
        Long targetOrg = orgId;
        if (targetOrg == null) {
            targetOrg = currentOrgId();
        }
        if (targetOrg == null) {
            throw new BizException(400, "机构ID不能为空");
        }
        // 幂等: 已日结直接返回
        HisDailySettle exist = dailySettleMapper.selectOne(Wrappers.<HisDailySettle>lambdaQuery()
                .eq(HisDailySettle::getOrgId, targetOrg)
                .eq(HisDailySettle::getSettleDate, d)
                .last("LIMIT 1"));
        if (exist != null) {
            return exist;
        }
        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT"
                        + " IFNULL(SUM(CASE WHEN bill_type = 1 THEN 1 ELSE 0 END), 0) AS total_count,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 1 THEN total_amount ELSE 0 END), 0) AS total_amount,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 2 THEN 1 ELSE 0 END), 0) AS refund_count,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 2 THEN total_amount ELSE 0 END), 0) AS refund_amount,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 1 THEN cash_pay ELSE -cash_pay END), 0) AS cash_total,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 1 THEN fund_pay ELSE -fund_pay END), 0) AS fund_total,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 1 THEN acct_pay ELSE -acct_pay END), 0) AS acct_total"
                        + " FROM his_charge_bill"
                        + " WHERE tenant_id = ? AND org_id = ? AND deleted = 0 AND status >= 1 AND DATE(charge_time) = ?",
                tenantId(), targetOrg, d);
        HisDailySettle settle = new HisDailySettle();
        settle.setOrgId(targetOrg);
        settle.setSettleDate(d);
        settle.setOperator(UserContext.username());
        settle.setTotalCount(toInt(agg.get("total_count")));
        settle.setTotalAmount(toBd(agg.get("total_amount")));
        settle.setRefundCount(toInt(agg.get("refund_count")));
        settle.setRefundAmount(toBd(agg.get("refund_amount")));
        settle.setCashTotal(toBd(agg.get("cash_total")));
        settle.setFundTotal(toBd(agg.get("fund_total")));
        settle.setAcctTotal(toBd(agg.get("acct_total")));
        settle.setStatus(1);
        settle.setSettleTime(LocalDateTime.now());
        dailySettleMapper.insert(settle);
        log.info("日结完成: orgId={}, date={}, 收费{}笔/{}元, 退费{}笔/{}元",
                targetOrg, d, settle.getTotalCount(), settle.getTotalAmount(),
                settle.getRefundCount(), settle.getRefundAmount());
        return settle;
    }

    /** 日结记录分页(机构/日期区间) */
    public IPage<HisDailySettle> dailySettlePage(Long orgId, String startDate, String endDate, long page, long size) {
        LambdaQueryWrapper<HisDailySettle> w = Wrappers.<HisDailySettle>lambdaQuery()
                .eq(orgId != null, HisDailySettle::getOrgId, orgId);
        LocalDate from = parseDate(startDate);
        LocalDate to = parseDate(endDate);
        w.ge(from != null, HisDailySettle::getSettleDate, from)
                .le(to != null, HisDailySettle::getSettleDate, to)
                .orderByDesc(HisDailySettle::getSettleDate)
                .orderByDesc(HisDailySettle::getId);
        return dailySettleMapper.selectPage(new Page<>(safePage(page), safeSize(size)), w);
    }

    // ==================== 单号与工具 ====================

    /** 单号生成: 前缀 + yyyyMMdd + 4位序号(SF收费单/TF退费单), 与 tenant+bill_no 唯一键联合防重。
     * 序号基值懒加载: 跨天或服务重启后首次生成时, 从库中恢复当天最大序号继续递增,
     * 避免内存计数归零后与已存在单号冲突(唯一键报 Duplicate entry)。 */
    private synchronized String generateBillNo(String prefix) {
        String day = LocalDate.now().format(BILL_DAY);
        if (!day.equals(SEQ_DAY)) {
            Integer max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(CAST(SUBSTRING(bill_no, 11) AS UNSIGNED)), 0) FROM his_charge_bill"
                            + " WHERE tenant_id = ? AND (bill_no LIKE ? OR bill_no LIKE ?)",
                    Integer.class, tenantId(), "SF" + day + "%", "TF" + day + "%");
            SEQ.set(max == null ? 0 : max);
            SEQ_DAY = day;
        }
        int s = Math.floorMod(SEQ.incrementAndGet(), 10000);
        return prefix + day + String.format("%04d", s);
    }

    private Long currentOrgId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getOrgId();
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

    private static Integer toInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
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

    private static BigDecimal bd(JSONObject o, String k) {
        return o == null ? null : o.getBigDecimal(k);
    }

    private static BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0.00" : v.toPlainString();
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case 0: return "待收费";
            case 1: return "已收费";
            case 2: return "已退费";
            default: return String.valueOf(status);
        }
    }
}
