package com.yb.hi.service.cashier;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.common.DateUtil;
import com.yb.hi.common.YbResponse;
import com.yb.hi.config.TenantYbConfigResolver;
import com.yb.hi.config.YbRuntimeConfig;
import com.yb.hi.dto.FeeDetailReq;
import com.yb.hi.dto.FeeDetailRevokeReq;
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
import com.yb.hi.entity.yb.HisCompTask;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.cashier.HisChargeBillItemMapper;
import com.yb.hi.mapper.cashier.HisChargeBillMapper;
import com.yb.hi.mapper.cashier.HisDailySettleMapper;
import com.yb.hi.mapper.cashier.HisPaymentDetailMapper;
import com.yb.hi.mapper.yb.HisCompTaskMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.OutpatientService;
import com.yb.hi.service.doctor.HisVisitService;
import com.yb.hi.service.medtech.SpecimenService;
import com.yb.hi.service.nurse.NurseExecService;
import com.yb.hi.service.treatment.TreatmentPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
import com.yb.hi.service.warehouse.TraceCodeService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    /** 下游执行链路(护士站/治疗/医技): 收费完成后驱动单据生成, @Lazy 规避潜在循环依赖 */
    private final NurseExecService nurseExecService;
    private final TreatmentPlanService treatmentPlanService;
    private final SpecimenService specimenService;
    /** 补偿任务 Mapper(批次4: UNKNOWN 交易登记) */
    private final HisCompTaskMapper compTaskMapper;
    /** 就诊服务(M5: 收费前校验 2203 就诊上传状态) */
    private final HisVisitService visitService;
    /** 追溯码服务(批次5 M2 通道A): 已发药码随 2207 结算挂 drug_trac_info 节点报送并三分收口 */
    private final TraceCodeService traceCodeService;
    /** 两阶段化事务模板(T1/T3 显式事务边界, T2 医保调用不占事务) */
    private final TransactionTemplate txTemplate;

    public CashierService(HisChargeBillMapper billMapper, HisChargeBillItemMapper billItemMapper,
                          HisDailySettleMapper dailySettleMapper, HisPaymentDetailMapper paymentDetailMapper,
                          JdbcTemplate jdbcTemplate,
                          OutpatientService outpatientService, TenantYbConfigResolver ybConfigResolver,
                          OrgAccessGuard orgAccessGuard, InvoiceService invoiceService,
                          @Lazy NurseExecService nurseExecService,
                          @Lazy TreatmentPlanService treatmentPlanService,
                          @Lazy SpecimenService specimenService,
                          PlatformTransactionManager transactionManager,
                          HisCompTaskMapper compTaskMapper,
                          HisVisitService visitService,
                          TraceCodeService traceCodeService) {
        this.billMapper = billMapper;
        this.billItemMapper = billItemMapper;
        this.dailySettleMapper = dailySettleMapper;
        this.paymentDetailMapper = paymentDetailMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.outpatientService = outpatientService;
        this.ybConfigResolver = ybConfigResolver;
        this.orgAccessGuard = orgAccessGuard;
        this.invoiceService = invoiceService;
        this.nurseExecService = nurseExecService;
        this.treatmentPlanService = treatmentPlanService;
        this.specimenService = specimenService;
        this.compTaskMapper = compTaskMapper;
        this.visitService = visitService;
        this.traceCodeService = traceCodeService;
        this.txTemplate = new TransactionTemplate(transactionManager);
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
                        + " WHERE v.id = ? AND v.tenant_id = ? AND v.deleted = 0", visitId, tenantId());
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
        // 药品明细(处方): rx_type 供票据归并区分中草药费; selfpay_prop 取目录(甲乙丙分级 chrgitm_lv 归一化为首字符)
        List<Map<String, Object>> drugRows = jdbcTemplate.queryForList(
                "SELECT pi.id AS ref_id, pi.item_code, pi.item_name, pi.spec,"
                        + " pi.quantity AS qty, pi.price, pi.amount, pi.unit,"
                        + " COALESCE(pi.med_list_codg, dc.yb_drug_code) AS med_list_codg,"
                        + " COALESCE((SELECT sd.reg_name FROM std_drug sd"
                        + "           WHERE sd.drug_code = COALESCE(pi.med_list_codg, dc.yb_drug_code) LIMIT 1),"
                        + "          dc.generic_name) AS med_list_name,"
                        + " IFNULL(dc.selfpay_prop, 0) AS ratio, pr.rx_type,"
                        + " LEFT(dc.chrgitm_lv, 1) AS lv"
                        + " FROM his_prescription_item pi"
                        + " JOIN his_prescription pr ON pr.id = pi.prescription_id AND pr.status > 0"
                        + " LEFT JOIN his_drug_catalog dc ON dc.id = pi.drug_id"
                        + " WHERE pr.visit_id = ? AND pr.tenant_id = ? AND pi.deleted = 0 AND pr.deleted = 0"
                        + " ORDER BY pi.id", visitId, tenantId());
        for (Map<String, Object> r : drugRows) {
            items.add(buildItem(1, "prescription_item", r));
        }
        // 检查/治疗明细(医嘱单): 票据分类取收费项目字典 invoice_class(归集口径规范已填 64%), 空时服务层默认
        List<Map<String, Object>> orderRows = jdbcTemplate.queryForList(
                "SELECT oi.id AS ref_id, oi.item_code, oi.item_name, oi.spec,"
                        + " oi.quantity AS qty, oi.price, oi.amount, oi.unit,"
                        + " COALESCE(oi.med_list_codg, ci.med_list_codg) AS med_list_codg,"
                        + " (SELECT sms.loc_item_name FROM std_med_service sms"
                        + "  WHERE sms.nat_item_code = COALESCE(oi.med_list_codg, ci.med_list_codg) LIMIT 1) AS med_list_name,"
                        + " IFNULL(ci.selfpay_prop, 0) AS ratio, o.order_type, ci.invoice_class,"
                        + " LEFT(ci.chrgitm_lv, 1) AS lv"
                        + " FROM his_order_item oi"
                        + " JOIN his_order o ON o.id = oi.order_id AND o.status > 0"
                        + " LEFT JOIN his_charge_item ci ON ci.id = oi.item_id"
                        + " WHERE o.visit_id = ? AND o.tenant_id = ? AND oi.deleted = 0 AND o.deleted = 0"
                        + " ORDER BY oi.id", visitId, tenantId());
        for (Map<String, Object> r : orderRows) {
            items.add(buildItem(orderItemType(str(r.get("order_type"))), "order_item", r));
        }
        // 票据式样字段: 报销类别(甲/乙/丙/自费) + 自费自理(乙类先自付部分) + 其中医保政策范围外自费
        for (Map<String, Object> it : items) {
            enrichInvoiceFields(it);
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
        it.put("unit", r.get("unit"));
        it.put("qty", toBd(r.get("qty")));
        it.put("price", toBd(r.get("price")));
        it.put("amount", toBd(r.get("amount")));
        it.put("medListCodg", r.get("med_list_codg"));
        it.put("medListName", r.get("med_list_name"));
        it.put("ratio", toBd(r.get("ratio")));
        it.put("lv", r.get("lv"));
        it.put("rxType", r.get("rx_type"));
        it.put("invoiceClass", r.get("invoice_class"));
        return it;
    }

    /**
     * 票据式样字段计算(财综〔2012〕3号门诊收费票据):
     * - 报销类别 rebateClass: 医保甲类/乙类/丙类(按目录 chrgitm_lv), 无医保编码=自费;
     * - 自费自理 selfCost: 乙类先自付部分 = 金额 x 自付比例; 丙类/自费整项计入范围外;
     * - 医保政策范围外自费 outOfScope: 自费/丙类全额, 乙类取先自付部分;
     * - 票据归并类 invoiceCat: 药品按处方类型与院内编码首字母(中成药Z/饮片C,T/其余西药),
     *   服务项目优先收费项目字典 invoice_class(病理/麻醉/中医细分归并到父类), 材料默认卫生材料费。
     */
    private void enrichInvoiceFields(Map<String, Object> it) {
        BigDecimal amount = nvl(toBd(it.get("amount")));
        BigDecimal ratio = nvl(toBd(it.get("ratio")));
        String lv = str(it.get("lv"));
        String med = str(it.get("medListCodg"));
        String rebate;
        BigDecimal selfCost;
        BigDecimal outOfScope;
        if (!StringUtils.hasText(med)) {
            rebate = "自费";
            selfCost = BigDecimal.ZERO;
            outOfScope = amount;
        } else if ("乙".equals(lv)) {
            rebate = "医保乙类";
            selfCost = amount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            outOfScope = selfCost;
        } else if ("丙".equals(lv)) {
            rebate = "医保丙类";
            selfCost = BigDecimal.ZERO;
            outOfScope = amount;
        } else {
            rebate = "医保甲类";
            selfCost = BigDecimal.ZERO;
            outOfScope = BigDecimal.ZERO;
        }
        it.put("rebateClass", rebate);
        it.put("selfCost", selfCost);
        it.put("outOfScope", outOfScope);
        it.put("invoiceCat", invoiceCatOf(it));
    }

    /** 明细行 -> 门诊票据费用归并类(手工票 11 栏目) */
    private String invoiceCatOf(Map<String, Object> it) {
        Integer t = toInt(it.get("itemType"));
        if (t != null && t == 1) {
            String rxType = str(it.get("rxType"));
            if (rxType != null && rxType.contains("中")) {
                return "中草药费";
            }
            String code = str(it.get("itemCode"));
            String head = StringUtils.hasText(code) ? code.substring(0, 1).toUpperCase() : "";
            if ("Z".equals(head)) {
                return "中成药费";
            }
            if ("C".equals(head) || "T".equals(head)) {
                return "中草药费";
            }
            return "西药费";
        }
        if (t != null && t == 4) {
            return "卫生材料费";
        }
        String inv = str(it.get("invoiceClass"));
        if (StringUtils.hasText(inv)) {
            int dash = inv.indexOf('-');
            return dash > 0 ? inv.substring(0, dash) : inv;
        }
        if (t != null && t == 2) {
            return "检查费";
        }
        return "治疗费";
    }

    /** 医嘱单类型 -> 收费明细类型: 检查/检验=2, 治疗/其余=3 */
    private static int orderItemType(String orderType) {
        if ("检查".equals(orderType) || "检验".equals(orderType)) {
            return 2;
        }
        return 3;
    }

    // ==================== 收费 ====================

    /**
     * 医保收费(2204+2206+2207)。
     * 批次4两阶段化: T1 本地事务(行锁校验+建单中间态 status=0/yb_status=1) ->
     * T2 医保调用(无事务, 结果三分 SUCCESS/FAIL/UNKNOWN) ->
     * T3 终态落账(成功条件更新已收费; 明确失败置作废; UNKNOWN 建补偿任务挂起, 由 CompTaskSweeper 收敛)。
     */
    public Map<String, Object> charge(ChargeReq req) {
        boolean ybFlow = req == null || !"self".equalsIgnoreCase(req.getPayType());
        return doCharge(req, ybFlow);
    }

    /** 自费收费(不走医保, 全额自付现金, 单本地事务完成) */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> selfPayCharge(ChargeReq req) {
        return doCharge(req, false);
    }

    /** 收费主流程: T1 建单(自费单事务内直接终态) -> [医保] T2 医保链 -> T3 终态落账 */
    private Map<String, Object> doCharge(ChargeReq req, boolean ybFlow) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        // M5 收费入口守卫(设计 §3.5): 规范顺序上就诊上传(2203)是结算前置,
        // VISIT 未传成功时先补传 2203(无事务, 不占行锁), 成功才继续 2204/2206/2207;
        // 自费单不走医保链, 不适用
        if (ybFlow) {
            visitService.ensureVisitUploaded(req.getVisitId());
        }
        ChargeCtx ctx = txTemplate.execute(status -> buildStage1(req, ybFlow));
        if (!ybFlow) {
            // 自费单: 无医保环节, T1 事务内已完成终态落账
            return ctx.result;
        }
        // T2: 医保调用(无事务, 每笔交易独立回执; UNKNOWN 不落终态, 走补偿)
        ChargeOutcome outcome = callYbSettleChain(ctx);
        // T3: 终态落账(独立事务, 条件更新防并发/补偿收敛后重复落账)
        if (outcome.success) {
            try {
                finalizeSuccess(ctx, outcome);
            } catch (Exception e) {
                // 本地终态落账失败(单据中间态已提交, 无法回滚): 转补偿任务收敛, 避免单据永久挂起阻塞重收
                log.error("医保结算本地终态落账失败, 转补偿任务: billNo={}, 原因: {}", ctx.bill.getBillNo(), e.getMessage());
                createCompTaskAndThrow(ctx, outcome, "医保结算已完成但本地落账异常, 系统已登记补偿任务自动核对, 请稍后刷新查看");
            }
        } else if (outcome.unknown) {
            createCompTaskAndThrow(ctx, outcome,
                    "医保结算结果未知(网络超时/异常), 系统已登记补偿任务自动核对, 请稍后刷新查看; 详情: " + outcome.errMsg);
        } else {
            markBillFailed(ctx, outcome);
        }
        return ctx.result;
    }

    /**
     * T1: 本地事务内校验+建单。
     * 医保单落中间态(status=0, yb_status=1 结算中), 医保调用挪到事务外(T2);
     * 自费单无医保环节, 本事务内直接终态落账。
     */
    private ChargeCtx buildStage1(ChargeReq req, boolean ybFlow) {
        long tenantId = tenantId();
        // 1. 校验就诊: 已完成接诊且未收费
        //    FOR UPDATE 锁定就诊行: 并发收费窗口第二次进入时阻塞至前一单提交,
        //    读到 charge_status=1 后拒绝, 杜绝双窗口双收费
        List<Map<String, Object>> visitRows = jdbcTemplate.queryForList(
                "SELECT id, registration_id, patient_id, patient_name, mdtrt_id, psn_no, insutype, med_type,"
                        + " dept_code, dept_name, atddr_no, dr_name, visit_status, charge_status"
                        + " FROM his_visit WHERE id = ? AND tenant_id = ? AND deleted = 0 FOR UPDATE",
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
        // 1a. 结算中守卫(批次4): 就诊已有挂起的医保单(上次 UNKNOWN 待补偿收敛)时拒绝重复发起;
        //     自费单无医保环节不适用
        if (ybFlow) {
            Integer inProgress = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_charge_bill"
                            + " WHERE visit_id = ? AND tenant_id = ? AND bill_type = 1 AND deleted = 0"
                            + " AND status = 0 AND yb_status = 1",
                    Integer.class, req.getVisitId(), tenantId);
            if (inProgress != null && inProgress > 0) {
                throw new BizException("该就诊存在医保结算中的收费单(上次结算结果待确认), 请稍后刷新查看");
            }
        }
        // 1b. 医保单先决校验: 无医保就诊信息时建单前拒绝(可直接改用自费收费, 不留中间单)
        if (ybFlow && (!StringUtils.hasText(str(visit.get("mdtrt_id"))) || !StringUtils.hasText(str(visit.get("psn_no"))))) {
            throw new BizException("该就诊无医保就诊信息, 请使用自费收费");
        }

        // 1c. 2207必填回填: 就诊凭证(挂号记录) + 参保地区划(参保记录, 规范表3: 输入含psn_no时insuplc_admdvs必填)
        String mdtrtCertType = "02";
        String mdtrtCertNo = null;
        String insuplcAdmdvs = null;
        List<Map<String, Object>> extRows = jdbcTemplate.queryForList(
                "SELECT r.mdtrt_cert_type, r.mdtrt_cert_no, pi.insuplc_admdvs"
                        + " FROM his_visit v"
                        + " LEFT JOIN his_registration r ON r.id = v.registration_id AND r.deleted = 0"
                        + " LEFT JOIN his_patient_insu pi ON pi.patient_id = v.patient_id AND pi.psn_no = v.psn_no AND pi.deleted = 0"
                        + " WHERE v.id = ? AND v.tenant_id = ? AND v.deleted = 0",
                req.getVisitId(), tenantId);
        if (!extRows.isEmpty()) {
            Map<String, Object> ext = extRows.get(0);
            mdtrtCertType = StringUtils.hasText(str(ext.get("mdtrt_cert_type"))) ? str(ext.get("mdtrt_cert_type")) : "02";
            mdtrtCertNo = str(ext.get("mdtrt_cert_no"));
            insuplcAdmdvs = str(ext.get("insuplc_admdvs"));
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

        // 3. 创建收费单(医保单先落中间态: status=0/yb_status=1 结算中, 结算成功后 T3 转已收费)
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
        if (ybFlow) {
            bill.setYbStatus(1);
        }
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

        ChargeCtx ctx = new ChargeCtx(req, visit, bill, items, total, mdtrtCertType, mdtrtCertNo, insuplcAdmdvs);
        if (!ybFlow) {
            // 自费单: 全额自付, 无医保环节, 本事务内直接终态
            ChargeOutcome selfOut = ChargeOutcome.success();
            selfOut.selfPay = total;
            finishBillLocal(ctx, selfOut, false);
        }
        return ctx;
    }

    /**
     * T2: 医保交易链 2204 -> 2206 -> 2207(无事务)。
     * 每笔交易独立判定三分结果: 平台明确拒绝=FAIL(单据置作废可重试),
     * 超时/网络异常=UNKNOWN(建补偿任务挂起), 全部成功才返回 SUCCESS 金额四分。
     */
    private ChargeOutcome callYbSettleChain(ChargeCtx ctx) {
        String mdtrtId = str(ctx.visit.get("mdtrt_id"));
        String psnNo = str(ctx.visit.get("psn_no"));
        SettlementReq setlReq = new SettlementReq();
        setlReq.setPsnNo(psnNo);
        setlReq.setMdtrtId(mdtrtId);
        setlReq.setInsutype(str(ctx.visit.get("insutype")));
        // 医疗类别随挂号/就诊同步(如急诊14), 未同步的老数据兕底普通门诊11
        String medType = str(ctx.visit.get("med_type"));
        setlReq.setMedType(StringUtils.hasText(medType) ? medType : "11");
        setlReq.setMedfeeSumamt(ctx.total);
        setlReq.setPsnSetlway("01");
        setlReq.setMdtrtCertType(ctx.mdtrtCertType);
        setlReq.setMdtrtCertNo(ctx.mdtrtCertNo);
        setlReq.setChrgBchno(ctx.bill.getBillNo());

        // 2204 费用明细上传: 规范要求 2204 -> 2206 -> 2207 顺序;
        // 无医保目录编码的自费明细不上传(金额计入全自费)
        List<FeeDetailReq> feeDetails = buildFeeDetails(ctx.items, mdtrtId, psnNo, ctx.bill.getBillNo(), ctx.visit);
        if (!CollectionUtils.isEmpty(feeDetails)) {
            YbResponse feeResp = outpatientService.uploadFeeDetail(feeDetails, ctx.insuplcAdmdvs);
            if (feeResp == null) {
                return ChargeOutcome.unknown(null, "医保费用明细上传(2204)无响应");
            }
            if (feeResp.isUnknown()) {
                return ChargeOutcome.unknown(findTxnLogId(feeResp), "医保费用明细上传(2204)网络异常, 结果未知");
            }
            if (!feeResp.isSuccess()) {
                return ChargeOutcome.fail("医保费用明细上传(2204)失败: " + feeResp.getErrMsg());
            }
        }
        // 2206 预结算
        YbResponse preResp = outpatientService.preSettlement(setlReq, ctx.insuplcAdmdvs);
        if (preResp == null) {
            return ChargeOutcome.unknown(null, "医保预结算(2206)无响应");
        }
        if (preResp.isUnknown()) {
            return ChargeOutcome.unknown(findTxnLogId(preResp), "医保预结算(2206)网络异常, 结果未知");
        }
        if (!preResp.isSuccess()) {
            return ChargeOutcome.fail("医保预结算(2206)失败: " + preResp.getErrMsg());
        }
        // 预结算金额四分回传2207(fulamt_ownpay_amt/overlmt_selfpay/preselfpay_amt/inscp_scp_amt 以平台预结算结果为准);
        // 数值型按规范为空传"0"; acct_used_flag 按预结算是否动用个账
        JSONObject preSetlinfo = preResp.getOutputNode("setlinfo");
        setlReq.setFulamtOwnpayAmt(firstNonNull(bd(preSetlinfo, "fulamt_ownpay_amt"), BigDecimal.ZERO));
        setlReq.setOverlmtSelfpay(firstNonNull(bd(preSetlinfo, "overlmt_selfpay"), BigDecimal.ZERO));
        setlReq.setPreselfpayAmt(firstNonNull(bd(preSetlinfo, "preselfpay_amt"), BigDecimal.ZERO));
        setlReq.setInscpScpAmt(firstNonNull(bd(preSetlinfo, "inscp_scp_amt"), BigDecimal.ZERO));
        BigDecimal preAcct = bd(preSetlinfo, "acct_pay");
        setlReq.setAcctUsedFlag(preAcct != null && preAcct.compareTo(BigDecimal.ZERO) > 0 ? "1" : "0");
        // 2207 正式结算, 取结算ID; 批次5 M2 通道A: 本次就诊已发药追溯码挂 drug_trac_info 节点随结算报送(认领→发送→三分收口)
        TraceCodeService.SettlementTrace stTrace = traceCodeService.buildSettlementNodes(ctx.req.getVisitId(), ctx.items);
        YbResponse setlResp = outpatientService.settlement(setlReq, ctx.insuplcAdmdvs, stTrace.nodes());
        if (setlResp == null) {
            traceCodeService.finalizeSettlementUpload(stTrace, false, true, mdtrtId, null, null);
            return ChargeOutcome.unknown(null, "医保结算(2207)无响应");
        }
        if (setlResp.isUnknown()) {
            traceCodeService.finalizeSettlementUpload(stTrace, false, true, mdtrtId, setlResp.getInfRefmsgid(), null);
            return ChargeOutcome.unknown(findTxnLogId(setlResp), "医保结算(2207)网络异常, 结果未知");
        }
        if (!setlResp.isSuccess()) {
            traceCodeService.finalizeSettlementUpload(stTrace, false, false, mdtrtId, null, "2207结算被平台拒绝: " + setlResp.getErrMsg());
            return ChargeOutcome.fail("医保结算(2207)失败: " + setlResp.getErrMsg());
        }
        JSONObject setlinfo = setlResp.getOutputNode("setlinfo");
        String setlId = setlinfo == null ? null : setlinfo.getString("setl_id");
        // 2207成功但缺少setl_id: 规范出参必含结算ID, 缺失即平台侧异常, 本地不得置已收费(否则无法撤销)
        if (!StringUtils.hasText(setlId)) {
            log.error("医保结算(2207)返回缺少setl_id: billNo={}, visitId={}", ctx.bill.getBillNo(), ctx.req.getVisitId());
            traceCodeService.finalizeSettlementUpload(stTrace, false, false, mdtrtId, null, "2207成功但缺setl_id(平台侧异常)");
            return ChargeOutcome.fail("医保结算(2207)返回缺少结算ID(setl_id), 请核对医保平台结算状态后处理");
        }
        // 结算实际成功: 追溯码收口已报送(通道A)
        traceCodeService.finalizeSettlementUpload(stTrace, true, false, mdtrtId, setlResp.getInfRefmsgid(), null);
        // 金额四分(自付/基金/现金/个账)
        BigDecimal[] split = splitAmounts(setlinfo, ctx.total);
        ChargeOutcome o = ChargeOutcome.success();
        o.setlId = setlId;
        o.fundPay = split[0];
        o.acctPay = split[1];
        o.selfPay = split[2];
        o.txnLogId = findTxnLogId(setlResp);
        return o;
    }

    /** T3: 结算成功本地终态落账(独立事务, 内部条件更新防并发重复落账) */
    private void finalizeSuccess(ChargeCtx ctx, ChargeOutcome o) {
        txTemplate.executeWithoutResult(status -> finishBillLocal(ctx, o, true));
    }

    /** 平台明确拒绝(FAIL): 单据置作废(status=-1), 就诊保持未收费, 可重新发起收费(新单新 chrg_bchno) */
    private void markBillFailed(ChargeCtx ctx, ChargeOutcome o) {
        txTemplate.executeWithoutResult(status -> {
            int updated = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getStatus, -1)
                    .set(HisChargeBill::getYbStatus, 0)
                    .set(HisChargeBill::getRemark,
                            o.errMsg != null && o.errMsg.length() > 490 ? o.errMsg.substring(0, 490) : o.errMsg)
                    .eq(HisChargeBill::getId, ctx.bill.getId())
                    .eq(HisChargeBill::getStatus, 0)
                    .eq(HisChargeBill::getYbStatus, 1));
            log.warn("医保结算明确失败, 单据置作废: billNo={}, affected={}, 原因: {}",
                    ctx.bill.getBillNo(), updated, o.errMsg);
        });
        throw new BizException("医保结算失败: " + o.errMsg);
    }

    /** UNKNOWN(超时/网络异常): 补偿任务独立事务落库, 单据留中间态挂起, 由 CompTaskSweeper 收敛 */
    private void createCompTaskAndThrow(ChargeCtx ctx, ChargeOutcome o, String userMsg) {
        txTemplate.executeWithoutResult(status -> {
            HisCompTask task = new HisCompTask();
            task.setBizType(HisCompTask.BIZ_CHARGE);
            task.setRefId(ctx.bill.getId());
            task.setAction(HisCompTask.ACT_RESOLVE_UNKNOWN);
            task.setTxnLogId(o.txnLogId);
            task.setStatus(HisCompTask.ST_PENDING);
            task.setAttempts(0);
            task.setNextRun(LocalDateTime.now());
            task.setMemo(o.errMsg);
            compTaskMapper.insert(task);
        });
        throw new BizException(userMsg);
    }

    /**
     * 本地终态落账(自费单在 T1 事务内 / 医保单在 T3 事务内):
     * 金额落单(条件更新防并发重复落账) -> 支付明细 -> 就诊收费状态 -> 医嘱下游联动 -> 发票 -> 回执。
     */
    private void finishBillLocal(ChargeCtx ctx, ChargeOutcome o, boolean ybFlow) {
        // 多支付方式处理: payments 非空时校验金额守恒并拆分(医保单=自付部分, 自费单=全额);
        // payments 为空(老接口)保持 cashPay=selfPay 全现金兼容行为
        BigDecimal insPay = o.fundPay.add(o.acctPay);
        List<PaymentItem> payments = normalizePayments(ctx.req.getPayments(), ybFlow ? o.selfPay : ctx.total);
        BigDecimal cashPay = o.selfPay;
        if (payments != null) {
            cashPay = BigDecimal.ZERO;
            for (PaymentItem p : payments) {
                if ("CASH".equals(p.getPayMethod())) {
                    cashPay = cashPay.add(p.getAmount());
                }
            }
        }

        ctx.bill.setFundPay(o.fundPay);
        ctx.bill.setAcctPay(o.acctPay);
        ctx.bill.setSelfPay(o.selfPay);
        ctx.bill.setCashPay(cashPay);
        ctx.bill.setPayMethod(resolveMainPayMethod(payments, ybFlow, insPay, ctx.total));
        ctx.bill.setStatus(1);
        ctx.bill.setChargeBy(UserContext.username());
        ctx.bill.setChargeTime(LocalDateTime.now());
        if (ybFlow) {
            ctx.bill.setYbStatus(2);
            ctx.bill.setSetlId(o.setlId);
        }
        // 条件更新(仅中间态可置已收费: 并发双击/重复提交/补偿任务已收敛时 affected=0)
        int updated = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                .set(HisChargeBill::getFundPay, ctx.bill.getFundPay())
                .set(HisChargeBill::getAcctPay, ctx.bill.getAcctPay())
                .set(HisChargeBill::getSelfPay, ctx.bill.getSelfPay())
                .set(HisChargeBill::getCashPay, ctx.bill.getCashPay())
                .set(HisChargeBill::getPayMethod, ctx.bill.getPayMethod())
                .set(HisChargeBill::getStatus, 1)
                .set(HisChargeBill::getChargeBy, ctx.bill.getChargeBy())
                .set(HisChargeBill::getChargeTime, ctx.bill.getChargeTime())
                .set(ybFlow, HisChargeBill::getYbStatus, 2)
                .set(ybFlow, HisChargeBill::getSetlId, o.setlId)
                .eq(HisChargeBill::getId, ctx.bill.getId())
                .eq(HisChargeBill::getStatus, 0)
                .eq(ybFlow, HisChargeBill::getYbStatus, 1));
        if (updated != 1) {
            log.error("收费单终态条件更新失败: billId={}, affected={}", ctx.bill.getId(), updated);
            throw new BizException("收费单状态已变化, 请刷新后重试");
        }

        // 支付明细落库: 医保部分(INSURANCE, 流水号=结算ID) + 自费各方式逐笔
        if (ybFlow && insPay.compareTo(BigDecimal.ZERO) > 0) {
            savePaymentDetail(ctx.bill.getId(), "INSURANCE", insPay, o.setlId);
        }
        if (payments != null) {
            for (PaymentItem p : payments) {
                savePaymentDetail(ctx.bill.getId(), p.getPayMethod(), p.getAmount(), p.getPayRef());
            }
        }

        // 回写就诊收费状态(条件更新: 仅未收费可置已收费, 并发双击/重复提交时第二次受影响行数为0)
        int chargeUpdated = jdbcTemplate.update(
                "UPDATE his_visit SET charge_status = 1 WHERE id = ? AND tenant_id = ? AND charge_status = 0 AND deleted = 0",
                ctx.req.getVisitId(), tenantId());
        if (chargeUpdated != 1) {
            log.error("收费状态条件更新失败: visitId={}, affected={}", ctx.req.getVisitId(), chargeUpdated);
            throw new BizException("就诊收费状态已变化, 请刷新后重试");
        }

        // 医嘱下游联动: 回写 paid_flag 并驱动治疗计划/标本/护士执行单生成(失败不阻塞收费)
        syncOrderAfterCharge(ctx.req.getVisitId());

        // 发票联动: 自动取号开票并回写 bill.invoice_no; 号段未配置/用完仅记日志不阻塞收费
        try {
            HisInvoice invoice = invoiceService.createInvoice(ctx.bill.getId());
            ctx.bill.setInvoiceNo(invoice.getInvoiceNo());
        } catch (Exception e) {
            log.warn("发票自动分配失败(不阻塞收费): billNo={}, 原因: {}", ctx.bill.getBillNo(), e.getMessage());
        }

        log.info("收费完成: billNo={}, visitId={}, total={}, fund={}, acct={}, self={}, payMethod={}, invoiceNo={}, setlId={}",
                ctx.bill.getBillNo(), ctx.req.getVisitId(), ctx.total, o.fundPay, o.acctPay, o.selfPay,
                ctx.bill.getPayMethod(), ctx.bill.getInvoiceNo(), o.setlId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bill", ctx.bill);
        result.put("receipt", billReceipt(ctx.bill.getId()));
        ctx.result = result;
    }

    /** 按 UNKNOWN 交易请求报文 msgid 回查交易日志ID(补偿任务挂接原交易) */
    private Long findTxnLogId(YbResponse resp) {
        try {
            String reqJson = resp.getRequestJson();
            if (reqJson == null) {
                return null;
            }
            String msgid = JSON.parseObject(reqJson).getString("msgid");
            if (msgid == null) {
                return null;
            }
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM his_yb_txn_log WHERE msgid = ? AND tenant_id = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
                    Long.class, msgid, tenantId());
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            log.warn("回查医保交易日志ID失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 补偿收敛入口(CompTaskSweeper 驱动): UNKNOWN 医保收费单终态回填或复位。
     * platformAccepted=true(核对平台侧已受理): 条件回填已收费+金额四分(mock 70/10/20)+就诊状态+支付明细+下游联动;
     * false(平台未受理): 中间态复位 yb_status 1->0, 允许重新收费。
     * 调用方传入 tenantId(调度线程无请求上下文), 内部为 Mapper 操作临时设置 TenantContext。
     * @return 收敛结果说明(由补偿任务记入 memo)
     */
    public String resolveUnknownCharge(Long tenantId, Long billId, boolean platformAccepted, String setlId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, visit_id, total_amount, create_by, bill_no FROM his_charge_bill"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND status = 0 AND yb_status = 1",
                billId, tenantId);
        if (rows.isEmpty()) {
            return "单据中间态已不存在(可能已人工处理)";
        }
        if (!platformAccepted) {
            int reset = jdbcTemplate.update(
                    "UPDATE his_charge_bill SET yb_status = 0"
                            + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND status = 0 AND yb_status = 1",
                    billId, tenantId);
            return reset == 1 ? "平台未受理, 中间态已复位, 可重新收费" : "复位失败(单据状态已变化)";
        }
        Map<String, Object> r = rows.get(0);
        Long visitId = toLong(r.get("visit_id"));
        BigDecimal total = toBd(r.get("total_amount"));
        if (total == null) {
            total = BigDecimal.ZERO;
        }
        BigDecimal fundPay = total.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal acctPay = total.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal selfPay = total.subtract(fundPay).subtract(acctPay);
        TenantContext.set(tenantId);
        try {
            return txTemplate.execute(status -> {
                int updated = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                        .set(HisChargeBill::getStatus, 1)
                        .set(HisChargeBill::getYbStatus, 2)
                        .set(HisChargeBill::getSetlId, setlId)
                        .set(HisChargeBill::getFundPay, fundPay)
                        .set(HisChargeBill::getAcctPay, acctPay)
                        .set(HisChargeBill::getSelfPay, selfPay)
                        .set(HisChargeBill::getCashPay, selfPay)
                        .set(HisChargeBill::getPayMethod, "INSURANCE")
                        .set(HisChargeBill::getChargeBy, str(r.get("create_by")))
                        .set(HisChargeBill::getChargeTime, LocalDateTime.now())
                        .eq(HisChargeBill::getId, billId)
                        .eq(HisChargeBill::getStatus, 0)
                        .eq(HisChargeBill::getYbStatus, 1));
                if (updated != 1) {
                    return "回填失败(单据状态已变化)";
                }
                BigDecimal ins = fundPay.add(acctPay);
                if (ins.compareTo(BigDecimal.ZERO) > 0) {
                    savePaymentDetail(billId, "INSURANCE", ins, setlId);
                }
                if (selfPay.compareTo(BigDecimal.ZERO) > 0) {
                    savePaymentDetail(billId, "CASH", selfPay, null);
                }
                if (visitId != null) {
                    int v = jdbcTemplate.update(
                            "UPDATE his_visit SET charge_status = 1"
                                    + " WHERE id = ? AND tenant_id = ? AND charge_status = 0 AND deleted = 0",
                            visitId, tenantId);
                    if (v != 1) {
                        log.warn("补偿回填: 就诊收费状态未更新(可能已变化): visitId={}, affected={}", visitId, v);
                    }
                    syncOrderAfterCharge(visitId);
                }
                return "平台已受理, 已补录终态(setl_id=" + setlId + ")";
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** T1->T2->T3 收费上下文 */
    private static final class ChargeCtx {
        final ChargeReq req;
        final Map<String, Object> visit;
        final HisChargeBill bill;
        final List<Map<String, Object>> items;
        final BigDecimal total;
        final String mdtrtCertType;
        final String mdtrtCertNo;
        final String insuplcAdmdvs;
        Map<String, Object> result;

        ChargeCtx(ChargeReq req, Map<String, Object> visit, HisChargeBill bill, List<Map<String, Object>> items,
                  BigDecimal total, String mdtrtCertType, String mdtrtCertNo, String insuplcAdmdvs) {
            this.req = req;
            this.visit = visit;
            this.bill = bill;
            this.items = items;
            this.total = total;
            this.mdtrtCertType = mdtrtCertType;
            this.mdtrtCertNo = mdtrtCertNo;
            this.insuplcAdmdvs = insuplcAdmdvs;
        }
    }

    /** 医保结算链结果(三分): success=平台明确成功 / fail=平台明确拒绝 / unknown=超时网络异常 */
    private static final class ChargeOutcome {
        boolean success;
        boolean fail;
        boolean unknown;
        String errMsg;
        Long txnLogId;
        String setlId;
        BigDecimal fundPay = BigDecimal.ZERO;
        BigDecimal acctPay = BigDecimal.ZERO;
        BigDecimal selfPay = BigDecimal.ZERO;

        static ChargeOutcome success() {
            ChargeOutcome o = new ChargeOutcome();
            o.success = true;
            return o;
        }

        static ChargeOutcome fail(String errMsg) {
            ChargeOutcome o = new ChargeOutcome();
            o.fail = true;
            o.errMsg = errMsg;
            return o;
        }

        static ChargeOutcome unknown(Long txnLogId, String errMsg) {
            ChargeOutcome o = new ChargeOutcome();
            o.unknown = true;
            o.txnLogId = txnLogId;
            o.errMsg = errMsg;
            return o;
        }
    }

    /** 退费两阶段上下文(T1 构建) */
    private static final class RefundCtx {
        final HisChargeBill origin;
        final HisChargeBill refundBill;
        final boolean hasSetl;
        final SetlCancelReq cancelReq;
        final String insuplcAdmdvs;

        RefundCtx(HisChargeBill origin, HisChargeBill refundBill, boolean hasSetl,
                  SetlCancelReq cancelReq, String insuplcAdmdvs) {
            this.origin = origin;
            this.refundBill = refundBill;
            this.hasSetl = hasSetl;
            this.cancelReq = cancelReq;
            this.insuplcAdmdvs = insuplcAdmdvs;
        }
    }

    /** 退费(2208)结果(三分): success=平台明确成功 / fail=平台明确拒绝 / unknown=超时网络异常 */
    private static final class RefundOutcome {
        boolean success;
        boolean fail;
        boolean unknown;
        String errMsg;
        Long txnLogId;

        static RefundOutcome success() {
            RefundOutcome o = new RefundOutcome();
            o.success = true;
            return o;
        }

        static RefundOutcome fail(String errMsg) {
            RefundOutcome o = new RefundOutcome();
            o.fail = true;
            o.errMsg = errMsg;
            return o;
        }

        static RefundOutcome unknown(Long txnLogId, String errMsg) {
            RefundOutcome o = new RefundOutcome();
            o.unknown = true;
            o.txnLogId = txnLogId;
            o.errMsg = errMsg;
            return o;
        }
    }

    /** 部分退费全撤重结上下文(T1 构建; 补偿重放时由 loadPartialCtx 重建) */
    private static final class PartialCtx {
        final HisChargeBill origin;
        final HisChargeBill refundBill;
        final boolean hasSetl;
        final String mdtrtId;
        final String psnNo;
        final String insuplcAdmdvs;
        final String mdtrtCertType;
        final String mdtrtCertNo;
        final Map<String, Object> visit;
        final List<Map<String, Object>> remainingItems;
        final BigDecimal remainingTotal;
        final String rebuildBchno;
        final boolean allRefunded;
        final Map<Long, BigDecimal> lineQtyDelta;
        /** 批次5 M2 通道A: 原单就诊ID(追溯码按就诊认领随重结算报送; 非医保链可空) */
        final Long visitId;

        PartialCtx(HisChargeBill origin, HisChargeBill refundBill, boolean hasSetl, String mdtrtId, String psnNo,
                   String insuplcAdmdvs, String mdtrtCertType, String mdtrtCertNo, Map<String, Object> visit,
                   List<Map<String, Object>> remainingItems, BigDecimal remainingTotal, String rebuildBchno,
                   boolean allRefunded, Map<Long, BigDecimal> lineQtyDelta, Long visitId) {
            this.origin = origin;
            this.refundBill = refundBill;
            this.hasSetl = hasSetl;
            this.mdtrtId = mdtrtId;
            this.psnNo = psnNo;
            this.insuplcAdmdvs = insuplcAdmdvs;
            this.mdtrtCertType = mdtrtCertType;
            this.mdtrtCertNo = mdtrtCertNo;
            this.visit = visit;
            this.remainingItems = remainingItems;
            this.remainingTotal = remainingTotal;
            this.rebuildBchno = rebuildBchno;
            this.allRefunded = allRefunded;
            this.lineQtyDelta = lineQtyDelta;
            this.visitId = visitId;
        }
    }

    /** 全撤重结链结果(三分+发生步骤) */
    private static final class PartialOutcome {
        boolean success;
        boolean fail;
        boolean unknown;
        /** 失败/未知发生在第几步: 1=2208 2=2205 3=2204 4=2206 5=2207 */
        int stage;
        String errMsg;
        Long txnLogId;
        String setlId;
        BigDecimal fundPay = BigDecimal.ZERO;
        BigDecimal acctPay = BigDecimal.ZERO;
        BigDecimal selfPay = BigDecimal.ZERO;
        boolean allRefunded;

        static PartialOutcome success() {
            PartialOutcome o = new PartialOutcome();
            o.success = true;
            return o;
        }

        static PartialOutcome fail(int stage, String errMsg) {
            PartialOutcome o = new PartialOutcome();
            o.fail = true;
            o.stage = stage;
            o.errMsg = errMsg;
            return o;
        }

        static PartialOutcome unknown(Long txnLogId, int stage, String errMsg) {
            PartialOutcome o = new PartialOutcome();
            o.unknown = true;
            o.txnLogId = txnLogId;
            o.stage = stage;
            o.errMsg = errMsg;
            return o;
        }
    }

    /**
     * 收费完成后的医嘱下游联动(门诊闭环关键接缝):
     * 1) 本就诊未作废医嘱统一置 paid_flag=1 —— 护士站/治疗/医技三个工作台的待执行查询均以此为准;
     * 2) 按单据类型驱动下游单据生成: 治疗→疗程计划+执行单, 检验→标本, 全部→护士执行单(明细关键词命中才建);
     * 3) 逐类 try-catch 只记日志: 下游单据可由各工作台查询侧兜底补建, 不能因建单失败回滚已成功的收费/医保结算。
     */
    private void syncOrderAfterCharge(Long visitId) {
        long tid = tenantId();
        try {
            jdbcTemplate.update("UPDATE his_order SET paid_flag = 1"
                    + " WHERE visit_id = ? AND tenant_id = ? AND deleted = 0 AND status > 0 AND IFNULL(paid_flag, 0) <> 1",
                    visitId, tid);
        } catch (Exception e) {
            log.warn("医嘱收费标志回写失败: visitId={}, 原因: {}", visitId, e.getMessage());
        }
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT id, order_no, order_type FROM his_order"
                        + " WHERE visit_id = ? AND tenant_id = ? AND deleted = 0 AND status > 0 ORDER BY id",
                visitId, tid);
        for (Map<String, Object> o : orders) {
            Long orderId = toLong(o.get("id"));
            String orderType = str(o.get("order_type"));
            if ("治疗".equals(orderType)) {
                try {
                    treatmentPlanService.createPlanFromOrder(orderId);
                } catch (Exception e) {
                    log.warn("治疗计划自动生成失败(可治疗台补建): orderNo={}, 原因: {}", o.get("order_no"), e.getMessage());
                }
            } else if ("检验".equals(orderType)) {
                try {
                    specimenService.generateSpecimens(orderId);
                } catch (Exception e) {
                    log.warn("标本自动生成失败(可医技台补生成): orderNo={}, 原因: {}", o.get("order_no"), e.getMessage());
                }
            }
            try {
                nurseExecService.createExecRecords(orderId);
            } catch (Exception e) {
                log.warn("护士执行单自动生成失败(可护士站兜底补建): orderNo={}, 原因: {}", o.get("order_no"), e.getMessage());
            }
        }
    }

    /**
     * 退费(批次4两阶段化):
     * T1 本地事务(行锁原单 + 校验 + 建退费单中间态 + 原单 yb_status=3 撤销中) ->
     * T2 无事务 2208 结算撤销(超时即 UNKNOWN, 绝不重发——重复 2208 会撤错或拒) ->
     * T3 终态落账(成功: 原单 1->2/yb 3->4、退费单 0->1、就诊 1->2、发票红冲;
     *    明确失败: 原单回已结算、退费单作废; UNKNOWN: 补偿任务挂起, CompTaskSweeper 按平台侧撤销状态收敛)。
     * 自费单(无医保结算)无平台环节, T1 事务内直接完成全部退费。
     */
    public HisChargeBill refund(RefundReq req) {
        RefundCtx ctx = txTemplate.execute(status -> buildRefundStage1(req));
        if (!ctx.hasSetl) {
            return ctx.refundBill;
        }
        // T2: 2208 撤销(不重发原则: 超时/网络异常一律 UNKNOWN, 交补偿任务核对)
        YbResponse resp = outpatientService.cancelSettlement(ctx.cancelReq, ctx.insuplcAdmdvs);
        RefundOutcome outcome;
        if (resp == null) {
            outcome = RefundOutcome.unknown(null, "医保结算撤销(2208)无响应");
        } else if (resp.isUnknown()) {
            outcome = RefundOutcome.unknown(findTxnLogId(resp), "医保结算撤销(2208)网络异常, 结果未知");
        } else if (!resp.isSuccess()) {
            outcome = RefundOutcome.fail("医保结算撤销(2208)失败: " + resp.getErrMsg());
        } else {
            outcome = RefundOutcome.success();
        }
        // T3: 终态落账
        if (outcome.success) {
            finalizeRefund(ctx);
        } else if (outcome.unknown) {
            createRefundCompTaskAndThrow(ctx, outcome);
        } else {
            refundFailed(ctx, outcome);
        }
        return ctx.refundBill;
    }

    /** T1: 校验原单(行锁/撤销中/机构/日结/部分退互斥/已发药/已执行) + 建退费单中间态 + 原单置撤销中 */
    private RefundCtx buildRefundStage1(RefundReq req) {
        if (req == null || req.getBillId() == null) {
            throw new BizException(400, "收费单ID不能为空");
        }
        // 行锁: 锁定原单行串行化退费请求(并发双击/全额与部分并发时, 后到事务在此阻塞, 提交后读到 status=2 被拒)
        HisChargeBill origin = billMapper.selectOne(Wrappers.<HisChargeBill>lambdaQuery()
                .eq(HisChargeBill::getId, req.getBillId())
                .last("FOR UPDATE"));
        if (origin == null) {
            throw new BizException(400, "收费单不存在");
        }
        if (origin.getBillType() != null && origin.getBillType() == 2) {
            throw new BizException("退费单不能再次退费");
        }
        if (origin.getStatus() == null || origin.getStatus() != 1) {
            throw new BizException("该收费单当前状态不允许退费");
        }
        // 批次4 撤销中守卫: 医保撤销中(yb_status=3)或冲正中(9)的收费单不可并发退费
        if (origin.getYbStatus() != null && (origin.getYbStatus() == 3 || origin.getYbStatus() == 9)) {
            throw new BizException("该收费单医保结算处理中, 请稍后刷新重试");
        }
        // 机构隔离: 非牵头机构用户仅可退本机构收费单(牵头机构可退全医共体)
        LoginUser lu = UserContext.get();
        if (lu != null && lu.getOrgId() != null && origin.getOrgId() != null
                && !origin.getOrgId().equals(lu.getOrgId()) && !orgAccessGuard.isLead()) {
            throw new BizException(403, "仅可退本机构收费单");
        }
        // 已日结不可退费(仅锁定日结时刻之前收取的单: 日结后当日新收的票未被汇总, 不应被历史日结误锁)
        if (origin.getChargeTime() != null && origin.getOrgId() != null) {
            HisDailySettle settled = dailySettleMapper.selectOne(Wrappers.<HisDailySettle>lambdaQuery()
                    .eq(HisDailySettle::getOrgId, origin.getOrgId())
                    .eq(HisDailySettle::getSettleDate, origin.getChargeTime().toLocalDate())
                    .eq(HisDailySettle::getStatus, 1)
                    .last("LIMIT 1"));
            if (settled != null && (settled.getSettleTime() == null
                    || !origin.getChargeTime().isAfter(settled.getSettleTime()))) {
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
        // 退药/执行守卫(批次4): 已发药药品先退药, 已执行项目不可退
        assertRefundableItems(origin.getId(), null);

        boolean hasSetl = StringUtils.hasText(origin.getSetlId()) && origin.getVisitId() != null;
        SetlCancelReq cancelReq = null;
        String insuplcAdmdvs = null;
        if (hasSetl) {
            Map<String, Object> yb = loadVisitYbInfo(origin.getVisitId());
            String mdtrtId = str(yb.get("mdtrt_id"));
            String psnNo = str(yb.get("psn_no"));
            if (!StringUtils.hasText(mdtrtId) || !StringUtils.hasText(psnNo)) {
                throw new BizException("该收费单就诊医保信息缺失, 无法线上退费, 请联系管理员处理");
            }
            cancelReq = new SetlCancelReq();
            cancelReq.setSetlId(origin.getSetlId());
            cancelReq.setMdtrtId(mdtrtId);
            cancelReq.setPsnNo(psnNo);
            insuplcAdmdvs = str(yb.get("insuplc_admdvs"));
            // 原单置撤销中(yb_status=3): 并发退费互斥由入口守卫完成
            int setRevoking = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getYbStatus, 3)
                    .eq(HisChargeBill::getId, origin.getId())
                    .eq(HisChargeBill::getStatus, 1));
            if (setRevoking != 1) {
                throw new BizException("收费单状态已变化, 请刷新后重试");
            }
        }

        // 生成退费单(关联原单: originBillId + 备注记录原单号与原因); 医保退费先落中间态 status=0
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
        refundBill.setStatus(hasSetl ? 0 : 1);
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

        RefundCtx ctx = new RefundCtx(origin, refundBill, hasSetl, cancelReq, insuplcAdmdvs);
        if (!hasSetl) {
            // 自费单: 无医保环节, T1 事务内直接完成全部退费
            finalizeRefundLocal(ctx);
        }
        return ctx;
    }

    /** T3: 2208 成功后退费本地终态落账(独立事务) */
    private void finalizeRefund(RefundCtx ctx) {
        txTemplate.executeWithoutResult(status -> finalizeRefundLocal(ctx));
    }

    /** 退费本地终态落账(自费单在 T1 / 医保单在 T3 事务内): 原单条件更新 1->2 + yb 3->4, 退费单 0->1, 就诊 1->2, 发票红冲, 医嘱复位 */
    private void finalizeRefundLocal(RefundCtx ctx) {
        HisChargeBill origin = ctx.origin;
        // 原单标记已退费(条件更新: 仅已收费可置已退费, 并发/重复提交时第二次受影响行数为0);
        // 医保退费同时 yb_status 3->4(已撤销)
        int originUpdated = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                .set(HisChargeBill::getStatus, 2)
                .set(ctx.hasSetl, HisChargeBill::getYbStatus, 4)
                .eq(HisChargeBill::getId, origin.getId())
                .eq(HisChargeBill::getStatus, 1)
                .eq(ctx.hasSetl, HisChargeBill::getYbStatus, 3));
        if (originUpdated != 1) {
            log.error("原单退费状态条件更新失败: billId={}, affected={}", origin.getId(), originUpdated);
            throw new BizException("收费单状态已变化, 请刷新后重试");
        }
        // 退费单转已退费(医保退费中间态 0->1; 自费单插入即 status=1)
        if (ctx.hasSetl) {
            int refundUpdated = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getStatus, 1)
                    .eq(HisChargeBill::getId, ctx.refundBill.getId())
                    .eq(HisChargeBill::getStatus, 0));
            if (refundUpdated != 1) {
                throw new BizException("退费单状态已变化, 请刷新后重试");
            }
        }
        // 全额退费: 原单明细退回数量置满(与部分退费 refunded_qty 口径一致, 供收据/对账展示)
        billItemMapper.update(null, Wrappers.<HisChargeBillItem>lambdaUpdate()
                .setSql("refunded_qty = IFNULL(qty, 0)")
                .eq(HisChargeBillItem::getBillId, origin.getId()));

        // 发票联动: 全额退费原单已开票时自动红冲(负数冲销记录保留票据轨迹), 并清空原单发票号展示;
        // 历史发票状态异常不阻塞退费主流程(退费/医保撤销已完成), 仅记日志
        try {
            invoiceService.redFlushForBill(origin.getId(), "全额退费冲销: 原单" + origin.getBillNo());
        } catch (Exception e) {
            log.warn("退费发票红冲失败(不阻塞退费): billNo={}, 原因: {}", origin.getBillNo(), e.getMessage());
        }

        // 回写就诊收费状态
        if (origin.getVisitId() != null) {
            jdbcTemplate.update("UPDATE his_visit SET charge_status = 2 WHERE id = ? AND tenant_id = ? AND charge_status = 1 AND deleted = 0",
                    origin.getVisitId(), tenantId());
            // 医嘱下游回滚: 未产生执行痕迹的医嘱复位 paid_flag(已执行单留痕不回滚, 避免费用已发生仍可被工作台执行)
            int reset = jdbcTemplate.update("UPDATE his_order SET paid_flag = 0, update_by = ?, update_time = NOW()"
                    + " WHERE visit_id = ? AND tenant_id = ? AND deleted = 0 AND status > 0"
                    + " AND IFNULL(exec_status, 0) = 0 AND IFNULL(paid_flag, 0) = 1",
                    UserContext.username(), origin.getVisitId(), tenantId());
            if (reset > 0) {
                log.info("全额退费回滚医嘱收费标志: visitId={}, 回滚{}条", origin.getVisitId(), reset);
            }
        }

        log.info("退费完成: 原单={}, 退费单={}, visitId={}", origin.getBillNo(), ctx.refundBill.getBillNo(), origin.getVisitId());
    }

    /** 2208 明确拒绝: 原单回已结算(yb_status 3->2), 退费单作废, 抛错让用户重试 */
    private void refundFailed(RefundCtx ctx, RefundOutcome o) {
        txTemplate.executeWithoutResult(status -> {
            billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getYbStatus, 2)
                    .eq(HisChargeBill::getId, ctx.origin.getId())
                    .eq(HisChargeBill::getStatus, 1)
                    .eq(HisChargeBill::getYbStatus, 3));
            billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getStatus, -1)
                    .eq(HisChargeBill::getId, ctx.refundBill.getId())
                    .eq(HisChargeBill::getStatus, 0));
        });
        throw new BizException("医保退费失败: " + o.errMsg);
    }

    /** 2208 UNKNOWN: 补偿任务独立事务落库, 原单留撤销中间态挂起, 由 CompTaskSweeper 收敛 */
    private void createRefundCompTaskAndThrow(RefundCtx ctx, RefundOutcome o) {
        txTemplate.executeWithoutResult(status -> {
            HisCompTask task = new HisCompTask();
            task.setBizType(HisCompTask.BIZ_REFUND);
            task.setRefId(ctx.origin.getId());
            task.setAction(HisCompTask.ACT_RESOLVE_UNKNOWN);
            task.setTxnLogId(o.txnLogId);
            task.setStatus(HisCompTask.ST_PENDING);
            task.setAttempts(0);
            task.setNextRun(LocalDateTime.now());
            task.setMemo(o.errMsg);
            compTaskMapper.insert(task);
        });
        throw new BizException("医保结算撤销结果未知(网络超时/异常), 系统已登记补偿任务自动核对, 请稍后刷新查看; 详情: " + o.errMsg);
    }

    /**
     * 部分退费(批次4 A8 全撤重结两阶段化): 按明细行退指定数量(支持多次部分退), 生成部分退费单(billType=2, originBillId 指向原单),
     * 原明细累计 refundedQty。自费单: T1 事务内完成全部退费(与批次1行为一致)。
     * 医保单: T1 行锁校验 + 建部分退费单中间态(status=0) + 原单 yb_status=3 撤销中 ->
     *   T2 全撤重结链(①2208 撤原结算 -> ②2205"0000"全撤未结算明细 -> ③2204 重传剩余明细(新 chrg_bchno)
     *       -> ④2206 预结算 -> ⑤2207 重结算取新 setl_id; 剩余=0 时②后直接终态不再重结算) ->
     *   T3 终态: 原单 1->2/yb 3->4、部分退费单 0->1、剩余>0 生成重结收费单(新 setl_id, 可作后续部分退费新原单)、
     *       全部退完则就诊 charge_status 1->2; UNKNOWN/②-⑤失败落补偿任务由 CompTaskSweeper 收敛。
     */
    public HisChargeBill partialRefund(PartialRefundReq req) {
        PartialCtx ctx = txTemplate.execute(status -> buildPartialStage1(req));
        if (!ctx.hasSetl) {
            return ctx.refundBill;
        }
        // T2: 全撤重结(2208 -> 2205"0000" -> 2204 剩余明细 -> 2206 -> 2207)
        PartialOutcome outcome = rebuildPartialChain(ctx, false);
        // T3: 终态
        if (outcome.success) {
            try {
                finalizePartialRebuild(ctx, outcome);
            } catch (Exception e) {
                log.error("部分退费本地终态落账失败, 转补偿任务: 原单={}, 原因: {}", ctx.origin.getBillNo(), e.getMessage());
                createPartialCompTaskAndThrow(ctx, outcome,
                        "医保部分退费已完成但本地落账异常, 系统已登记补偿任务自动核对, 请稍后刷新查看");
            }
        } else if (outcome.unknown) {
            createPartialCompTaskAndThrow(ctx, outcome,
                    "医保部分退费结果未知(网络超时/异常), 系统已登记补偿任务自动核对, 请稍后刷新查看; 详情: " + outcome.errMsg);
        } else if (outcome.stage <= 1) {
            // ①2208 明确拒绝(平台侧未发生任何变更): 本地直接复位, 用户可重新发起
            partialFailed(ctx, outcome);
        } else {
            // ②-⑤ 失败(平台侧已撤销结算/已撤明细): 留中间态交补偿任务重试, 不盲目复位避免平台-院内不一致
            createPartialCompTaskAndThrow(ctx, outcome,
                    "医保部分退费第" + outcome.stage + "步失败, 系统已登记补偿任务自动重试, 请稍后刷新查看; 详情: " + outcome.errMsg);
        }
        return ctx.refundBill;
    }

    /** T1: 校验原单(行锁/撤销中/机构/日结/退药执行守卫) + 校验退费行与金额 + 建部分退费单中间态 + 原单置撤销中 */
    private PartialCtx buildPartialStage1(PartialRefundReq req) {
        if (req == null || req.getBillId() == null) {
            throw new BizException(400, "收费单ID不能为空");
        }
        if (CollectionUtils.isEmpty(req.getItems())) {
            throw new BizException(400, "退费明细不能为空");
        }
        // 1. 校验原单: 行锁串行化(与全额退费共用同一锁点, 并发部分退在此排队后按最新 refunded_qty 校验)
        HisChargeBill origin = billMapper.selectOne(Wrappers.<HisChargeBill>lambdaQuery()
                .eq(HisChargeBill::getId, req.getBillId())
                .last("FOR UPDATE"));
        if (origin == null) {
            throw new BizException(400, "收费单不存在");
        }
        if (origin.getBillType() != null && origin.getBillType() == 2) {
            throw new BizException("退费单不能再次退费");
        }
        if (origin.getStatus() == null || origin.getStatus() != 1) {
            throw new BizException("该收费单当前状态不允许退费");
        }
        // 批次4 撤销中守卫: 医保撤销中(yb_status=3)或冲正中(9)的收费单不可并发退费
        if (origin.getYbStatus() != null && (origin.getYbStatus() == 3 || origin.getYbStatus() == 9)) {
            throw new BizException("该收费单医保结算处理中, 请稍后刷新重试");
        }
        // 2. 机构隔离: 非牵头机构用户仅可退本机构收费单(牵头机构可退全医共体)
        LoginUser lu = UserContext.get();
        if (lu != null && lu.getOrgId() != null && origin.getOrgId() != null
                && !origin.getOrgId().equals(lu.getOrgId()) && !orgAccessGuard.isLead()) {
            throw new BizException(403, "仅可退本机构收费单");
        }
        // 3. 日结锁定: 原单收费时刻已被当日日结汇总则不可退(日结后新收的票不受历史日结影响)
        if (origin.getChargeTime() != null && origin.getOrgId() != null) {
            HisDailySettle settled = dailySettleMapper.selectOne(Wrappers.<HisDailySettle>lambdaQuery()
                    .eq(HisDailySettle::getOrgId, origin.getOrgId())
                    .eq(HisDailySettle::getSettleDate, origin.getChargeTime().toLocalDate())
                    .eq(HisDailySettle::getStatus, 1)
                    .last("LIMIT 1"));
            if (settled != null && (settled.getSettleTime() == null
                    || !origin.getChargeTime().isAfter(settled.getSettleTime()))) {
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
        // 退药/执行守卫(批次4): 本次退费涉及的明细中, 已发药/调配中药品须先退药, 已执行项目不可退
        assertRefundableItems(origin.getId(), lineQtyDelta.keySet());

        // 医保单: 撤销中中间态 + 2208 撤销信息预取
        boolean hasSetl = StringUtils.hasText(origin.getSetlId()) && origin.getVisitId() != null;
        Map<String, Object> visitYb = null;
        String mdtrtId = null;
        String psnNo = null;
        String insuplcAdmdvs = null;
        if (hasSetl) {
            visitYb = loadVisitYbInfo(origin.getVisitId());
            mdtrtId = str(visitYb.get("mdtrt_id"));
            psnNo = str(visitYb.get("psn_no"));
            if (!StringUtils.hasText(mdtrtId) || !StringUtils.hasText(psnNo)) {
                throw new BizException("该收费单就诊医保信息缺失, 无法线上部分退费, 请联系管理员处理");
            }
            // 原单置撤销中(全撤重结期间冻结该单; 并发互斥由入口 yb_status 守卫完成)
            int setRevoking = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getYbStatus, 3)
                    .eq(HisChargeBill::getId, origin.getId())
                    .eq(HisChargeBill::getStatus, 1));
            if (setRevoking != 1) {
                throw new BizException("收费单状态已变化, 请刷新后重试");
            }
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

        // 6. 创建部分退费单(医保退费先落中间态 status=0, T3 转已退费)
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
        refundBill.setStatus(hasSetl ? 0 : 1);
        refundBill.setChargeBy(UserContext.username());
        refundBill.setChargeTime(LocalDateTime.now());
        String remark = "部分退费; 原单:" + origin.getBillNo()
                + (StringUtils.hasText(req.getReason()) ? "; 退费原因:" + req.getReason() : "");
        if (hasSetl) {
            remark += "; 医保全撤重结(2208→2205→2204→2206→2207)";
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

        // 10. 原单全部明细全额退完 -> 全撤重结链②后无需重结算, T3 直接终态; 以回读库内实际已退数量判定
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

        // 剩余明细快照(全撤重结 ③ 重传用): 剩余数量与按比例折算金额
        List<Map<String, Object>> remainingItems = new ArrayList<>();
        BigDecimal remainingTotal = BigDecimal.ZERO;
        if (hasSetl) {
            for (HisChargeBillItem it : latestItems) {
                BigDecimal qty = nvl(it.getQty());
                BigDecimal remain = qty.subtract(nvl(it.getRefundedQty()));
                if (remain.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal amt = qty.compareTo(BigDecimal.ZERO) > 0
                        ? nvl(it.getAmount()).multiply(remain).divide(qty, 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("refType", it.getRefType());
                m.put("refId", it.getRefId());
                m.put("itemCode", it.getItemCode());
                m.put("itemName", it.getItemName());
                m.put("medListCodg", it.getMedListCodg());
                m.put("qty", remain);
                m.put("price", it.getPrice());
                m.put("amount", amt);
                m.put("itemType", it.getItemType());
                remainingItems.add(m);
                remainingTotal = remainingTotal.add(amt);
            }
        }

        String rebuildCertType = null;
        String rebuildCertNo = null;
        if (hasSetl) {
            rebuildCertType = StringUtils.hasText(str(visitYb.get("mdtrt_cert_type")))
                    ? str(visitYb.get("mdtrt_cert_type")) : "02";
            rebuildCertNo = str(visitYb.get("mdtrt_cert_no"));
        }
        PartialCtx ctx = new PartialCtx(origin, refundBill, hasSetl, mdtrtId, psnNo, insuplcAdmdvs,
                rebuildCertType, rebuildCertNo, visitYb, remainingItems, remainingTotal,
                hasSetl ? generateBillNo("SF") : null, allRefunded, lineQtyDelta, origin.getVisitId());
        if (!hasSetl && allRefunded) {
            // 自费单全部退完: T1 事务内直接终态(与批次1行为一致)
            // 条件更新: 仅已收费可置已退费(行锁内本应必成, 守卫并发异常)
            int originUpdated = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getStatus, 2)
                    .eq(HisChargeBill::getId, origin.getId())
                    .eq(HisChargeBill::getStatus, 1));
            if (originUpdated != 1) {
                log.error("原单退费状态条件更新失败: billId={}, affected={}", origin.getId(), originUpdated);
                throw new BizException("收费单状态已变化, 请刷新后重试");
            }
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

        log.info("部分退费T1完成: 原单={}, 退费单={}, 退费金额={}, 原单{}",
                origin.getBillNo(), refundBill.getBillNo(), refundTotal, allRefunded ? "已全部退完" : "仍有剩余可退项");
        return ctx;
    }

    /** T2: 全撤重结链; 结果含 stage(失败/未知发生在第几步: 1=2208 2=2205 3=2204 4=2206 5=2207) */
    private PartialOutcome rebuildPartialChain(PartialCtx ctx, boolean skipCancel) {
        String mdtrtId = ctx.mdtrtId;
        String psnNo = ctx.psnNo;
        // ① 2208 撤销原结算(补偿重放 skipCancel=true 时已受理, 跳过)
        if (!skipCancel) {
            SetlCancelReq cancelReq = new SetlCancelReq();
            cancelReq.setSetlId(ctx.origin.getSetlId());
            cancelReq.setMdtrtId(mdtrtId);
            cancelReq.setPsnNo(psnNo);
            YbResponse cancelResp = outpatientService.cancelSettlement(cancelReq, ctx.insuplcAdmdvs);
            if (cancelResp == null) {
                return PartialOutcome.unknown(null, 1, "撤销原结算(2208)无响应");
            }
            if (cancelResp.isUnknown()) {
                return PartialOutcome.unknown(findTxnLogId(cancelResp), 1, "撤销原结算(2208)网络异常, 结果未知");
            }
            if (!cancelResp.isSuccess()) {
                return PartialOutcome.fail(1, "撤销原结算(2208)失败: " + cancelResp.getErrMsg());
            }
        }
        // ② 2205 全撤未结算明细(chrg_bchno="0000"; 规范语义幂等, 重试安全)
        FeeDetailRevokeReq revokeReq = new FeeDetailRevokeReq();
        revokeReq.setMdtrtId(mdtrtId);
        revokeReq.setChrgBchno("0000");
        revokeReq.setPsnNo(psnNo);
        revokeReq.setExpContent("");
        YbResponse revokeResp = outpatientService.revokeFeeDetail(revokeReq, ctx.insuplcAdmdvs);
        if (revokeResp == null) {
            return PartialOutcome.unknown(null, 2, "撤销费用明细(2205)无响应");
        }
        if (revokeResp.isUnknown()) {
            return PartialOutcome.unknown(findTxnLogId(revokeResp), 2, "撤销费用明细(2205)网络异常, 结果未知");
        }
        if (!revokeResp.isSuccess()) {
            return PartialOutcome.fail(2, "撤销费用明细(2205)失败: " + revokeResp.getErrMsg());
        }
        // 剩余=0: 已全部退完, 无需重结算, 直接终态
        if (ctx.allRefunded) {
            PartialOutcome done = PartialOutcome.success();
            done.allRefunded = true;
            return done;
        }
        // ③ 2204 重传剩余明细(新 chrg_bchno=重结单号; 已退明细不再传)
        List<FeeDetailReq> feeDetails = buildFeeDetails(ctx.remainingItems, mdtrtId, psnNo, ctx.rebuildBchno, ctx.visit);
        if (CollectionUtils.isEmpty(feeDetails)) {
            return PartialOutcome.fail(3, "剩余明细均无医保目录编码, 无法重结算, 请改为自费退费处理");
        }
        YbResponse feeResp = outpatientService.uploadFeeDetail(feeDetails, ctx.insuplcAdmdvs);
        if (feeResp == null) {
            return PartialOutcome.unknown(null, 3, "重传费用明细(2204)无响应");
        }
        if (feeResp.isUnknown()) {
            return PartialOutcome.unknown(findTxnLogId(feeResp), 3, "重传费用明细(2204)网络异常, 结果未知");
        }
        if (!feeResp.isSuccess()) {
            return PartialOutcome.fail(3, "重传费用明细(2204)失败: " + feeResp.getErrMsg());
        }
        // ④ 2206 预结算
        SettlementReq setlReq = new SettlementReq();
        setlReq.setPsnNo(psnNo);
        setlReq.setMdtrtId(mdtrtId);
        setlReq.setInsutype(str(ctx.visit.get("insutype")));
        String medType = str(ctx.visit.get("med_type"));
        setlReq.setMedType(StringUtils.hasText(medType) ? medType : "11");
        setlReq.setMedfeeSumamt(ctx.remainingTotal);
        setlReq.setPsnSetlway("01");
        setlReq.setMdtrtCertType(ctx.mdtrtCertType);
        setlReq.setMdtrtCertNo(ctx.mdtrtCertNo);
        setlReq.setChrgBchno(ctx.rebuildBchno);
        YbResponse preResp = outpatientService.preSettlement(setlReq, ctx.insuplcAdmdvs);
        if (preResp == null) {
            return PartialOutcome.unknown(null, 4, "重结算预结算(2206)无响应");
        }
        if (preResp.isUnknown()) {
            return PartialOutcome.unknown(findTxnLogId(preResp), 4, "重结算预结算(2206)网络异常, 结果未知");
        }
        if (!preResp.isSuccess()) {
            return PartialOutcome.fail(4, "重结算预结算(2206)失败: " + preResp.getErrMsg());
        }
        JSONObject preSetlinfo = preResp.getOutputNode("setlinfo");
        setlReq.setFulamtOwnpayAmt(firstNonNull(bd(preSetlinfo, "fulamt_ownpay_amt"), BigDecimal.ZERO));
        setlReq.setOverlmtSelfpay(firstNonNull(bd(preSetlinfo, "overlmt_selfpay"), BigDecimal.ZERO));
        setlReq.setPreselfpayAmt(firstNonNull(bd(preSetlinfo, "preselfpay_amt"), BigDecimal.ZERO));
        setlReq.setInscpScpAmt(firstNonNull(bd(preSetlinfo, "inscp_scp_amt"), BigDecimal.ZERO));
        BigDecimal preAcct = bd(preSetlinfo, "acct_pay");
        setlReq.setAcctUsedFlag(preAcct != null && preAcct.compareTo(BigDecimal.ZERO) > 0 ? "1" : "0");
        // ⑤ 2207 重结算(批次5 M2 通道A: 剩余已发药追溯码挂节点随重结算报送)
        TraceCodeService.SettlementTrace stTrace = traceCodeService.buildSettlementNodes(ctx.visitId, ctx.remainingItems);
        YbResponse setlResp = outpatientService.settlement(setlReq, ctx.insuplcAdmdvs, stTrace.nodes());
        if (setlResp == null) {
            traceCodeService.finalizeSettlementUpload(stTrace, false, true, mdtrtId, null, null);
            return PartialOutcome.unknown(null, 5, "重结算(2207)无响应");
        }
        if (setlResp.isUnknown()) {
            traceCodeService.finalizeSettlementUpload(stTrace, false, true, mdtrtId, setlResp.getInfRefmsgid(), null);
            return PartialOutcome.unknown(findTxnLogId(setlResp), 5, "重结算(2207)网络异常, 结果未知");
        }
        if (!setlResp.isSuccess()) {
            traceCodeService.finalizeSettlementUpload(stTrace, false, false, mdtrtId, null, "重结算(2207)被拒: " + setlResp.getErrMsg());
            return PartialOutcome.fail(5, "重结算(2207)失败: " + setlResp.getErrMsg());
        }
        JSONObject setlinfo = setlResp.getOutputNode("setlinfo");
        String newSetlId = setlinfo == null ? null : setlinfo.getString("setl_id");
        if (!StringUtils.hasText(newSetlId)) {
            traceCodeService.finalizeSettlementUpload(stTrace, false, false, mdtrtId, null, "重结算成功但缺setl_id(平台侧异常)");
            return PartialOutcome.fail(5, "重结算(2207)返回缺少结算ID(setl_id), 请核对医保平台结算状态后处理");
        }
        // 重结算实际成功: 追溯码收口已报送
        traceCodeService.finalizeSettlementUpload(stTrace, true, false, mdtrtId, setlResp.getInfRefmsgid(), null);
        BigDecimal[] split = splitAmounts(setlinfo, ctx.remainingTotal);
        PartialOutcome o = PartialOutcome.success();
        o.setlId = newSetlId;
        o.fundPay = split[0];
        o.acctPay = split[1];
        o.selfPay = split[2];
        o.txnLogId = findTxnLogId(setlResp);
        return o;
    }

    /** T3: 部分退费全撤重结终态(原单 1->2/yb 3->4, 退费单 0->1, 剩余>0 建重结收费单, 全部退完回写就诊) */
    private void finalizePartialRebuild(PartialCtx ctx, PartialOutcome o) {
        txTemplate.executeWithoutResult(status -> {
            long tid = tenantId();
            // 原单: 已退费 + 已撤销
            int originUpdated = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getStatus, 2)
                    .set(HisChargeBill::getYbStatus, 4)
                    .eq(HisChargeBill::getId, ctx.origin.getId())
                    .eq(HisChargeBill::getStatus, 1)
                    .eq(HisChargeBill::getYbStatus, 3));
            if (originUpdated != 1) {
                log.error("部分退费原单终态条件更新失败: billId={}, affected={}", ctx.origin.getId(), originUpdated);
                throw new BizException("原收费单状态已变化, 请刷新后重试");
            }
            // 部分退费单 0->1
            int refundUpdated = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getStatus, 1)
                    .eq(HisChargeBill::getId, ctx.refundBill.getId())
                    .eq(HisChargeBill::getStatus, 0));
            if (refundUpdated != 1) {
                throw new BizException("退费单状态已变化, 请刷新后重试");
            }
            // 原单发票红冲(全撤重结后原票金额已不成立)
            try {
                invoiceService.redFlushForBill(ctx.origin.getId(), "部分退费全撤重结冲销: 原单" + ctx.origin.getBillNo());
            } catch (Exception e) {
                log.warn("部分退费原单发票红冲失败(不阻塞退费): billNo={}, 原因: {}", ctx.origin.getBillNo(), e.getMessage());
            }
            if (!o.allRefunded) {
                // 重结收费单(剩余金额, 新结算ID; 可作后续部分退费的新原单)
                HisChargeBill rebill = new HisChargeBill();
                rebill.setOrgId(ctx.origin.getOrgId());
                rebill.setBillNo(ctx.rebuildBchno);
                rebill.setVisitId(ctx.origin.getVisitId());
                rebill.setRegistrationId(ctx.origin.getRegistrationId());
                rebill.setPatientId(ctx.origin.getPatientId());
                rebill.setPatientName(ctx.origin.getPatientName());
                rebill.setBillType(1);
                rebill.setTotalAmount(ctx.remainingTotal);
                rebill.setFundPay(o.fundPay);
                rebill.setAcctPay(o.acctPay);
                rebill.setSelfPay(o.selfPay);
                rebill.setCashPay(o.selfPay);
                rebill.setPayMethod("INSURANCE");
                rebill.setSetlId(o.setlId);
                rebill.setStatus(1);
                rebill.setYbStatus(2);
                rebill.setChargeBy(UserContext.username());
                rebill.setChargeTime(LocalDateTime.now());
                rebill.setRemark("部分退费重结; 原单:" + ctx.origin.getBillNo());
                billMapper.insert(rebill);
                for (Map<String, Object> it : ctx.remainingItems) {
                    HisChargeBillItem bi = new HisChargeBillItem();
                    bi.setBillId(rebill.getId());
                    bi.setItemType(toInt(it.get("itemType")));
                    bi.setRefType(str(it.get("refType")));
                    bi.setRefId(toLong(it.get("refId")));
                    bi.setItemCode(str(it.get("itemCode")));
                    bi.setItemName(str(it.get("itemName")));
                    bi.setQty(toBd(it.get("qty")));
                    bi.setPrice(toBd(it.get("price")));
                    bi.setAmount(toBd(it.get("amount")));
                    bi.setMedListCodg(str(it.get("medListCodg")));
                    billItemMapper.insert(bi);
                }
                BigDecimal ins = o.fundPay.add(o.acctPay);
                if (ins.compareTo(BigDecimal.ZERO) > 0) {
                    savePaymentDetail(rebill.getId(), "INSURANCE", ins, o.setlId);
                }
                if (o.selfPay.compareTo(BigDecimal.ZERO) > 0) {
                    savePaymentDetail(rebill.getId(), "CASH", o.selfPay, null);
                }
                try {
                    HisInvoice inv = invoiceService.createInvoice(rebill.getId());
                    rebill.setInvoiceNo(inv.getInvoiceNo());
                    billMapper.updateById(rebill);
                } catch (Exception e) {
                    log.warn("重结单发票分配失败(不阻塞): billNo={}, 原因: {}", ctx.rebuildBchno, e.getMessage());
                }
                // 就诊保持已收费(剩余金额仍有效)
            } else if (ctx.origin.getVisitId() != null) {
                // 全部退完: 就诊置已退费 + 未执行医嘱复位收费标志(与全额退费同口径)
                jdbcTemplate.update("UPDATE his_visit SET charge_status = 2"
                        + " WHERE id = ? AND tenant_id = ? AND charge_status = 1 AND deleted = 0",
                        ctx.origin.getVisitId(), tid);
                jdbcTemplate.update("UPDATE his_order SET paid_flag = 0, update_time = NOW()"
                        + " WHERE visit_id = ? AND tenant_id = ? AND deleted = 0 AND status > 0"
                        + " AND IFNULL(exec_status, 0) = 0 AND IFNULL(paid_flag, 0) = 1",
                        ctx.origin.getVisitId(), tid);
            }
            log.info("部分退费全撤重结完成: 原单={}, 退费单={}, 重结单={}, 新setlId={}",
                    ctx.origin.getBillNo(), ctx.refundBill.getBillNo(), ctx.rebuildBchno, o.setlId);
        });
    }

    /** ①2208 明确拒绝: 平台侧未发生任何变更, 原单回已结算(yb 3->2)/退费单作废/退量回滚, 抛错重试 */
    private void partialFailed(PartialCtx ctx, PartialOutcome o) {
        txTemplate.executeWithoutResult(status -> {
            billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getYbStatus, 2)
                    .eq(HisChargeBill::getId, ctx.origin.getId())
                    .eq(HisChargeBill::getStatus, 1)
                    .eq(HisChargeBill::getYbStatus, 3));
            billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                    .set(HisChargeBill::getStatus, -1)
                    .eq(HisChargeBill::getId, ctx.refundBill.getId())
                    .eq(HisChargeBill::getStatus, 0));
            rollbackRefundQty(ctx);
        });
        throw new BizException("医保部分退费失败: " + o.errMsg);
    }

    /** 部分退费补偿任务独立事务落库, 原单留撤销中间态挂起, 由 CompTaskSweeper 收敛 */
    private void createPartialCompTaskAndThrow(PartialCtx ctx, PartialOutcome o, String userMsg) {
        txTemplate.executeWithoutResult(status -> {
            HisCompTask task = new HisCompTask();
            task.setBizType(HisCompTask.BIZ_PARTIAL_REFUND);
            task.setRefId(ctx.origin.getId());
            task.setAction(HisCompTask.ACT_RESOLVE_UNKNOWN);
            task.setTxnLogId(o.txnLogId);
            task.setStatus(HisCompTask.ST_PENDING);
            task.setAttempts(0);
            task.setNextRun(LocalDateTime.now());
            task.setMemo("stage=" + o.stage + "; " + o.errMsg);
            compTaskMapper.insert(task);
        });
        throw new BizException(userMsg);
    }

    /** 回滚中间态退费单对应的退款数量增量(复位路径用, 保证原单明细可退数量复原) */
    private void rollbackRefundQty(PartialCtx ctx) {
        for (Map.Entry<Long, BigDecimal> e : ctx.lineQtyDelta.entrySet()) {
            billItemMapper.update(null, Wrappers.<HisChargeBillItem>lambdaUpdate()
                    .setSql("refunded_qty = IFNULL(refunded_qty, 0) - " + e.getValue().toPlainString())
                    .eq(HisChargeBillItem::getId, e.getKey())
                    .apply("IFNULL(refunded_qty, 0) >= {0}", e.getValue()));
        }
    }

    /** 按(来源类型+来源ID+项目编码)顺序匹配, 重建 原单明细ID -> 退费数量 映射(补偿重放时退费单行与原始行对齐) */
    private Map<Long, BigDecimal> matchRefundDeltas(List<HisChargeBillItem> originItems, List<HisChargeBillItem> refundItems) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        int ri = 0;
        for (HisChargeBillItem oi : originItems) {
            if (ri >= refundItems.size()) {
                break;
            }
            HisChargeBillItem r = refundItems.get(ri);
            if (sameLine(r, oi)) {
                map.put(oi.getId(), nvl(r.getQty()));
                ri++;
            }
        }
        return map;
    }

    private boolean sameLine(HisChargeBillItem a, HisChargeBillItem b) {
        return Objects.equals(a.getRefType(), b.getRefType())
                && Objects.equals(a.getRefId(), b.getRefId())
                && Objects.equals(a.getItemCode(), b.getItemCode());
    }

    /** 补偿重放: 从库中重建全撤重结上下文(原单 + 中间态退费单 + 剩余明细), 供 CompTaskSweeper 驱动收敛 */
    private PartialCtx loadPartialCtx(Long billId) {
        HisChargeBill origin = billMapper.selectById(billId);
        if (origin == null || origin.getVisitId() == null
                || origin.getStatus() == null || origin.getStatus() != 1) {
            return null;
        }
        // 中间态部分退费单(取最近一张 status=0)
        HisChargeBill refundBill = billMapper.selectOne(Wrappers.<HisChargeBill>lambdaQuery()
                .eq(HisChargeBill::getOriginBillId, billId)
                .eq(HisChargeBill::getBillType, 2)
                .eq(HisChargeBill::getStatus, 0)
                .orderByDesc(HisChargeBill::getId)
                .last("LIMIT 1"));
        if (refundBill == null) {
            return null;
        }
        Map<String, Object> yb = loadVisitYbInfo(origin.getVisitId());
        String mdtrtId = str(yb.get("mdtrt_id"));
        String psnNo = str(yb.get("psn_no"));
        if (!StringUtils.hasText(mdtrtId) || !StringUtils.hasText(psnNo)) {
            return null;
        }
        List<HisChargeBillItem> originItems = billItemMapper.selectList(
                Wrappers.<HisChargeBillItem>lambdaQuery()
                        .eq(HisChargeBillItem::getBillId, origin.getId())
                        .orderByAsc(HisChargeBillItem::getId));
        List<HisChargeBillItem> refundItems = billItemMapper.selectList(
                Wrappers.<HisChargeBillItem>lambdaQuery()
                        .eq(HisChargeBillItem::getBillId, refundBill.getId())
                        .orderByAsc(HisChargeBillItem::getId));
        // 剩余明细(回读库内实际已退数量)
        List<Map<String, Object>> remaining = new ArrayList<>();
        BigDecimal remainingTotal = BigDecimal.ZERO;
        for (HisChargeBillItem it : originItems) {
            BigDecimal qty = nvl(it.getQty());
            BigDecimal remain = qty.subtract(nvl(it.getRefundedQty()));
            if (remain.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal amt = qty.compareTo(BigDecimal.ZERO) > 0
                    ? nvl(it.getAmount()).multiply(remain).divide(qty, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("refType", it.getRefType());
            m.put("refId", it.getRefId());
            m.put("itemCode", it.getItemCode());
            m.put("itemName", it.getItemName());
            m.put("medListCodg", it.getMedListCodg());
            m.put("qty", remain);
            m.put("price", it.getPrice());
            m.put("amount", amt);
            m.put("itemType", it.getItemType());
            remaining.add(m);
            remainingTotal = remainingTotal.add(amt);
        }
        boolean allRefunded = remaining.isEmpty() && !originItems.isEmpty();
        String certType = StringUtils.hasText(str(yb.get("mdtrt_cert_type")))
                ? str(yb.get("mdtrt_cert_type")) : "02";
        return new PartialCtx(origin, refundBill, true, mdtrtId, psnNo, str(yb.get("insuplc_admdvs")),
                certType, str(yb.get("mdtrt_cert_no")), yb, remaining, remainingTotal,
                generateBillNo("SF"), allRefunded, matchRefundDeltas(originItems, refundItems), origin.getVisitId());
    }

    /**
     * 补偿收敛入口(CompTaskSweeper 驱动): UNKNOWN/②-⑤失败的部分退费(全撤重结)终态回填或复位。
     * cancelLanded=false(平台未受理2208): 复位(原单 yb 3->2, 退费单作废, 退量回滚), 可重新退费;
     * cancelLanded=true 且 landedSetlId 非空(2207已落): 直接补 T3 终态;
     * cancelLanded=true 且 landedSetlId 为空: 从 ② 全撤重放(2205 幂等)后补终态。
     */
    public String resolveUnknownPartial(Long tenantId, Long billId, boolean cancelLanded, String landedSetlId) {
        if (!ybConfigResolver.resolve().isMockEnabled()) {
            throw new BizException("真实模式全撤重结结果未知, 需按 3202 人工核对后处理, 不自动收敛");
        }
        TenantContext.set(tenantId);
        try {
            PartialCtx ctx = loadPartialCtx(billId);
            if (ctx == null) {
                return "全撤重结中间态已不存在(可能已人工处理)";
            }
            if (!cancelLanded) {
                return txTemplate.execute(status -> {
                    billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                            .set(HisChargeBill::getYbStatus, 2)
                            .eq(HisChargeBill::getId, billId)
                            .eq(HisChargeBill::getStatus, 1)
                            .eq(HisChargeBill::getYbStatus, 3));
                    billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                            .set(HisChargeBill::getStatus, -1)
                            .eq(HisChargeBill::getId, ctx.refundBill.getId())
                            .eq(HisChargeBill::getStatus, 0));
                    rollbackRefundQty(ctx);
                    return "平台未受理撤销, 已复位原单(回已结算)/退费单作废/退量回滚, 可重新退费";
                });
            }
            if (StringUtils.hasText(landedSetlId)) {
                BigDecimal[] split = splitAmounts(null, ctx.remainingTotal);
                PartialOutcome o = PartialOutcome.success();
                o.setlId = landedSetlId;
                o.fundPay = split[0];
                o.acctPay = split[1];
                o.selfPay = split[2];
                o.allRefunded = ctx.allRefunded;
                finalizePartialRebuild(ctx, o);
                return "平台重结算已受理, 已补录终态(setl_id=" + landedSetlId + ")";
            }
            PartialOutcome outcome = rebuildPartialChain(ctx, true);
            if (outcome.success) {
                finalizePartialRebuild(ctx, outcome);
                return "重放全撤重结成功, 已补录终态";
            }
            throw new BizException("重放全撤重结失败(" + outcome.errMsg + "), 任务将退避重试");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 补偿收敛入口(CompTaskSweeper 驱动): UNKNOWN 全额退费(2208)终态回填或复位。
     * cancelled=true(平台侧已撤销): 补 T3 终态(原单 1->2/yb 3->4, 退费单 0->1, 就诊 1->2, 红冲);
     * cancelled=false(平台未撤销): 复位(原单 yb 3->2 回已结算, 退费单作废), 可重新发起退费。
     */
    public String resolveUnknownRefund(Long tenantId, Long billId, boolean cancelled) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, visit_id, bill_no, setl_id FROM his_charge_bill"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 AND yb_status = 3 AND status = 1",
                billId, tenantId);
        if (rows.isEmpty()) {
            return "原单撤销中间态已不存在(可能已人工处理)";
        }
        Long visitId = toLong(rows.get(0).get("visit_id"));
        List<Long> refundBills = jdbcTemplate.queryForList(
                "SELECT id FROM his_charge_bill WHERE origin_bill_id = ? AND tenant_id = ? AND deleted = 0"
                        + " AND bill_type = 2 AND status = 0",
                Long.class, billId, tenantId);
        TenantContext.set(tenantId);
        try {
            return txTemplate.execute(status -> {
                if (!cancelled) {
                    billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                            .set(HisChargeBill::getYbStatus, 2)
                            .eq(HisChargeBill::getId, billId)
                            .eq(HisChargeBill::getStatus, 1)
                            .eq(HisChargeBill::getYbStatus, 3));
                    for (Long rb : refundBills) {
                        billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                                .set(HisChargeBill::getStatus, -1)
                                .eq(HisChargeBill::getId, rb)
                                .eq(HisChargeBill::getStatus, 0));
                    }
                    return "平台未撤销, 已复位原单(回已结算)/退费单作废, 可重新退费";
                }
                int updated = billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                        .set(HisChargeBill::getStatus, 2)
                        .set(HisChargeBill::getYbStatus, 4)
                        .eq(HisChargeBill::getId, billId)
                        .eq(HisChargeBill::getStatus, 1)
                        .eq(HisChargeBill::getYbStatus, 3));
                if (updated != 1) {
                    return "原单状态已变化, 未回填";
                }
                for (Long rb : refundBills) {
                    billMapper.update(null, Wrappers.<HisChargeBill>lambdaUpdate()
                            .set(HisChargeBill::getStatus, 1)
                            .eq(HisChargeBill::getId, rb)
                            .eq(HisChargeBill::getStatus, 0));
                }
                billItemMapper.update(null, Wrappers.<HisChargeBillItem>lambdaUpdate()
                        .setSql("refunded_qty = IFNULL(qty, 0)")
                        .eq(HisChargeBillItem::getBillId, billId));
                try {
                    invoiceService.redFlushForBill(billId, "补偿回填全额退费冲销: 原单" + str(rows.get(0).get("bill_no")));
                } catch (Exception e) {
                    log.warn("补偿退费发票红冲失败(不阻塞): billId={}, 原因: {}", billId, e.getMessage());
                }
                if (visitId != null) {
                    jdbcTemplate.update("UPDATE his_visit SET charge_status = 2"
                            + " WHERE id = ? AND tenant_id = ? AND charge_status = 1 AND deleted = 0", visitId, tenantId);
                    jdbcTemplate.update("UPDATE his_order SET paid_flag = 0, update_time = NOW()"
                            + " WHERE visit_id = ? AND tenant_id = ? AND deleted = 0 AND status > 0"
                            + " AND IFNULL(exec_status, 0) = 0 AND IFNULL(paid_flag, 0) = 1", visitId, tenantId);
                }
                return "平台已撤销, 已补录退费终态";
            });
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 退费守卫(批次4): 本次退费涉及的明细中, 已发药/调配中的药品须先退药, 已执行项目不可退。
     * selectedBillItemIds 为空=全额退费(检查全部明细)。
     */
    private void assertRefundableItems(Long billId, Set<Long> selectedBillItemIds) {
        List<HisChargeBillItem> items = billItemMapper.selectList(Wrappers.<HisChargeBillItem>lambdaQuery()
                .eq(HisChargeBillItem::getBillId, billId)
                .in(!CollectionUtils.isEmpty(selectedBillItemIds), HisChargeBillItem::getId, selectedBillItemIds));
        if (CollectionUtils.isEmpty(items)) {
            return;
        }
        long tid = tenantId();
        List<Long> rxRefs = new ArrayList<>();
        List<Long> orderRefs = new ArrayList<>();
        for (HisChargeBillItem bi : items) {
            if (bi.getRefId() == null) {
                continue;
            }
            if ("prescription_item".equals(bi.getRefType())) {
                rxRefs.add(bi.getRefId());
            } else if ("order_item".equals(bi.getRefType())) {
                orderRefs.add(bi.getRefId());
            }
        }
        // 药品: 存在未退药的已调配(1)/已发药(2)记录则拦截(退药后发药记录置3, 守卫放行)
        if (!rxRefs.isEmpty()) {
            List<Map<String, Object>> dispensed = jdbcTemplate.queryForList(
                    "SELECT bi.item_name FROM his_charge_bill_item bi"
                            + " JOIN his_prescription p ON p.id = bi.ref_id AND p.tenant_id = ? AND p.deleted = 0"
                            + " JOIN his_dispense d ON d.prescription_id = p.id AND d.tenant_id = ? AND d.deleted = 0 AND d.status IN (1, 2)"
                            + " WHERE bi.bill_id = ? AND bi.tenant_id = ? AND bi.deleted = 0"
                            + " AND bi.ref_id IN (" + joinIds(rxRefs) + ")",
                    tid, tid, billId, tid);
            if (!dispensed.isEmpty()) {
                throw new BizException("明细[" + str(dispensed.get(0).get("item_name")) + "]药品已发药/调配中, 请先办理退药后再退费");
            }
        }
        // 执行项目: 医嘱已执行(his_order.exec_status=1)或存在执行记录(his_treatment_exec.exec_status IN (1,2))则拦截
        if (!orderRefs.isEmpty()) {
            List<Map<String, Object>> executed = jdbcTemplate.queryForList(
                    "SELECT bi.item_name FROM his_charge_bill_item bi"
                            + " JOIN his_order_item oi ON oi.id = bi.ref_id AND oi.tenant_id = ? AND oi.deleted = 0"
                            + " JOIN his_order o ON o.id = oi.order_id AND o.tenant_id = ? AND o.deleted = 0"
                            + " WHERE bi.bill_id = ? AND bi.tenant_id = ? AND bi.deleted = 0"
                            + " AND bi.ref_id IN (" + joinIds(orderRefs) + ")"
                            + " AND (o.exec_status = 1 OR EXISTS (SELECT 1 FROM his_treatment_exec te"
                            + "  WHERE te.order_item_id = oi.id AND te.tenant_id = ? AND te.deleted = 0 AND te.exec_status IN (1, 2)))",
                    tid, tid, billId, tid, tid);
            if (!executed.isEmpty()) {
                throw new BizException("明细[" + str(executed.get(0).get("item_name")) + "]已执行, 不可退费");
            }
        }
    }

    /** 就诊医保信息装载(退费/全撤重结共用): 就诊核心 + 挂号凭证 + 参保地(与收费同口径) */
    private Map<String, Object> loadVisitYbInfo(Long visitId) {
        Map<String, Object> yb = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT v.mdtrt_id, v.psn_no, v.insutype, v.med_type, v.dept_code, v.dept_name, v.atddr_no, v.dr_name,"
                        + " r.mdtrt_cert_type, r.mdtrt_cert_no, pi.insuplc_admdvs"
                        + " FROM his_visit v"
                        + " LEFT JOIN his_registration r ON r.id = v.registration_id AND r.deleted = 0"
                        + " LEFT JOIN his_patient_insu pi ON pi.patient_id = v.patient_id AND pi.psn_no = v.psn_no AND pi.deleted = 0"
                        + " WHERE v.id = ? AND v.tenant_id = ? AND v.deleted = 0",
                visitId, tenantId());
        if (!rows.isEmpty()) {
            yb.putAll(rows.get(0));
        }
        return yb;
    }

    /** 结算 setlinfo 金额四分(自付/基金/现金/个账): mock 70/10/20, 真实按规范口径(自付=psn_part_amt-acct_pay) */
    private BigDecimal[] splitAmounts(JSONObject setlinfo, BigDecimal total) {
        BigDecimal fundPay;
        BigDecimal acctPay;
        BigDecimal selfPay;
        if (setlinfo == null || ybConfigResolver.resolve().isMockEnabled()) {
            // 模拟模式(或补偿回填无报文场景): 基金70% / 个账10% / 自付20%, 余数归自付保证三分守恒
            fundPay = total.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.HALF_UP);
            acctPay = total.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
            selfPay = total.subtract(fundPay).subtract(acctPay);
        } else {
            // 真实模式: 规范口径, 个人负担总金额 psn_part_amt = 个账 acct_pay + 现金 psn_cash_pay;
            // 自付(现金) = psn_part_amt - acct_pay, 不得用 psn_part_amt 直接当自付(会把个账双重计算)
            fundPay = firstNonNull(bd(setlinfo, "fund_pay_sumamt"), bd(setlinfo, "hifp_pay"));
            acctPay = firstNonNull(bd(setlinfo, "acct_pay"), bd(setlinfo, "acct_mulaid_pay"));
            BigDecimal psnPart = bd(setlinfo, "psn_part_amt");
            if (psnPart != null) {
                selfPay = acctPay != null ? psnPart.subtract(acctPay) : psnPart;
            } else {
                selfPay = bd(setlinfo, "psn_cash_pay");
            }
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
        return new BigDecimal[]{fundPay, acctPay, selfPay};
    }

    /** 拼接 IN 子句(值均为数据库回读 Long, 无注入风险) */
    private static String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
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

    /** 收据数据: 收费单 + 明细列表(含票据式样字段) + 患者信息 */
    public Map<String, Object> billReceipt(Long billId) {
        HisChargeBill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new BizException(400, "收费单不存在");
        }
        List<Map<String, Object>> items = receiptItems(billId);
        Map<String, Object> patient = new LinkedHashMap<>();
        if (bill.getVisitId() != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT v.patient_no, v.reg_no, v.mdtrt_id, v.insutype, v.dept_name, v.dr_name,"
                            + " v.patient_name, v.gender, v.age, p.id_card, v.psn_no"
                            + " FROM his_visit v LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                            + " WHERE v.id = ? AND v.tenant_id = ? AND v.deleted = 0", bill.getVisitId(), tenantId());
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
                patient.put("psnNo", r.get("psn_no"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bill", bill);
        result.put("items", items);
        result.put("patient", patient);
        return result;
    }

    /**
     * 收据/票据明细: 收费单明细行 + 票据式样字段。
     * 明细表仅快照了医保编码/比例, 报销类别所需 chrgitm_lv、归并类所需 rx_type/invoice_class
     * 按 refType/refId 回溯源单据(处方明细/医嘱明细)取得, 再走与收费前预览同一套 enrichInvoiceFields 计算。
     */
    private List<Map<String, Object>> receiptItems(Long billId) {
        List<HisChargeBillItem> rows = billItemMapper.selectList(
                Wrappers.<HisChargeBillItem>lambdaQuery()
                        .eq(HisChargeBillItem::getBillId, billId)
                        .orderByAsc(HisChargeBillItem::getId));
        List<Long> piIds = new ArrayList<>();
        List<Long> oiIds = new ArrayList<>();
        for (HisChargeBillItem r : rows) {
            if (r.getRefId() == null) {
                continue;
            }
            if ("prescription_item".equals(r.getRefType())) {
                piIds.add(r.getRefId());
            } else if ("order_item".equals(r.getRefType())) {
                oiIds.add(r.getRefId());
            }
        }
        Map<Long, Map<String, Object>> piMap = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> oiMap = new LinkedHashMap<>();
        if (!piIds.isEmpty()) {
            List<Object> piArgs = new ArrayList<>(piIds);
            piArgs.add(tenantId());
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT pi.id AS rid, pr.rx_type, LEFT(dc.chrgitm_lv, 1) AS lv"
                            + " FROM his_prescription_item pi"
                            + " LEFT JOIN his_prescription pr ON pr.id = pi.prescription_id AND pr.deleted = 0"
                            + " LEFT JOIN his_drug_catalog dc ON dc.id = pi.drug_id"
                            + " WHERE pi.id IN (" + placeholders(piIds.size()) + ") AND pi.tenant_id = ?",
                    piArgs.toArray())) {
                piMap.put(toLong(r.get("rid")), r);
            }
        }
        if (!oiIds.isEmpty()) {
            List<Object> oiArgs = new ArrayList<>(oiIds);
            oiArgs.add(tenantId());
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT oi.id AS rid, ci.invoice_class, LEFT(ci.chrgitm_lv, 1) AS lv"
                            + " FROM his_order_item oi"
                            + " LEFT JOIN his_charge_item ci ON ci.id = oi.item_id"
                            + " WHERE oi.id IN (" + placeholders(oiIds.size()) + ") AND oi.tenant_id = ?",
                    oiArgs.toArray())) {
                oiMap.put(toLong(r.get("rid")), r);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (HisChargeBillItem r : rows) {
            Map<String, Object> src;
            if ("prescription_item".equals(r.getRefType())) {
                src = piMap.get(r.getRefId());
            } else if ("order_item".equals(r.getRefType())) {
                src = oiMap.get(r.getRefId());
            } else {
                src = null;
            }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("id", r.getId());
            it.put("itemType", r.getItemType());
            it.put("refType", r.getRefType());
            it.put("refId", r.getRefId());
            it.put("itemCode", r.getItemCode());
            it.put("itemName", r.getItemName());
            it.put("spec", r.getSpec());
            it.put("qty", r.getQty());
            it.put("refundedQty", r.getRefundedQty());
            it.put("price", r.getPrice());
            it.put("amount", r.getAmount());
            it.put("medListCodg", r.getMedListCodg());
            it.put("medListName", r.getMedListName());
            it.put("ratio", r.getRatio());
            it.put("lv", src == null ? null : src.get("lv"));
            it.put("rxType", src == null ? null : src.get("rx_type"));
            it.put("invoiceClass", src == null ? null : src.get("invoice_class"));
            enrichInvoiceFields(it);
            items.add(it);
        }
        return items;
    }

    /** IN 子句占位符 */
    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    // ==================== 收费工作站今日概览 ====================

    /**
     * 今日收费概览(工作站顶部统计卡):
     * 待收费人数(完成接诊未收费, 含往日积压)、今日收费/退费笔数金额、
     * 今日现金/基金/个账净额、今日已开票数、今日是否已日结。
     */
    public Map<String, Object> dailySummary(Long orgId) {
        Long org = orgId != null ? orgId : currentOrgId();
        Map<String, Object> r = new LinkedHashMap<>();
        // 待收费: his_visit 无 org_id, 按科室所属机构过滤(与 todoPage 同口径)
        List<Object> todoArgs = new ArrayList<>();
        todoArgs.add(tenantId());
        String orgCond = "";
        if (org != null) {
            orgCond = " AND d.org_id = ?";
            todoArgs.add(org);
        }
        Integer todoCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_visit v"
                        + " LEFT JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0"
                        + " WHERE v.visit_status = 3 AND v.charge_status = 0 AND v.deleted = 0 AND v.tenant_id = ?" + orgCond,
                Integer.class, todoArgs.toArray());
        r.put("todoCount", todoCount == null ? 0 : todoCount);
        // 今日收费/退费汇总(his_charge_bill 自带 org_id)
        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT"
                        + " IFNULL(SUM(CASE WHEN bill_type = 1 THEN 1 ELSE 0 END), 0) AS charge_count,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 1 THEN total_amount ELSE 0 END), 0) AS charge_amount,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 2 THEN 1 ELSE 0 END), 0) AS refund_count,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 2 THEN total_amount ELSE 0 END), 0) AS refund_amount,"
                        + " IFNULL(SUM(CASE WHEN bill_type = 1 THEN cash_pay ELSE -cash_pay END), 0) AS cash_total"
                        + " FROM his_charge_bill"
                        + " WHERE tenant_id = ? AND deleted = 0 AND status >= 1 AND DATE(charge_time) = CURDATE()"
                        + (org == null ? "" : " AND org_id = ?"),
                org == null ? new Object[]{tenantId()} : new Object[]{tenantId(), org});
        r.put("chargeCount", agg.get("charge_count"));
        r.put("chargeAmount", agg.get("charge_amount"));
        r.put("refundCount", agg.get("refund_count"));
        r.put("refundAmount", agg.get("refund_amount"));
        r.put("cashTotal", agg.get("cash_total"));
        // 今日已开票数(正常票, 不含作废/红冲)
        Integer invoiceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_invoice"
                        + " WHERE tenant_id = ? AND deleted = 0 AND invoice_type = 'NORMAL' AND DATE(create_time) = CURDATE()"
                        + (org == null ? "" : " AND org_id = ?"),
                Integer.class,
                org == null ? new Object[]{tenantId()} : new Object[]{tenantId(), org});
        r.put("invoiceCount", invoiceCount == null ? 0 : invoiceCount);
        // 今日日结状态
        List<HisDailySettle> settles = dailySettleMapper.selectList(Wrappers.<HisDailySettle>lambdaQuery()
                .eq(org != null, HisDailySettle::getOrgId, org)
                .eq(HisDailySettle::getSettleDate, LocalDate.now())
                .last("LIMIT 1"));
        r.put("settled", !settles.isEmpty() && settles.get(0).getStatus() != null && settles.get(0).getStatus() == 1);
        r.put("settleTime", settles.isEmpty() || settles.get(0).getSettleTime() == null
                ? "" : settles.get(0).getSettleTime().toString().replace('T', ' '));
        return r;
    }

    // ==================== 正式门诊收费票据(财综〔2012〕3号) ====================

    /** 手工票 11 类费用归并栏目(固定顺序, 无发生额也列示) */
    private static final String[] INVOICE_CATS = {
            "诊察费", "检查费", "化验费", "治疗费", "手术费", "卫生材料费",
            "西药费", "中草药费", "中成药费", "药事服务费", "一般诊疗费"};

    /**
     * 正式门诊收费票据打印数据(财综〔2012〕3号式样):
     * 表头(业务流水号/票据号/开票日期) + 患者栏(姓名/性别/医保类型/医保付费方式/社会保障号码)
     * + 机打明细行(项目/规格·报销类别·数量·金额·自费自理·其中医保政策范围外自费)
     * + 11类费用归并汇总(手工票栏目) + 合计大小写 + 医保统筹/个账/其他支付与个人现金支付。
     */
    public Map<String, Object> invoicePrint(Long billId) {
        HisChargeBill bill = billMapper.selectById(billId);
        if (bill == null) {
            throw new BizException(400, "收费单不存在");
        }
        List<Map<String, Object>> items = receiptItems(billId);
        // 患者与就诊信息(机构隔离由页面读口径保证, 此处按单取数)
        Map<String, Object> patient = new LinkedHashMap<>();
        if (bill.getVisitId() != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT v.patient_no, v.reg_no, v.ipt_otp_no, v.mdtrt_id, v.insutype, v.psn_no,"
                            + " v.dept_name, v.patient_name, v.gender, v.age, p.id_card"
                            + " FROM his_visit v LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                            + " WHERE v.id = ? AND v.tenant_id = ? AND v.deleted = 0", bill.getVisitId(), tenantId());
            if (!rows.isEmpty()) {
                Map<String, Object> vr = rows.get(0);
                patient.put("name", vr.get("patient_name"));
                patient.put("gender", genderText(str(vr.get("gender"))));
                patient.put("age", vr.get("age"));
                patient.put("idCard", vr.get("id_card"));
                patient.put("psnNo", vr.get("psn_no"));
                patient.put("patientNo", vr.get("patient_no"));
                patient.put("visitNo", vr.get("ipt_otp_no"));
                patient.put("regNo", vr.get("reg_no"));
                patient.put("mdtrtId", vr.get("mdtrt_id"));
                patient.put("deptName", vr.get("dept_name"));
                String insutype = str(vr.get("insutype"));
                patient.put("insuTypeName", insuTypeText(insutype));
                patient.put("insuPayWay", StringUtils.hasText(str(vr.get("mdtrt_id"))) || StringUtils.hasText(str(vr.get("psn_no")))
                        ? "按项目支付" : "自费");
            }
        }
        if (patient.isEmpty()) {
            patient.put("name", bill.getPatientName());
            patient.put("gender", "");
            patient.put("insuTypeName", "自费");
            patient.put("insuPayWay", "自费");
        }
        // 收款单位
        String orgName = "";
        if (bill.getOrgId() != null) {
            try {
                orgName = jdbcTemplate.queryForObject(
                        "SELECT org_name FROM sys_org WHERE id = ? AND deleted = 0", String.class, bill.getOrgId());
            } catch (Exception ignore) {
                // 机构名缺失不阻塞票面生成
            }
        }
        // 机打明细金额侧算: 自费自理合计 + 范围外自费合计
        BigDecimal selfCostTotal = BigDecimal.ZERO;
        BigDecimal outOfScopeTotal = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            selfCostTotal = selfCostTotal.add(nvl(toBd(it.get("selfCost"))));
            outOfScopeTotal = outOfScopeTotal.add(nvl(toBd(it.get("outOfScope"))));
        }
        // 11类费用归并(自定义归并类如"病理费"就近并入化验费, 未知并入治疗费)
        Map<String, BigDecimal> catMap = new LinkedHashMap<>();
        for (String c : INVOICE_CATS) {
            catMap.put(c, BigDecimal.ZERO);
        }
        for (Map<String, Object> it : items) {
            String cat = str(it.get("invoiceCat"));
            if (!catMap.containsKey(cat)) {
                cat = cat.contains("病理") || cat.contains("化验") ? "化验费"
                        : cat.contains("诊察") || cat.contains("诊疗") ? "诊察费" : "治疗费";
            }
            catMap.put(cat, catMap.get(cat).add(nvl(toBd(it.get("amount")))));
        }
        List<Map<String, Object>> cats = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : catMap.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", e.getKey());
            c.put("amount", e.getValue().setScale(2, RoundingMode.HALF_UP));
            cats.add(c);
        }
        // 医保支付四分 + 个人现金支付(退费单金额取负展示由前端按 billType 处理)
        BigDecimal fundPay = nvl(bill.getFundPay());
        BigDecimal acctPay = nvl(bill.getAcctPay());
        BigDecimal cashPay = nvl(bill.getCashPay());
        BigDecimal selfPay = nvl(bill.getSelfPay());
        BigDecimal total = nvl(bill.getTotalAmount());
        BigDecimal otherPay = BigDecimal.ZERO;
        LocalDateTime ct = bill.getChargeTime() == null ? LocalDateTime.now() : bill.getChargeTime();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", "湖北省医疗门诊收费票据");
        header.put("subTitle", "(机打)");
        header.put("orgName", orgName == null ? "" : orgName);
        header.put("billNo", bill.getBillNo());
        header.put("invoiceNo", bill.getInvoiceNo());
        header.put("date", ct.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        header.put("year", String.valueOf(ct.getYear()));
        header.put("month", String.valueOf(ct.getMonthValue()));
        header.put("day", String.valueOf(ct.getDayOfMonth()));
        header.put("billType", bill.getBillType());
        header.put("insuranceTypeCode", "鄂财办票〔2012〕3号");

        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("totalAmount", total);
        pay.put("upper", rmbUpper(total));
        pay.put("fundPay", fundPay);
        pay.put("acctPay", acctPay);
        pay.put("otherPay", otherPay);
        pay.put("selfPay", selfPay);
        pay.put("cashPay", cashPay);
        pay.put("selfCostTotal", selfCostTotal);
        pay.put("outOfScopeTotal", outOfScopeTotal);

        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("chargeBy", bill.getChargeBy());
        footer.put("chargeTime", ct.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        footer.put("setlId", bill.getSetlId());
        footer.put("payMethod", bill.getPayMethod());
        footer.put("remark", bill.getRemark());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("header", header);
        result.put("patient", patient);
        result.put("items", items);
        result.put("cats", cats);
        result.put("pay", pay);
        result.put("footer", footer);
        return result;
    }

    /** 性别码 -> 汉字(收据/票面共用) */
    private static String genderText(String g) {
        if ("1".equals(g)) {
            return "男";
        }
        if ("2".equals(g)) {
            return "女";
        }
        return g == null || g.isEmpty() ? "" : g;
    }

    /** 险种码 -> 医保类型名称(票面"医保类型"栏) */
    private static String insuTypeText(String t) {
        if (!StringUtils.hasText(t)) {
            return "自费";
        }
        switch (t) {
            case "310": return "职工医保";
            case "340": return "工伤保险";
            case "390": return "居民医保";
            case "391": return "城乡居民医保";
            case "510": return "自费";
            default: return t + "-医保";
        }
    }

    /** 人民币金额转中文大写(票据合计大写栏) */
    static String rmbUpper(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        String[] cnNum = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
        String[] unit = {"", "拾", "佰", "仟"};
        String[] bigUnit = {"", "万", "亿", "万亿"};
        boolean neg = amount.compareTo(BigDecimal.ZERO) < 0;
        BigDecimal abs = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long yuan = abs.longValue();
        int jiaoFen = abs.subtract(BigDecimal.valueOf(yuan)).multiply(BigDecimal.valueOf(100)).intValue();
        if (yuan == 0 && jiaoFen == 0) {
            return "零元整";
        }
        // 整数部分按 4 位分节, 高位向低位拼接; 低位节非零但不足千位(如 壹万零壹)或中间节全零时补零
        List<Integer> sections = new ArrayList<>();
        long tmp = yuan;
        while (tmp > 0) {
            sections.add((int) (tmp % 10000));
            tmp /= 10000;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = sections.size() - 1; i >= 0; i--) {
            int sec = sections.get(i);
            if (sec == 0) {
                continue;
            }
            if (sec < 1000 && sb.length() > 0) {
                sb.append("零");
            }
            sb.append(sectionToCn(sec, cnNum, unit)).append(bigUnit[i]);
        }
        // 不足1元时不接"元"(如 0.50 -> 伍角, 0.05 -> 伍分)
        if (yuan > 0) {
            sb.append("元");
        }
        int jiao = jiaoFen / 10;
        int fen = jiaoFen % 10;
        if (jiaoFen == 0) {
            sb.append("整");
        } else {
            if (jiao > 0) {
                sb.append(cnNum[jiao]).append("角");
            } else if (fen > 0 && yuan > 0) {
                sb.append("零");
            }
            if (fen > 0) {
                sb.append(cnNum[fen]).append("分");
            } else {
                sb.append("整");
            }
        }
        return (neg ? "负" : "") + sb;
    }

    /** 4 位以内金额节转大写(节内零值折叠, 尾零由调用方处理) */
    private static String sectionToCn(int sec, String[] cnNum, String[] unit) {
        StringBuilder s = new StringBuilder();
        boolean zero = false;
        for (int k = 3; k >= 0; k--) {
            int d = (int) (sec / Math.pow(10, k)) % 10;
            if (d == 0) {
                if (s.length() > 0) {
                    zero = true;
                }
            } else {
                if (zero) {
                    s.append("零");
                    zero = false;
                }
                s.append(cnNum[d]).append(unit[k]);
            }
        }
        return s.toString();
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
        // P1-15: 挂号费并入日结(笔数/金额净额: 挂号-退号)
        Map<String, Object> regAgg = jdbcTemplate.queryForMap(
                "SELECT"
                        + " IFNULL(SUM(direction), 0) AS reg_count,"
                        + " IFNULL(SUM(direction * amount), 0) AS reg_amount"
                        + " FROM his_reg_payment"
                        + " WHERE tenant_id = ? AND org_id = ? AND deleted = 0 AND DATE(biz_time) = ?",
                tenantId(), targetOrg, d);
        // 全渠道分项(收费侧): 混合支付明细自带正负方向(退费单明细为负), 直接按渠道汇总
        Map<String, Object> chAgg = jdbcTemplate.queryForMap(
                "SELECT"
                        + " IFNULL(SUM(CASE WHEN d.pay_method = 'WECHAT' THEN d.amount ELSE 0 END), 0) AS wechat_total,"
                        + " IFNULL(SUM(CASE WHEN d.pay_method = 'ALIPAY' THEN d.amount ELSE 0 END), 0) AS alipay_total,"
                        + " IFNULL(SUM(CASE WHEN d.pay_method = 'CARD' THEN d.amount ELSE 0 END), 0) AS card_total,"
                        + " IFNULL(SUM(CASE WHEN d.pay_method = 'FREE' THEN d.amount ELSE 0 END), 0) AS free_total"
                        + " FROM his_payment_detail d"
                        + " JOIN his_charge_bill b ON b.id = d.bill_id AND b.deleted = 0"
                        + " WHERE b.tenant_id = ? AND b.org_id = ? AND b.status >= 1 AND DATE(b.charge_time) = ?",
                tenantId(), targetOrg, d);
        // 全渠道分项(挂号侧): direction*amount 表达净额
        Map<String, Object> regChAgg = jdbcTemplate.queryForMap(
                "SELECT"
                        + " IFNULL(SUM(CASE WHEN pay_method = 'WECHAT' THEN direction * amount ELSE 0 END), 0) AS wechat_total,"
                        + " IFNULL(SUM(CASE WHEN pay_method = 'ALIPAY' THEN direction * amount ELSE 0 END), 0) AS alipay_total,"
                        + " IFNULL(SUM(CASE WHEN pay_method = 'CARD' THEN direction * amount ELSE 0 END), 0) AS card_total,"
                        + " IFNULL(SUM(CASE WHEN pay_method = 'FREE' THEN direction * amount ELSE 0 END), 0) AS free_total"
                        + " FROM his_reg_payment"
                        + " WHERE tenant_id = ? AND org_id = ? AND deleted = 0 AND DATE(biz_time) = ?",
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
        settle.setRegCount(toInt(regAgg.get("reg_count")));
        settle.setRegAmount(toBd(regAgg.get("reg_amount")));
        settle.setWechatTotal(toBd(chAgg.get("wechat_total")).add(toBd(regChAgg.get("wechat_total"))));
        settle.setAlipayTotal(toBd(chAgg.get("alipay_total")).add(toBd(regChAgg.get("alipay_total"))));
        settle.setCardTotal(toBd(chAgg.get("card_total")).add(toBd(regChAgg.get("card_total"))));
        settle.setFreeTotal(toBd(chAgg.get("free_total")).add(toBd(regChAgg.get("free_total"))));
        settle.setStatus(1);
        settle.setSettleTime(LocalDateTime.now());
        dailySettleMapper.insert(settle);
        log.info("日结完成: orgId={}, date={}, 收费{}笔/{}元, 退费{}笔/{}元, 挂号净{}笔/{}元",
                targetOrg, d, settle.getTotalCount(), settle.getTotalAmount(),
                settle.getRefundCount(), settle.getRefundAmount(),
                settle.getRegCount(), settle.getRegAmount());
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
     * 避免内存计数归零后与已存在单号冲突(唯一键报 Duplicate entry)。
     * 注意序号不做回绕: 超过9999后自然进位到5位/6位(格式 %04d 仅是最小宽度),
     * 回绕会与当天已有单号撞 uk_tenant_bill_no 导致收费全线 Duplicate entry
     * (压测复现: 单日10000单后 20 并发收费全部失败)。 */
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
        int s = SEQ.incrementAndGet();
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

    /**
     * 组装2204门诊费用明细上传报文(规范流程: 2204 -> 2206 -> 2207):
     * 仅上传带医保目录编码的明细(无目录编码的自费项目金额计入全自费, 不上传);
     * feedetl_sn 就诊内唯一: 处方明细 P+refId / 医嘱明细 O+refId;
     * medins_list_codg 取机构内项目编码; 开单科室/医生取就诊信息
     */
    private List<FeeDetailReq> buildFeeDetails(List<Map<String, Object>> items, String mdtrtId,
                                               String psnNo, String chrgBchno, Map<String, Object> visit) {
        List<FeeDetailReq> list = new ArrayList<>();
        if (CollectionUtils.isEmpty(items)) {
            return list;
        }
        String deptCode = str(visit.get("dept_code"));
        String deptName = str(visit.get("dept_name"));
        String drCode = str(visit.get("atddr_no"));
        String drName = str(visit.get("dr_name"));
        String feeTime = DateUtil.currentDateTime();
        int seq = 0;
        for (Map<String, Object> it : items) {
            String medListCodg = str(it.get("medListCodg"));
            if (!StringUtils.hasText(medListCodg)) {
                continue;
            }
            FeeDetailReq d = new FeeDetailReq();
            Long refId = toLong(it.get("refId"));
            boolean isOrder = "order_item".equals(str(it.get("refType")));
            d.setFeedetlSn((isOrder ? "O" : "P") + (refId != null ? refId : (++seq)));
            d.setMdtrtId(mdtrtId);
            d.setPsnNo(psnNo);
            d.setChrgBchno(chrgBchno);
            d.setFeeOcurTime(feeTime);
            d.setMedListCodg(medListCodg);
            d.setMedinsListCodg(str(it.get("itemCode")));
            d.setDetItemFeeSumamt(toBd(it.get("amount")));
            d.setCnt(toBd(it.get("qty")));
            d.setPric(toBd(it.get("price")));
            d.setBilgDeptCodg(deptCode);
            d.setBilgDeptName(deptName);
            d.setBilgDrCodg(drCode);
            d.setBilgDrName(drName);
            list.add(d);
        }
        return list;
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
