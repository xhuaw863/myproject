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
import com.yb.hi.dto.cashier.PartialRefundReq;
import com.yb.hi.dto.cashier.PaymentItem;
import com.yb.hi.dto.cashier.RefundReq;
import com.yb.hi.entity.cashier.HisChargeBill;
import com.yb.hi.entity.cashier.HisChargeBillItem;
import com.yb.hi.entity.cashier.HisDailySettle;
import com.yb.hi.entity.cashier.HisInvoice;
import com.yb.hi.entity.cashier.HisPaymentDetail;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.cashier.HisChargeBillItemMapper;
import com.yb.hi.mapper.cashier.HisChargeBillMapper;
import com.yb.hi.mapper.cashier.HisDailySettleMapper;
import com.yb.hi.mapper.cashier.HisPaymentDetailMapper;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 自费侧支持的支付方式(INSURANCE 由医保结算自动生成明细, 不允许前端传入) */
    private static final Set<String> PAY_METHODS = new HashSet<>(Arrays.asList(
            "CASH", "WECHAT", "ALIPAY", "CARD", "FREE"));

    private static final DateTimeFormatter BILL_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HisChargeBillMapper billMapper;
    private final HisChargeBillItemMapper billItemMapper;
    private final HisDailySettleMapper dailySettleMapper;
    private final HisPaymentDetailMapper paymentDetailMapper;
    private final JdbcTemplate jdbcTemplate;
    private final OutpatientService outpatientService;
    private final TenantYbConfigResolver ybConfigResolver;
    private final OrgAccessGuard orgAccessGuard;
    private final InvoiceService invoiceService;

    public CashierService(HisChargeBillMapper billMapper, HisChargeBillItemMapper billItemMapper,
                          HisDailySettleMapper dailySettleMapper, HisPaymentDetailMapper paymentDetailMapper,
                          JdbcTemplate jdbcTemplate,
                          OutpatientService outpatientService, TenantYbConfigResolver ybConfigResolver,
                          OrgAccessGuard orgAccessGuard, InvoiceService invoiceService) {
        this.billMapper = billMapper;
        this.billItemMapper = billItemMapper;
        this.dailySettleMapper = dailySettleMapper;
        this.paymentDetailMapper = paymentDetailMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.outpatientService = outpatientService;
        this.ybConfigResolver = ybConfigResolver;
        this.orgAccessGuard = orgAccessGuard;
        this.invoiceService = invoiceService;
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
                // 作废单(status=-1)不计费: 与医生站/报表口径一致
                + " IFNULL((SELECT SUM(pi.amount) FROM his_prescription_item pi"
                + "         JOIN his_prescription pr ON pr.id = pi.prescription_id AND pr.deleted = 0 AND pr.status > 0"
                + "         WHERE pr.visit_id = v.id AND pi.deleted = 0), 0)"
                + " + IFNULL((SELECT SUM(oi.amount) FROM his_order_item oi"
                + "           JOIN his_order o ON o.id = oi.order_id AND o.deleted = 0 AND o.status > 0"
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
                        + " JOIN his_prescription pr ON pr.id = pi.prescription_id AND pr.status > 0"
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
                        + " JOIN his_order o ON o.id = oi.order_id AND o.status > 0"
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
                "SELECT id, registration_id, patient_id, patient_name, mdtrt_id, psn_no, insutype, med_type,"
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
            // 医疗类别随挂号/就诊同步(如急诊14), 未同步的老数据兕底普通门诊11
            String medType = str(visit.get("med_type"));
            setlReq.setMedType(StringUtils.hasText(medType) ? medType : "11");
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

        // 6. 多支付方式处理: payments 非空时校验金额守恒并拆分(医保单=自付部分, 自费单=全额);
        //    payments 为空(老接口)保持 cashPay=selfPay 全现金兼容行为
        BigDecimal insPay = fundPay.add(acctPay);
        List<PaymentItem> payments = normalizePayments(req.getPayments(), ybFlow ? selfPay : total);
        BigDecimal cashPay = selfPay;
        if (payments != null) {
            cashPay = BigDecimal.ZERO;
            for (PaymentItem p : payments) {
                if ("CASH".equals(p.getPayMethod())) {
                    cashPay = cashPay.add(p.getAmount());
                }
            }
        }

        // 7. 更新收费单为已收费(payMethod=金额最大的支付方式, 医保统筹+个账作为整体参与比较)
        bill.setFundPay(fundPay);
        bill.setAcctPay(acctPay);
        bill.setSelfPay(selfPay);
        bill.setCashPay(cashPay);
        bill.setPayMethod(resolveMainPayMethod(payments, ybFlow, insPay, total));
        bill.setStatus(1);
        bill.setChargeBy(UserContext.username());
        bill.setChargeTime(LocalDateTime.now());
        billMapper.updateById(bill);

        // 8. 支付明细落库: 医保部分(INSURANCE, 流水号=结算ID) + 自费各方式逐笔
        if (ybFlow && insPay.compareTo(BigDecimal.ZERO) > 0) {
            savePaymentDetail(bill.getId(), "INSURANCE", insPay, setlId);
        }
        if (payments != null) {
            for (PaymentItem p : payments) {
                savePaymentDetail(bill.getId(), p.getPayMethod(), p.getAmount(), p.getPayRef());
            }
        }

        // 9. 回写就诊收费状态
        jdbcTemplate.update("UPDATE his_visit SET charge_status = 1 WHERE id = ? AND tenant_id = ? AND deleted = 0",
                req.getVisitId(), tenantId);

        // 10. 发票联动: 自动取号开票并回写 bill.invoice_no; 号段未配置/用完仅记日志不阻塞收费
        try {
            HisInvoice invoice = invoiceService.createInvoice(bill.getId());
            bill.setInvoiceNo(invoice.getInvoiceNo());
        } catch (Exception e) {
            log.warn("发票自动分配失败(不阻塞收费): billNo={}, 原因: {}", bill.getBillNo(), e.getMessage());
        }

        log.info("收费完成: billNo={}, visitId={}, total={}, fund={}, acct={}, self={}, payMethod={}, invoiceNo={}, setlId={}",
                bill.getBillNo(), req.getVisitId(), total, fundPay, acctPay, selfPay, bill.getPayMethod(),
                bill.getInvoiceNo(), setlId);

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
        // 已发生过部分退费的原单不允许再全额退费(会重复退全款), 请继续按部分退费退剩余项
        Long partialRefunded = billItemMapper.selectCount(Wrappers.<HisChargeBillItem>lambdaQuery()
                .eq(HisChargeBillItem::getBillId, origin.getId())
                .gt(HisChargeBillItem::getRefundedQty, BigDecimal.ZERO));
        if (partialRefunded != null && partialRefunded > 0) {
            throw new BizException("该收费单已发生部分退费, 不能再全额退费, 请按部分退费退剩余项");
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

        // 生成退费单(关联原单: originBillId + 备注记录原单号与原因)
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
        refundBill.setPayMethod(origin.getPayMethod());
        refundBill.setOriginBillId(origin.getId());
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

        // 退费支付明细(金额为负): 全额退费按原单逐笔取负
        saveRefundPaymentDetails(origin, refundBill, refundBill.getTotalAmount());

        // 原单标记已退费
        origin.setStatus(2);
        billMapper.updateById(origin);

        // 发票联动: 全额退费原单已开票时自动红冲(负数冲销记录保留票据轨迹), 并清空原单发票号展示;
        // 历史发票状态异常不阻塞退费主流程(退费/医保撤销已完成), 仅记日志
        try {
            invoiceService.redFlushForBill(origin.getId(), "全额退费冲销: 原单" + origin.getBillNo());
        } catch (Exception e) {
            log.warn("退费发票红冲失败(不阻塞退费): billNo={}, 原因: {}", origin.getBillNo(), e.getMessage());
        }

        // 回写就诊收费状态
        if (origin.getVisitId() != null) {
            jdbcTemplate.update("UPDATE his_visit SET charge_status = 2 WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    origin.getVisitId(), tenantId());
        }

        log.info("退费完成: 原单={}, 退费单={}, visitId={}", origin.getBillNo(), refundBill.getBillNo(), origin.getVisitId());
        return refundBill;
    }

    /**
     * 部分退费: 按明细行退指定数量(支持多次部分退), 生成部分退费单(billType=2, originBillId 指向原单),
     * 原明细累计 refundedQty; 原单所有明细退完时原单转已退费并回写就诊 charge_status=2。
     * 医保部分退费不线上撤销结算(医保侧按比例退算复杂), remark 标记需线下处理, 仅院内退费。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisChargeBill partialRefund(PartialRefundReq req) {
        if (req == null || req.getBillId() == null) {
            throw new BizException(400, "收费单ID不能为空");
        }
        if (CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException(400, "退费明细不能为空");
        }
        // 1. 校验原单: 存在 + 已收费 + 收费单
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
        // 2. 机构隔离: 非牵头机构用户仅可退本机构收费单(牵头机构可退全医共体)
        LoginUser lu = UserContext.get();
        if (lu != null && lu.getOrgId() != null && origin.getOrgId() != null
                && !origin.getOrgId().equals(lu.getOrgId()) && !orgAccessGuard.isLead()) {
            throw new BizException(403, "仅可退本机构收费单");
        }
        // 3. 日结锁定: 原单收费日期已日结则不可退
        if (origin.getChargeTime() != null && origin.getOrgId() != null) {
            Long settled = dailySettleMapper.selectCount(Wrappers.<HisDailySettle>lambdaQuery()
                    .eq(HisDailySettle::getOrgId, origin.getOrgId())
                    .eq(HisDailySettle::getSettleDate, origin.getChargeTime().toLocalDate())
                    .eq(HisDailySettle::getStatus, 1));
            if (settled != null && settled > 0) {
                throw new BizException("该笔收费已日结, 不能退费");
            }
        }

        // 4. 校验退费明细行并计算金额(逐行: 0 < refundQty <= qty - refundedQty)
        List<HisChargeBillItem> originItems = billItemMapper.selectList(
                Wrappers.<HisChargeBillItem>lambdaQuery()
                        .eq(HisChargeBillItem::getBillId, origin.getId())
                        .orderByAsc(HisChargeBillItem::getId));
        Map<Long, HisChargeBillItem> itemMap = new LinkedHashMap<>();
        for (HisChargeBillItem it : originItems) {
            itemMap.put(it.getId(), it);
        }
        // 整单金额封顶: 已生成的各退费单合计 + 本次退费额 不得超过原单实收金额(防多次退累计超退)
        BigDecimal alreadyRefunded = BigDecimal.ZERO;
        List<HisChargeBill> historyRefunds = billMapper.selectList(Wrappers.<HisChargeBill>lambdaQuery()
                .eq(HisChargeBill::getOriginBillId, origin.getId())
                .eq(HisChargeBill::getBillType, 2));
        for (HisChargeBill h : historyRefunds) {
            alreadyRefunded = alreadyRefunded.add(nvl(h.getTotalAmount()));
        }
        List<HisChargeBillItem> refundItems = new ArrayList<>();
        Map<Long, BigDecimal> lineQtyDelta = new LinkedHashMap<>();
        BigDecimal refundTotal = BigDecimal.ZERO;
        for (PartialRefundReq.RefundItem ri : req.getItems()) {
            if (ri == null || ri.getBillItemId() == null) {
                throw new BizException(400, "退费明细ID不能为空");
            }
            HisChargeBillItem item = itemMap.get(ri.getBillItemId());
            if (item == null) {
                throw new BizException(400, "收费明细不存在或不在原单内: " + ri.getBillItemId());
            }
            if (lineQtyDelta.containsKey(item.getId())) {
                throw new BizException(400, "同一收费明细重复提交退费: " + item.getItemName());
            }
            BigDecimal refundQty = ri.getRefundQty();
            if (refundQty == null || refundQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(400, "明细[" + item.getItemName() + "]退费数量必须大于0");
            }
            BigDecimal qty = nvl(item.getQty());
            BigDecimal refunded = nvl(item.getRefundedQty());
            BigDecimal existDelta = lineQtyDelta.getOrDefault(item.getId(), BigDecimal.ZERO);
            BigDecimal remain = qty.subtract(refunded).subtract(existDelta);
            if (refundQty.compareTo(remain) > 0) {
                throw new BizException("明细[" + item.getItemName() + "]可退数量不足: 剩余可退 " + remain);
            }
            // 行退费额按原行金额比例折算(不用 price*qty 重算, 避免舍入残差导致累计退额与原单不平);
            // 本次将该行余量一次退清时, 金额=行金额-(已退数量对应份额), 保证多次部分退累计严格等于行原金额(尾差收口)
            BigDecimal lineAmount;
            if (refundQty.compareTo(remain) == 0 && qty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal refundedSoFar = nvl(item.getAmount()).multiply(refunded)
                        .divide(qty, 2, RoundingMode.HALF_UP);
                lineAmount = nvl(item.getAmount()).subtract(refundedSoFar);
            } else {
                lineAmount = nvl(item.getAmount()).multiply(refundQty)
                        .divide(qty.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : qty, 2, RoundingMode.HALF_UP);
            }
            refundTotal = refundTotal.add(lineAmount);
            lineQtyDelta.put(item.getId(), refundQty);
            // 退费明细行(只含本次退费行, 数量/金额为退费部分)
            HisChargeBillItem ni = new HisChargeBillItem();
            ni.setItemType(item.getItemType());
            ni.setRefType(item.getRefType());
            ni.setRefId(item.getRefId());
            ni.setItemCode(item.getItemCode());
            ni.setItemName(item.getItemName());
            ni.setSpec(item.getSpec());
            ni.setQty(refundQty);
            ni.setPrice(item.getPrice());
            ni.setAmount(lineAmount);
            ni.setMedListCodg(item.getMedListCodg());
            ni.setMedListName(item.getMedListName());
            ni.setRatio(item.getRatio());
            refundItems.add(ni);
        }
        if (refundTotal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("退费总金额必须大于0");
        }
        if (alreadyRefunded.add(refundTotal).compareTo(nvl(origin.getTotalAmount())) > 0) {
            throw new BizException("累计退费金额将超过原单金额(原单 " + nvl(origin.getTotalAmount())
                    + ", 已退 " + alreadyRefunded + ", 本次 " + refundTotal + ")");
        }
        // 原单累计已退平后不允许再退(金额维度冗余守卫, 与明细数量维度互相兼顾)
        if (alreadyRefunded.compareTo(nvl(origin.getTotalAmount())) >= 0) {
            throw new BizException("原单已全部退费, 不可再次退费");
        }

        // 5. 四分金额按原单比例拆分(余数归自付保证三分守恒; cashPay 为现金退回近似比例)
        BigDecimal originTotal = origin.getTotalAmount() == null ? BigDecimal.ZERO : origin.getTotalAmount();
        BigDecimal refundFund = BigDecimal.ZERO;
        BigDecimal refundAcct = BigDecimal.ZERO;
        BigDecimal refundSelf = refundTotal;
        BigDecimal refundCash = BigDecimal.ZERO;
        if (originTotal.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal of = origin.getFundPay() == null ? BigDecimal.ZERO : origin.getFundPay();
            BigDecimal oa = origin.getAcctPay() == null ? BigDecimal.ZERO : origin.getAcctPay();
            BigDecimal oc = origin.getCashPay() == null ? BigDecimal.ZERO : origin.getCashPay();
            refundFund = refundTotal.multiply(of).divide(originTotal, 2, RoundingMode.HALF_UP);
            refundAcct = refundTotal.multiply(oa).divide(originTotal, 2, RoundingMode.HALF_UP);
            refundSelf = refundTotal.subtract(refundFund).subtract(refundAcct);
            refundCash = refundTotal.multiply(oc).divide(originTotal, 2, RoundingMode.HALF_UP);
        }

        // 6. 创建部分退费单
        HisChargeBill refundBill = new HisChargeBill();
        refundBill.setOrgId(origin.getOrgId());
        refundBill.setBillNo(generateBillNo("TF"));
        refundBill.setVisitId(origin.getVisitId());
        refundBill.setRegistrationId(origin.getRegistrationId());
        refundBill.setPatientId(origin.getPatientId());
        refundBill.setPatientName(origin.getPatientName());
        refundBill.setBillType(2);
        refundBill.setTotalAmount(refundTotal);
        refundBill.setSelfPay(refundSelf);
        refundBill.setFundPay(refundFund);
        refundBill.setAcctPay(refundAcct);
        refundBill.setCashPay(refundCash);
        refundBill.setPayMethod(origin.getPayMethod());
        refundBill.setOriginBillId(origin.getId());
        refundBill.setStatus(1);
        refundBill.setChargeBy(UserContext.username());
        refundBill.setChargeTime(LocalDateTime.now());
        String remark = "部分退费; 原单:" + origin.getBillNo()
                + (StringUtils.hasText(req.getReason()) ? "; 退费原因:" + req.getReason() : "");
        if (StringUtils.hasText(origin.getSetlId())) {
            remark += "; 医保结算" + origin.getSetlId() + "未线上撤销, 需线下处理";
        }
        refundBill.setRemark(remark);
        billMapper.insert(refundBill);

        // 7. 退费明细落库(只含退费行)
        for (HisChargeBillItem ni : refundItems) {
            ni.setBillId(refundBill.getId());
            billItemMapper.insert(ni);
        }

        // 8. 退费支付明细(金额为负): 按本次退费额在原单各支付方式间占比分摊, 使明细合计严格等于退费单金额
        saveRefundPaymentDetails(origin, refundBill, refundBill.getTotalAmount());

        // 9. 原子累计原明细 refundedQty: 条件更新带余量守卫, affected=0 说明已被并发退超, 整单回滚
        for (Map.Entry<Long, BigDecimal> e : lineQtyDelta.entrySet()) {
            BigDecimal delta = e.getValue();
            int rows = billItemMapper.update(null, Wrappers.<HisChargeBillItem>lambdaUpdate()
                    .setSql("refunded_qty = IFNULL(refunded_qty, 0) + " + delta.toPlainString())
                    .eq(HisChargeBillItem::getId, e.getKey())
                    .apply("IFNULL(qty, 0) - IFNULL(refunded_qty, 0) >= {0}", delta));
            if (rows == 0) {
                throw new BizException("退费明细并发变更, 可退数量不足, 请刷新后重试");
            }
        }

        // 10. 原单全部明细全额退完 -> 原单转已退费并回写就诊状态(否则保持已收费)
        // 以回读库内实际已退数量判定, 不依赖本次入参快照(多窗口/多次部分退场景)
        List<HisChargeBillItem> latestItems = billItemMapper.selectList(
                Wrappers.<HisChargeBillItem>lambdaQuery()
                        .eq(HisChargeBillItem::getBillId, origin.getId())
                        .orderByAsc(HisChargeBillItem::getId));
        boolean allRefunded = !latestItems.isEmpty();
        for (HisChargeBillItem it : latestItems) {
            BigDecimal qty = nvl(it.getQty());
            BigDecimal ref = nvl(it.getRefundedQty());
            // 零数量行(如赠送/平价单元)不构成"待退项", 否则任何部分退费都会误判为已全部退完
            if (qty.compareTo(BigDecimal.ZERO) > 0 && qty.compareTo(ref) > 0) {
                allRefunded = false;
                break;
            }
        }
        if (allRefunded) {
            origin.setStatus(2);
            billMapper.updateById(origin);
            // 发票联动: 明细全部退完后原单发票红冲(与全额退费同口径), 不阻塞退费主流程
            try {
                invoiceService.redFlushForBill(origin.getId(), "累计退完发票冲销: 原单" + origin.getBillNo());
            } catch (Exception e) {
                log.warn("部分退费累计退完发票红冲失败(不阻塞退费): billNo={}, 原因: {}", origin.getBillNo(), e.getMessage());
            }
            if (origin.getVisitId() != null) {
                jdbcTemplate.update("UPDATE his_visit SET charge_status = 2 WHERE id = ? AND tenant_id = ? AND deleted = 0",
                        origin.getVisitId(), tenantId());
            }
        }

        log.info("部分退费完成: 原单={}, 退费单={}, 退费金额={}, 原单{}",
                origin.getBillNo(), refundBill.getBillNo(), refundTotal, allRefunded ? "已全部退完" : "仍有剩余可退项");
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

    // ==================== 多支付方式工具 ====================

    /**
     * 校验并规整支付明细: 支付方式合法(自费侧枚举, INSURANCE 不允许前端传入)、金额非负、
     * 合计必须等于应付金额(医保单=自付部分 selfPay, 自费单=全额 total)。
     * 空列表规整为 null=老接口调用, 由调用方保持全现金兼容行为。
     */
    private List<PaymentItem> normalizePayments(List<PaymentItem> payments, BigDecimal expect) {
        if (CollectionUtils.isEmpty(payments)) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (PaymentItem p : payments) {
            if (p == null || !StringUtils.hasText(p.getPayMethod())) {
                throw new BizException(400, "支付方式不能为空");
            }
            String m = p.getPayMethod().trim().toUpperCase();
            if (!PAY_METHODS.contains(m)) {
                throw new BizException(400, "不支持的支付方式: " + p.getPayMethod()
                        + "(可用: CASH/WECHAT/ALIPAY/CARD/FREE)");
            }
            if (p.getAmount() == null || p.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException(400, "支付金额不能为空或为负");
            }
            p.setPayMethod(m);
            sum = sum.add(p.getAmount());
        }
        if (sum.compareTo(expect) != 0) {
            throw new BizException("支付明细金额合计(" + sum.toPlainString() + ")必须等于应付金额("
                    + expect.toPlainString() + ")");
        }
        return payments;
    }

    /** 主要支付方式: 金额最大者(医保统筹+个账作为 INSURANCE 整体参与比较, 并列时医保优先);
     *  全零时免费单兑底 FREE, 其余兑底 CASH(老接口未传 payments 场景)。 */
    private static String resolveMainPayMethod(List<PaymentItem> payments, boolean ybFlow,
                                               BigDecimal insPay, BigDecimal total) {
        String main = null;
        BigDecimal max = BigDecimal.ZERO;
        if (ybFlow && insPay != null && insPay.compareTo(BigDecimal.ZERO) > 0) {
            main = "INSURANCE";
            max = insPay;
        }
        if (payments != null) {
            for (PaymentItem p : payments) {
                if (p.getAmount() != null && p.getAmount().compareTo(max) > 0) {
                    main = p.getPayMethod();
                    max = p.getAmount();
                }
            }
        }
        if (main == null) {
            main = total != null && total.compareTo(BigDecimal.ZERO) == 0 ? "FREE" : "CASH";
        }
        return main;
    }

    /** 落一笔支付明细 */
    private void savePaymentDetail(Long billId, String payMethod, BigDecimal amount, String payRef) {
        HisPaymentDetail d = new HisPaymentDetail();
        d.setBillId(billId);
        d.setPayMethod(payMethod);
        d.setAmount(amount);
        d.setPayRef(payRef);
        d.setPayTime(LocalDateTime.now());
        paymentDetailMapper.insert(d);
    }

    /**
     * 退费支付明细(金额为负): 将本次退费额 target 按原单各支付方式的金额占比逐笔分摊,
     * 保证退费明细合计绝对值严格等于退费单金额(全额退时 target=原单金额, 即逐笔取负)。
     * 老单无支付明细时按四分兑底(医保部分 INSURANCE + 自付部分 CASH), 同样按 target/原单总额 缩放。
     */
    private void saveRefundPaymentDetails(HisChargeBill origin, HisChargeBill refundBill, BigDecimal target) {
        BigDecimal refundAmt = target == null ? BigDecimal.ZERO : target.setScale(2, RoundingMode.HALF_UP);
        if (refundAmt.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<HisPaymentDetail> originPays = paymentDetailMapper.selectList(
                Wrappers.<HisPaymentDetail>lambdaQuery()
                        .eq(HisPaymentDetail::getBillId, origin.getId())
                        .orderByAsc(HisPaymentDetail::getId));
        if (!originPays.isEmpty()) {
            BigDecimal originSum = BigDecimal.ZERO;
            for (HisPaymentDetail p : originPays) {
                originSum = originSum.add(nvl(p.getAmount()));
            }
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < originPays.size(); i++) {
                HisPaymentDetail p = originPays.get(i);
                BigDecimal amt;
                if (i == originPays.size() - 1) {
                    // 末行兼领尾差, 避免多行 HALF_UP 累积造成合计不等于退费额
                    amt = refundAmt.subtract(allocated);
                } else {
                    amt = originSum.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                            : refundAmt.multiply(nvl(p.getAmount()))
                                    .divide(originSum, 2, RoundingMode.HALF_UP);
                    allocated = allocated.add(amt);
                }
                if (amt.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }
                HisPaymentDetail n = new HisPaymentDetail();
                n.setBillId(refundBill.getId());
                n.setPayMethod(p.getPayMethod());
                n.setAmount(amt.negate());
                n.setPayRef(p.getPayRef());
                n.setPayTime(LocalDateTime.now());
                paymentDetailMapper.insert(n);
            }
            return;
        }
        // 老单兑底: 医保统筹+个账 + 自付默认现金(按退费额占原单比例缩放)
        BigDecimal originTotal = nvl(origin.getTotalAmount());
        BigDecimal ratio = originTotal.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE
                : refundAmt.divide(originTotal, 6, RoundingMode.HALF_UP);
        BigDecimal ins = nvl(origin.getFundPay()).add(nvl(origin.getAcctPay())).multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        if (ins.compareTo(BigDecimal.ZERO) > 0) {
            savePaymentDetail(refundBill.getId(), "INSURANCE", ins.negate(), origin.getSetlId());
        }
        BigDecimal self = refundAmt.subtract(ins);
        if (self.compareTo(BigDecimal.ZERO) > 0) {
            savePaymentDetail(refundBill.getId(), "CASH", self.negate(), null);
        }
    }

    /** null 金额归零 */
    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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
