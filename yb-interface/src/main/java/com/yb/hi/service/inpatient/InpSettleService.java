package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yb.hi.common.DateUtil;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.FeeDetailReq;
import com.yb.hi.dto.SetlInfoResult;
import com.yb.hi.dto.SetlCancelReq;
import com.yb.hi.dto.SettlementReq;
import com.yb.hi.dto.inpatient.InpSettleDTO;
import com.yb.hi.entity.SetlRecord;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpDeposit;
import com.yb.hi.entity.inpatient.HisInpSettle;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.yb.HisCompTask;
import com.yb.hi.entity.yb.HisUploadStatus;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.SetlRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpDepositMapper;
import com.yb.hi.mapper.inpatient.HisInpSettleMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.yb.HisCompTaskMapper;
import com.yb.hi.service.InpatientService;
import com.yb.hi.service.SetlResultHandler;
import com.yb.hi.service.yb.UploadStatusService;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 住院结算服务: 预结算(2303透传)/正式结算(2301明细上传+2304结算+院内落账)/撤销结算(2305)/住院日结。
 * 金额口径:
 * - selfPay=个人负担(psn_part_amt) fundPay=基金支付 acctPay=个账支付, 非医保时 selfPay=总额;
 * - 个人现金应付 cashOwed = selfPay - acctPay; 预交金抵扣 depositDeduct = min(余额, cashOwed);
 * - 补缴现金 cashPay = cashOwed - depositDeduct; 退还 refundAmount = 余额 - depositDeduct。
 * 状态流转: 结算先乐观占住 visit_status(2/3->4) 再调医保, 医保失败整体回滚, 并发重复结算被状态锁拦截。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class InpSettleService {

    /** 结算单号内存序号(与挂号 genNo 同模式) */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** 住院医疗类别默认值(医保 med_type: 21普通住院) */
    private static final String MED_TYPE_INPATIENT = "21";

    /** 结算类型: 1出院结算 */
    private static final int SETTLE_TYPE_DISCHARGE = 1;

    /** 结算类型: 2中途结算 */
    private static final int SETTLE_TYPE_MID = 2;

    private final HisInpSettleMapper settleMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisInpDepositMapper depositMapper;
    private final InpBedService bedService;
    private final InpatientService inpatientService;
    private final SetlResultHandler setlResultHandler;
    private final SetlRecordMapper setlRecordMapper;
    private final JdbcTemplate jdbcTemplate;
    /** 补偿任务 Mapper(住院结算 UNKNOWN 登记) */
    private final HisCompTaskMapper compTaskMapper;
    /** 上传状态机(住院结算/入出院登记上报留痕, 供上报中心) */
    private final UploadStatusService uploadStatusService;
    /** 两阶段化事务模板(T1/T3 显式事务边界, T2 医保调用不占事务) */
    private final TransactionTemplate txTemplate;

    public InpSettleService(HisInpSettleMapper settleMapper, HisInpVisitMapper visitMapper,
                            HisInpDepositMapper depositMapper, InpBedService bedService,
                            InpatientService inpatientService, SetlResultHandler setlResultHandler,
                            SetlRecordMapper setlRecordMapper, JdbcTemplate jdbcTemplate,
                            HisCompTaskMapper compTaskMapper, UploadStatusService uploadStatusService,
                            PlatformTransactionManager transactionManager) {
        this.settleMapper = settleMapper;
        this.visitMapper = visitMapper;
        this.depositMapper = depositMapper;
        this.bedService = bedService;
        this.inpatientService = inpatientService;
        this.setlResultHandler = setlResultHandler;
        this.setlRecordMapper = setlRecordMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.compTaskMapper = compTaskMapper;
        this.uploadStatusService = uploadStatusService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /* ==================== 预结算 ==================== */

    /**
     * 预结算: 汇总结算范围内未结算费用(与正式结算同口径: 在院=中途区间, 出院办理中=全部未结算)
     * -> 医保患者(psn_no+mdtrt_id齐全)透传2303取医保试算, 非医保患者仅做院内试算(不调医保)。
     * 返回 {totalAmount, depositBalance, needPay, settleMode, scopeStart, scopeEnd, ybResponse, setlInfo}。
     */
    public Map<String, Object> preSettle(Long visitId) {
        HisInpVisit visit = requireVisit(visitId);
        // 与正式结算同口径: 在院(2)=中途结算区间(上次结算次日~今日), 出院办理中(3)=全部未结算明细
        boolean midPreview = visit.getVisitStatus() != null && visit.getVisitStatus() == 2;
        LocalDate scopeStart = null;
        LocalDate scopeEnd = null;
        if (midPreview) {
            LocalDate lastDate = lastSettleDate(visitId);
            scopeStart = lastDate != null ? lastDate.plusDays(1) : admitLocalDate(visit);
            scopeEnd = LocalDate.now();
        }
        BigDecimal totalAmount = sumUnsettled(visitId, scopeStart, scopeEnd);
        BigDecimal depositBalance = nvl(visit.getDepositBalance());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visitId", visitId);
        result.put("inpNo", visit.getInpNo());
        result.put("patientName", patientName(visit.getPatientId()));
        result.put("settleMode", midPreview ? "MID" : "DISCHARGE");
        result.put("scopeStart", scopeStart);
        result.put("scopeEnd", scopeEnd);
        result.put("totalAmount", totalAmount);
        result.put("depositBalance", depositBalance);
        result.put("needPay", totalAmount.subtract(depositBalance).max(BigDecimal.ZERO));
        boolean ybTry = hasYbIdentity(visit) && totalAmount.signum() > 0;
        result.put("ybFlag", ybTry);

        if (ybTry) {
            SettlementReq req = buildSettlementReq(visit, totalAmount);
            YbResponse resp = inpatientService.preSettlement(req);
            if (resp == null || !resp.isSuccess()) {
                String err = resp == null ? "医保无响应" : resp.getErrMsg();
                throw new BizException("医保预结算(2303)失败: " + err);
            }
            SetlInfoResult setlInfo = setlResultHandler.parse(resp);
            result.put("ybResponse", resp);
            result.put("setlInfo", setlInfo);
        } else {
            result.put("ybResponse", null);
            result.put("setlInfo", null);
        }
        return result;
    }

    /* ==================== 正式结算 ==================== */

    /**
     * 结算两阶段化(批次对齐门诊 A7): 医保写交易(2301/2304)不得持有本地行锁执行网络 IO。
     * T1(事务): 校验+乐观占位 visit_status(2/3->4, 出院)/行锁(中途) + 建 his_inp_settle 中间态(医保 yb_status=1结算中,
     *   自费直接终态 yb_status=0) + 冻结本次结算费用区间(明细回写 settle_id) -> 提交释放锁;
     * T2(无事务): 2301明细上传 -> 2304结算, 每步独立三分结果(SUCCESS/FAIL/UNKNOWN);
     * T3(事务): SUCCESS 条件更新 yb_status 1->2 + 回填四分金额/DRG-DIP + 预交金退还 + visit 出院校准 + 释放床位;
     *   FAIL 复位(明细解挂+结算单作废+visit 状态回原) 允许重结; UNKNOWN 留中间态挂起, 落 his_comp_task 由 CompTaskSweeper 收敛。
     */
    public Map<String, Object> settle(InpSettleDTO dto, Long orgId) {
        // T1: 本地事务建中间态(自费单在本阶段直接终态落账)
        SettleCtx ctx = txTemplate.execute(status -> settleStage1(dto, orgId));
        if (ctx == null) {
            throw new BizException("结算初始化失败, 请刷新后重试");
        }
        if (ctx.selfPayFlow) {
            // 自费/零金额出院: 无医保环节, T1 内已终态
            return ctx.buildResult(ctx.settle);
        }
        // T2: 医保交易链 2301 -> 2304(无事务, 每笔独立回执; UNKNOWN 不落终态, 走补偿)
        SettleOutcome outcome = callYbInpChain(ctx);
        // T3: 终态落账(独立事务, 条件更新防并发/补偿收敛后重复落账)
        if (outcome.success) {
            try {
                finalizeSettleSuccess(ctx, outcome);
            } catch (Exception e) {
                // 本地终态落账失败(单据中间态已提交, 无法回滚): 转补偿任务收敛, 避免单据永久挂起阻塞重结
                log.error("住院医保结算本地终态落账失败, 转补偿任务: settleNo={}, 原因: {}", ctx.settleNo, e.getMessage());
                createSettleCompTaskAndThrow(ctx, outcome,
                        "医保结算已完成但本地落账异常, 系统已登记补偿任务自动核对, 请稍后刷新查看");
            }
        } else if (outcome.unknown) {
            createSettleCompTaskAndThrow(ctx, outcome,
                    "医保结算结果未知(网络超时/异常), 系统已登记补偿任务自动核对, 请稍后刷新查看; 详情: " + outcome.errMsg);
        } else {
            markSettleFailed(ctx, outcome);
        }
        return ctx.buildResult(ctx.settle);
    }

    /**
     * T1 结算建单(在事务内执行): 校验+乐观占位+建中间态+冻结费用区间。
     * 自费(含零金额出院)在此直接算出全部金额并落终态, 不走 T2/T3。
     */
    private SettleCtx settleStage1(InpSettleDTO dto, Long orgId) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        Long visitId = dto.getInpVisitId();
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        int settleType = dto.getSettleType() == null ? SETTLE_TYPE_DISCHARGE : dto.getSettleType();
        boolean midSettle = settleType == SETTLE_TYPE_MID;
        Integer origVisitStatus = visit.getVisitStatus();
        if (midSettle) {
            // 中途结算: 要求在院(2), 不改状态不释放床位; 行锁串行化同一就诊的并发结算(无状态翻转, 乐观锁不可用)
            if (origVisitStatus == null || origVisitStatus != 2) {
                throw new BizException("该就诊不在院(状态=" + origVisitStatus + "), 不能办理中途结算");
            }
            jdbcTemplate.queryForObject(
                    "SELECT id FROM his_inp_visit WHERE id = ? AND deleted = 0 AND tenant_id = ? FOR UPDATE",
                    Long.class, visitId, tenantId());
        } else if (origVisitStatus == null || (origVisitStatus != 2 && origVisitStatus != 3)) {
            throw new BizException("该就诊不在院或未提交出院申请(状态=" + origVisitStatus + "), 不能结算");
        }

        // 结算范围: 中途结算按区间隔离(上次结算次日至今日), 出院结算=全部未结算明细(settle_id IS NULL)
        LocalDate scopeStart = null;
        LocalDate scopeEnd = null;
        if (midSettle) {
            LocalDate lastDate = lastSettleDate(visitId);
            scopeStart = lastDate != null ? lastDate.plusDays(1) : admitLocalDate(visit);
            scopeEnd = LocalDate.now();
            if (scopeStart.isAfter(scopeEnd)) {
                throw new BizException("结算区间异常(起始 " + scopeStart + " 晚于今日), 请核对上次结算时间");
            }
        }
        List<HisInpChargeDetail> details = queryUnsettledDetails(visitId, scopeStart, scopeEnd);
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (HisInpChargeDetail d : details) {
            totalAmount = totalAmount.add(nvl(d.getAmount()));
        }
        if (totalAmount.signum() <= 0 && midSettle) {
            throw new BizException("该就诊本次结算区间内无未结算费用, 不能办理中途结算");
        }
        // 出院结算允许零未结算费用(费用已全部中途结清等): 生成零元结算单完成出院闭环
        BigDecimal depositBalance = nvl(visit.getDepositBalance());

        // 出院结算先乐观占住状态(2/3->4): 并发重复结算在此被拦截(中间态独立提交, FAIL/未受理时复位回原状态)
        if (!midSettle) {
            int locked = jdbcTemplate.update(
                    "UPDATE his_inp_visit SET visit_status = 4, update_time = NOW()"
                            + " WHERE id = ? AND visit_status IN (2, 3) AND deleted = 0 AND tenant_id = ?",
                    visitId, tenantId());
            if (locked == 0) {
                throw new BizException("该就诊已被结算或状态已变化, 请刷新后重试");
            }
        }

        // 医保链路判定(psn_no+mdtrt_id 齐全且金额>0): 零金额出院/自费患者走院内直接终态, 不调医保
        boolean ybFlag = hasYbIdentity(visit) && totalAmount.signum() > 0;

        HisInpSettle settle = new HisInpSettle();
        settle.setOrgId(orgId);
        settle.setInpVisitId(visitId);
        settle.setSettleNo(genSettleNo());
        settle.setTotalAmount(totalAmount);
        settle.setSettleType(settleType);
        settle.setSettleTime(LocalDateTime.now());
        settle.setOperatorId(currentStaffId());
        SettleCtx ctx = new SettleCtx();
        ctx.tenantId = tenantId();
        ctx.visitId = visitId;
        ctx.visit = visit;
        ctx.details = details;
        ctx.totalAmount = totalAmount;
        ctx.depositBalance = depositBalance;
        ctx.midSettle = midSettle;
        ctx.scopeStart = scopeStart;
        ctx.scopeEnd = scopeEnd;
        ctx.settleType = settleType;
        ctx.origVisitStatus = origVisitStatus;
        ctx.settle = settle;
        ctx.settleNo = settle.getSettleNo();
        ctx.bedId = visit.getBedId();
        ctx.orgId = orgId;

        if (!ybFlag) {
            // 自费/零金额出院: 全额自付, 本事务内算终态并落账(yb_status=0)
            BigDecimal cashOwed = totalAmount;
            BigDecimal depositDeduct = depositBalance.min(cashOwed);
            BigDecimal cashPay = cashOwed.subtract(depositDeduct);
            BigDecimal refundAmount = midSettle ? BigDecimal.ZERO : depositBalance.subtract(depositDeduct);
            settle.setSelfPay(totalAmount);
            settle.setFundPay(BigDecimal.ZERO);
            settle.setAcctPay(BigDecimal.ZERO);
            settle.setCashPay(cashPay);
            settle.setDepositDeduct(depositDeduct);
            settle.setRefundAmount(refundAmount);
            settle.setYbStatus(0);
            settleMapper.insert(settle);
            freezeSettleScope(ctx);
            applyVisitLedger(ctx, depositDeduct, refundAmount, currentStaffId());
            ctx.selfPayFlow = true;
            ctx.finalCashPay = cashPay;
            ctx.finalRefund = refundAmount;
            ctx.finalYbFlag = false;
            return ctx;
        }

        // 医保结算中间态: 金额待 2304 回填, 先置预估(selfPay=total, 其余0), yb_status=1(结算中)
        settle.setSelfPay(totalAmount);
        settle.setFundPay(BigDecimal.ZERO);
        settle.setAcctPay(BigDecimal.ZERO);
        settle.setCashPay(totalAmount);
        settle.setDepositDeduct(BigDecimal.ZERO);
        settle.setRefundAmount(BigDecimal.ZERO);
        settle.setYbStatus(1);
        settleMapper.insert(settle);
        // 冻结本次结算费用区间(明细挂 settle_id): FAIL 时解挂回待结算池, UNKNOWN 期间保持归属本单
        freezeSettleScope(ctx);
        return ctx;
    }

    /** 冻结本次结算明细区间: 范围内未结算明细回写 settle_id(支撑按结算单追溯 + 中途分次不重复计入) */
    private void freezeSettleScope(SettleCtx ctx) {
        String markSql = "UPDATE his_inp_charge_detail SET settle_id = ?, update_time = NOW()"
                + " WHERE inp_visit_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ? AND settle_id IS NULL"
                + (ctx.midSettle ? " AND charge_date BETWEEN ? AND ?" : "");
        if (ctx.midSettle) {
            jdbcTemplate.update(markSql, ctx.settle.getId(), ctx.visitId, ctx.tenantId, ctx.scopeStart, ctx.scopeEnd);
        } else {
            jdbcTemplate.update(markSql, ctx.settle.getId(), ctx.visitId, ctx.tenantId);
        }
    }

    /**
     * T2: 医保交易链 2301 -> 2304(无事务)。每笔独立三分: 平台明确拒绝=FAIL(复位可重结),
     * 超时/网络异常=UNKNOWN(补偿任务挂起), 全部成功才返回 SUCCESS 金额四分 + DRG/DIP。
     * 零编码自费明细不上传(与门诊2204口径一致); 2304 成功但缺 setl_id 视为平台异常按 FAIL 处理。
     */
    private SettleOutcome callYbInpChain(SettleCtx ctx) {
        List<FeeDetailReq> feeDetails = buildFeeDetails(ctx.visit, ctx.details);
        if (!feeDetails.isEmpty()) {
            YbResponse feeResp = inpatientService.uploadFeeDetail(feeDetails);
            if (feeResp == null) {
                return SettleOutcome.unknown(null, "医保费用明细上传(2301)无响应");
            }
            if (feeResp.isUnknown()) {
                return SettleOutcome.unknown(findInpTxnLogId(feeResp), "医保费用明细上传(2301)网络异常, 结果未知");
            }
            if (!feeResp.isSuccess()) {
                return SettleOutcome.fail("医保费用明细上传(2301)失败: " + feeResp.getErrMsg());
            }
        }
        SettlementReq req = buildSettlementReq(ctx.visit, ctx.totalAmount);
        YbResponse resp = inpatientService.settlement(req);
        if (resp == null) {
            return SettleOutcome.unknown(null, "医保结算(2304)无响应");
        }
        if (resp.isUnknown()) {
            return SettleOutcome.unknown(findInpTxnLogId(resp), "医保结算(2304)网络异常, 结果未知");
        }
        if (!resp.isSuccess()) {
            return SettleOutcome.fail("医保结算(2304)失败: " + resp.getErrMsg());
        }
        JSONObject setlNode = resp.getOutputNode("setlinfo");
        String setlId = setlNode == null ? null : setlNode.getString("setl_id");
        // 2304 成功但缺 setl_id: 规范出参必含结算ID, 缺失即平台侧异常, 本地不得置已结算(否则无法撤销)
        if (!StringUtils.hasText(setlId)) {
            log.error("医保结算(2304)返回缺少setl_id: settleNo={}, visitId={}", ctx.settleNo, ctx.visitId);
            return SettleOutcome.fail("医保结算(2304)返回缺少结算ID(setl_id), 请核对医保平台结算状态后处理");
        }
        SetlInfoResult setlInfo = setlResultHandler.parse(resp);
        SettleOutcome o = SettleOutcome.success();
        o.setlId = setlId;
        o.fundPay = setlInfo == null || setlInfo.getFundPaySumamt() == null ? BigDecimal.ZERO : setlInfo.getFundPaySumamt();
        o.acctPay = setlInfo == null || setlInfo.getAcctPay() == null ? BigDecimal.ZERO : setlInfo.getAcctPay();
        o.psnPart = setlInfo == null ? null : setlInfo.getPsnPartAmt();
        if (setlNode != null) {
            o.drgCode = firstText(setlNode, "drgGroupCode", "drg_group_code", "drgCode", "drg_code");
            o.dipCode = firstText(setlNode, "dipCode", "dip_code");
            o.payMethod = firstInt(setlNode, "payMethod", "pay_method", "clrWay", "clr_way");
        }
        o.txnLogId = findInpTxnLogId(resp);
        return o;
    }

    /** T3: 医保结算成功本地终态落账(独立事务, 条件更新 yb_status 1->2 防并发/重复落账) */
    private void finalizeSettleSuccess(SettleCtx ctx, SettleOutcome o) {
        txTemplate.executeWithoutResult(status -> {
            BigDecimal selfPay = o.psnPart == null ? ctx.totalAmount.subtract(o.fundPay) : o.psnPart;
            BigDecimal cashOwed = selfPay.subtract(o.acctPay).max(BigDecimal.ZERO);
            BigDecimal depositDeduct = ctx.depositBalance.min(cashOwed);
            BigDecimal cashPay = cashOwed.subtract(depositDeduct);
            BigDecimal refundAmount = ctx.midSettle ? BigDecimal.ZERO : ctx.depositBalance.subtract(depositDeduct);
            int updated = jdbcTemplate.update(
                    "UPDATE his_inp_settle SET self_pay=?, fund_pay=?, acct_pay=?, cash_pay=?, deposit_deduct=?,"
                            + " refund_amount=?, drg_group_code=?, dip_code=?, pay_method=?, yb_status=2, update_time=NOW()"
                            + " WHERE id=? AND tenant_id=? AND yb_status=1 AND deleted=0",
                    selfPay, o.fundPay, o.acctPay, cashPay, depositDeduct, refundAmount,
                    o.drgCode, o.dipCode, o.payMethod, ctx.settle.getId(), ctx.tenantId);
            if (updated != 1) {
                log.error("住院结算单终态条件更新失败: settleId={}, affected={}", ctx.settle.getId(), updated);
                throw new BizException("结算单状态已变化, 请刷新后重试");
            }
            applyVisitLedger(ctx, depositDeduct, refundAmount, currentStaffId());
            // 回填内存对象供结果返回
            ctx.settle.setSelfPay(selfPay);
            ctx.settle.setFundPay(o.fundPay);
            ctx.settle.setAcctPay(o.acctPay);
            ctx.settle.setCashPay(cashPay);
            ctx.settle.setDepositDeduct(depositDeduct);
            ctx.settle.setRefundAmount(refundAmount);
            ctx.settle.setDrgGroupCode(o.drgCode);
            ctx.settle.setDipCode(o.dipCode);
            ctx.settle.setPayMethod(o.payMethod);
            ctx.settle.setYbStatus(2);
            ctx.finalCashPay = cashPay;
            ctx.finalRefund = refundAmount;
            ctx.finalYbFlag = true;
            log.info("住院医保结算完成: visitId={}, settleNo={}, 类型={}(1出院/2中途), 区间={}~{}, total={}, self={}, fund={}, acct={}, cash={}, deduct={}, refund={}, setlId={}",
                    ctx.visitId, ctx.settleNo, ctx.settleType, ctx.scopeStart, ctx.scopeEnd,
                    ctx.totalAmount, selfPay, o.fundPay, o.acctPay, cashPay, depositDeduct, refundAmount, o.setlId);
        });
    }

    /**
     * 院内落账(出院/中途共用, 须在事务内调用): 多缴预交金退还流水(出院) + visit 状态落账。
     * 中途: 仅扣减预交金抵扣额, 保持2在院, 不覆盖累计费用不释放床位;
     * 出院: 出院时间/累计费用校准/余额清零 + 释放床位(结算出院后床位回空床池)。
     */
    private void applyVisitLedger(SettleCtx ctx, BigDecimal depositDeduct, BigDecimal refundAmount, Long operatorId) {
        if (refundAmount.signum() > 0) {
            HisInpDeposit flow = new HisInpDeposit();
            flow.setOrgId(ctx.orgId);
            flow.setInpVisitId(ctx.visitId);
            flow.setAmount(refundAmount);
            flow.setPayType("CASH");
            flow.setDirection(2);
            flow.setBalanceAfter(BigDecimal.ZERO);
            flow.setOperatorId(operatorId);
            flow.setRemark("出院结算(" + ctx.settleNo + ")退还多缴预交金");
            depositMapper.insert(flow);
        }
        if (ctx.midSettle) {
            jdbcTemplate.update(
                    "UPDATE his_inp_visit SET deposit_balance = IFNULL(deposit_balance, 0) - ?, update_time = NOW()"
                            + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    depositDeduct, ctx.visitId, ctx.tenantId);
        } else {
            BigDecimal allCost = sumCharges(ctx.visitId);
            jdbcTemplate.update(
                    "UPDATE his_inp_visit SET discharge_date = NOW(), total_cost = ?, deposit_balance = 0,"
                            + " update_time = NOW() WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    allCost, ctx.visitId, ctx.tenantId);
            if (ctx.bedId != null) {
                bedService.releaseBed(ctx.bedId);
            }
        }
    }

    /** FAIL(平台明确拒绝): 明细解挂回待结算池 + 结算单作废(deleted=1) + 出院 visit 状态回原(可重新结算), 独立事务 */
    private void markSettleFailed(SettleCtx ctx, SettleOutcome o) {
        txTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE his_inp_charge_detail SET settle_id = NULL, update_time = NOW()"
                            + " WHERE settle_id = ? AND deleted = 0 AND tenant_id = ?",
                    ctx.settle.getId(), ctx.tenantId);
            int voided = jdbcTemplate.update(
                    "UPDATE his_inp_settle SET deleted = 1, update_time = NOW()"
                            + " WHERE id = ? AND tenant_id = ? AND yb_status = 1 AND deleted = 0",
                    ctx.settle.getId(), ctx.tenantId);
            if (!ctx.midSettle) {
                Integer restore = ctx.origVisitStatus != null ? ctx.origVisitStatus : 3;
                jdbcTemplate.update(
                        "UPDATE his_inp_visit SET visit_status = ?, update_time = NOW()"
                                + " WHERE id = ? AND visit_status = 4 AND deleted = 0 AND tenant_id = ?",
                        restore, ctx.visitId, ctx.tenantId);
            }
            log.warn("医保结算明确失败, 结算单作废可重结: settleNo={}, voided={}, 原因: {}", ctx.settleNo, voided, o.errMsg);
        });
        throw new BizException(o.errMsg == null ? "医保结算失败" : o.errMsg);
    }

    /** UNKNOWN(超时/网络异常): 补偿任务独立事务落库, 单据留中间态挂起(yb_status=1), 由 CompTaskSweeper 收敛 */
    private void createSettleCompTaskAndThrow(SettleCtx ctx, SettleOutcome o, String userMsg) {
        txTemplate.executeWithoutResult(status -> {
            HisCompTask task = new HisCompTask();
            task.setBizType(HisCompTask.BIZ_INP_SETTLE);
            task.setRefId(ctx.settle.getId());
            task.setAction(HisCompTask.ACT_RESOLVE_UNKNOWN);
            task.setTxnLogId(o.txnLogId);
            task.setStatus(HisCompTask.ST_PENDING);
            task.setAttempts(0);
            task.setNextRun(LocalDateTime.now());
            task.setMemo(o.errMsg);
            compTaskMapper.insert(task);
            // 上报中心留痕(住院结算失败待补)
            uploadStatusService.record(ctx.tenantId, HisUploadStatus.BIZ_INP_SETL, ctx.settle.getId(),
                    ctx.visit.getMdtrtId(), false, null, o.errMsg);
        });
        throw new BizException(userMsg);
    }

    /**
     * 补偿收敛入口(CompTaskSweeper 驱动): 住院 UNKNOWN 结算单终态回填或复位(对齐门诊 resolveUnknownCharge)。
     * platformAccepted=true(核对平台侧已受理): 条件回填已结算(1->2)+金额四分+预交金退还+出院校准+释放床位;
     * false(平台未受理): 明细解挂+结算单作废+出院 visit 状态复位, 允许重新结算。
     * 调度线程无请求上下文, 显式传 tenantId 并临时设置 TenantContext。
     * @return 收敛结果说明(由补偿任务记入 memo)
     */
    public String resolveUnknownSettle(Long tenantId, Long settleId, boolean platformAccepted, String setlId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, inp_visit_id, total_amount, settle_type, org_id, operator_id, settle_no"
                        + " FROM his_inp_settle WHERE id = ? AND tenant_id = ? AND deleted = 0 AND yb_status = 1",
                settleId, tenantId);
        if (rows.isEmpty()) {
            return "结算中间态已不存在(可能已人工处理)";
        }
        Map<String, Object> r = rows.get(0);
        Long visitId = toLong(r.get("inp_visit_id"));
        int settleType = ((Number) r.get("settle_type")).intValue();
        boolean midSettle = settleType == SETTLE_TYPE_MID;
        if (!platformAccepted) {
            TenantContext.set(tenantId);
            try {
                return txTemplate.execute(status -> {
                    jdbcTemplate.update(
                            "UPDATE his_inp_charge_detail SET settle_id = NULL, update_time = NOW()"
                                    + " WHERE settle_id = ? AND deleted = 0 AND tenant_id = ?", settleId, tenantId);
                    jdbcTemplate.update(
                            "UPDATE his_inp_settle SET deleted = 1, update_time = NOW()"
                                    + " WHERE id = ? AND tenant_id = ? AND yb_status = 1 AND deleted = 0", settleId, tenantId);
                    if (!midSettle) {
                        // 出院复位到"出院办理中"(3)可重新结算(原状态2/3无法回溯, 3为可再结算安全态)
                        jdbcTemplate.update(
                                "UPDATE his_inp_visit SET visit_status = 3, update_time = NOW()"
                                        + " WHERE id = ? AND visit_status = 4 AND deleted = 0 AND tenant_id = ?",
                                visitId, tenantId);
                    }
                    return "平台未受理, 结算中间态已复位, 可重新结算";
                });
            } finally {
                TenantContext.clear();
            }
        }
        // 已受理: 按平台权威金额回填(mock 结算流水无四分, 与门诊同口径按 total 估分 70/10/20 收敛终态)
        BigDecimal total = toBd(r.get("total_amount"));
        if (total == null) {
            total = BigDecimal.ZERO;
        }
        BigDecimal fundPay = total.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal acctPay = total.multiply(new BigDecimal("0.10")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal selfPay = total.subtract(fundPay).subtract(acctPay);
        SettleCtx ctx = new SettleCtx();
        ctx.tenantId = tenantId;
        ctx.visitId = visitId;
        ctx.midSettle = midSettle;
        ctx.settleType = settleType;
        ctx.orgId = toLong(r.get("org_id"));
        ctx.settleNo = str(r.get("settle_no"));
        ctx.settle = new HisInpSettle();
        ctx.settle.setId(settleId);
        ctx.finalYbFlag = true;
        TenantContext.set(tenantId);
        try {
            return txTemplate.execute(status -> {
                HisInpVisit visit = visitMapper.selectById(visitId);
                BigDecimal depositBalance = visit == null ? BigDecimal.ZERO : nvl(visit.getDepositBalance());
                ctx.bedId = visit == null ? null : visit.getBedId();
                BigDecimal cashOwed = selfPay.subtract(acctPay).max(BigDecimal.ZERO);
                BigDecimal depositDeduct = depositBalance.min(cashOwed);
                BigDecimal cashPay = cashOwed.subtract(depositDeduct);
                BigDecimal refundAmount = midSettle ? BigDecimal.ZERO : depositBalance.subtract(depositDeduct);
                int updated = jdbcTemplate.update(
                        "UPDATE his_inp_settle SET self_pay=?, fund_pay=?, acct_pay=?, cash_pay=?, deposit_deduct=?,"
                                + " refund_amount=?, yb_status=2, update_time=NOW()"
                                + " WHERE id=? AND tenant_id=? AND yb_status=1 AND deleted=0",
                        selfPay, fundPay, acctPay, cashPay, depositDeduct, refundAmount, settleId, tenantId);
                if (updated != 1) {
                    return "回填失败(结算状态已变化)";
                }
                applyVisitLedger(ctx, depositDeduct, refundAmount, toLong(r.get("operator_id")));
                log.info("补偿收敛-住院结算平台已受理: settleId={}, setlId={}, visitId={}", settleId, setlId, visitId);
                return "平台已受理, 结算终态已补录(setl_id=" + setlId + ")";
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** 按 UNKNOWN 交易请求报文 msgid 回查交易日志ID(补偿任务挂接原交易) */
    private Long findInpTxnLogId(YbResponse resp) {
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

    /** 结算两阶段 T1->T2->T3 上下文 */
    private static final class SettleCtx {
        long tenantId;
        Long visitId;
        HisInpVisit visit;
        HisInpSettle settle;
        List<HisInpChargeDetail> details;
        BigDecimal totalAmount;
        BigDecimal depositBalance;
        boolean midSettle;
        LocalDate scopeStart;
        LocalDate scopeEnd;
        int settleType;
        Integer origVisitStatus;
        boolean selfPayFlow;
        String settleNo;
        Long bedId;
        Long orgId;
        BigDecimal finalCashPay;
        BigDecimal finalRefund;
        boolean finalYbFlag;

        Map<String, Object> buildResult(HisInpSettle settle) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("settle", settle);
            result.put("cashPay", finalCashPay);
            result.put("refundAmount", finalRefund);
            result.put("ybFlag", finalYbFlag);
            result.put("settleType", settleType);
            result.put("scopeStart", scopeStart);
            result.put("scopeEnd", scopeEnd);
            return result;
        }
    }

    /** T2 医保结算结果三分载体 */
    private static final class SettleOutcome {
        boolean success;
        boolean unknown;
        String errMsg;
        Long txnLogId;
        String setlId;
        BigDecimal fundPay;
        BigDecimal acctPay;
        BigDecimal psnPart;
        String drgCode;
        String dipCode;
        Integer payMethod;

        static SettleOutcome success() {
            SettleOutcome o = new SettleOutcome();
            o.success = true;
            return o;
        }

        static SettleOutcome fail(String msg) {
            SettleOutcome o = new SettleOutcome();
            o.errMsg = msg;
            return o;
        }

        static SettleOutcome unknown(Long txnLogId, String msg) {
            SettleOutcome o = fail(msg);
            o.unknown = true;
            o.txnLogId = txnLogId;
            return o;
        }
    }


    /* ==================== 结算历史 ==================== */

    /** 结算历史(含中途/出院/退费; 已撤销记录也返回, yb_status=4 供前端标识), 按结算时间倒序 */
    public List<HisInpSettle> listSettlements(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        return settleMapper.selectList(new LambdaQueryWrapper<HisInpSettle>()
                .eq(HisInpSettle::getInpVisitId, visitId)
                .orderByDesc(HisInpSettle::getSettleTime)
                .orderByDesc(HisInpSettle::getId));
    }

    /* ==================== 结算打印数据(模型增强) ==================== */

    /** 结算单打印数据(结算单+就诊+患者姓名+按类别费用汇总), 供票据打印模板渲染 */
    public R<Map<String, Object>> getSettlePrintData(Long settleId) {
        if (settleId == null) {
            throw new BizException(400, "settleId不能为空");
        }
        HisInpSettle settle = settleMapper.selectById(settleId);
        if (settle == null) {
            throw new BizException(404, "结算记录不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settle", settle);
        HisInpVisit visit = visitMapper.selectById(settle.getInpVisitId());
        data.put("visit", visit);
        if (visit != null) {
            data.put("inpNo", visit.getInpNo());
            data.put("patientName", patientName(visit.getPatientId()));
            data.put("admitDate", visit.getAdmitDate());
            data.put("dischargeDate", visit.getDischargeDate());
        }
        // 按类别费用汇总: 优先按结算单关联明细(settle_id, 分次结算按单追溯费用组成);
        // 旧数据(分次结算上线前结算单无明细关联)回退按就诊全量汇总
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT fee_type, COALESCE(SUM(quantity), 0) total_qty, COALESCE(SUM(amount), 0) total_amount"
                        + " FROM his_inp_charge_detail"
                        + " WHERE settle_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?"
                        + " GROUP BY fee_type ORDER BY fee_type",
                settleId, tenantId());
        if (rows.isEmpty()) {
            rows = jdbcTemplate.queryForList(
                    "SELECT fee_type, COALESCE(SUM(quantity), 0) total_qty, COALESCE(SUM(amount), 0) total_amount"
                            + " FROM his_inp_charge_detail"
                            + " WHERE inp_visit_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?"
                            + " GROUP BY fee_type ORDER BY fee_type",
                    settle.getInpVisitId(), tenantId());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            Integer ft = row.get("fee_type") == null ? null : ((Number) row.get("fee_type")).intValue();
            BigDecimal amt = row.get("total_amount") == null ? BigDecimal.ZERO
                    : new BigDecimal(String.valueOf(row.get("total_amount")));
            m.put("feeType", ft);
            m.put("feeTypeName", feeTypeName(ft));
            m.put("totalQuantity", row.get("total_qty"));
            m.put("totalAmount", amt);
            items.add(m);
            totalAmount = totalAmount.add(amt);
        }
        data.put("feeItems", items);
        data.put("chargeTotalAmount", totalAmount);
        data.put("printTime", LocalDateTime.now());
        log.info("结算单打印数据组装: settleId={}, settleNo={}", settleId, settle.getSettleNo());
        return R.ok(data);
    }

    /** 结算报文多键名兼容取文本(setlinfo 各版本键名差异) */
    private static String firstText(JSONObject node, String... keys) {
        for (String k : keys) {
            Object v = node.get(k);
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    /** 结算报文多键名兼容取整数 */
    private static Integer firstInt(JSONObject node, String... keys) {
        for (String k : keys) {
            Object v = node.get(k);
            if (v != null) {
                try {
                    return Integer.valueOf(String.valueOf(v).trim());
                } catch (NumberFormatException ignored) {
                    // 当前键名非数值, 尝试下一键名
                }
            }
        }
        return null;
    }

    /** 费用类别名称 */
    private String feeTypeName(Integer feeType) {
        if (feeType == null) {
            return "其他";
        }
        switch (feeType) {
            case 1:
                return "西药";
            case 2:
                return "中药";
            case 3:
                return "检查";
            case 4:
                return "检验";
            case 5:
                return "治疗";
            case 6:
                return "护理";
            case 7:
                return "材料";
            case 8:
                return "床位";
            default:
                return "其他";
        }
    }

    /* ==================== 撤销结算 ==================== */

    /**
     * 撤销结算两阶段化(对齐门诊 A7): 2305 撤销不得持有本地行锁执行网络 IO。
     * T1(事务): 校验 + 医保已结算单乐观占位 yb_status(2->3 撤销中)后独立提交释放行锁; 自费单(yb_status=0)无医保环节, 本阶段直接同步撤销(0->4)。
     * T2(无事务): 2305 撤销(超时 UNKNOWN 绝不重发)。
     * T3(事务): SUCCESS 条件更新 yb_status(3->4)+费用明细解挂+visit 回在院(2)+预交金冲回; FAIL 复位(3->2)可重试;
     *   UNKNOWN 留撤销中(3)挂 his_comp_task(BIZ_INP_RTN), 由 CompTaskSweeper 核对平台撤销状态收敛。
     * 床位不自动恢复: 原床位可能已被新患者占用, 请通过转床或床位管理重新安排(中途撤销未释放床位不受影响)。
     */
    public Map<String, Object> cancelSettle(Long settleId) {
        // T1: 本地事务乐观占位撤销中间态(自费单在本阶段直接同步撤销)
        CancelCtx ctx = txTemplate.execute(st -> cancelStage1(settleId));
        if (ctx == null) {
            throw new BizException("撤销初始化失败, 请刷新后重试");
        }
        if (ctx.selfPayDone) {
            // 自费/无医保: 无撤销环节, T1 内已终态
            return buildCancelResult(ctx);
        }
        // T2: 医保撤销交易 2305(无事务, UNKNOWN 不落终态, 走补偿)
        CancelOutcome o = callYbCancel(ctx);
        // T3: 终态落账(独立事务, 条件更新防并发/补偿收敛后重复落账)
        if (o.success) {
            try {
                finalizeCancelSuccess(ctx);
            } catch (Exception e) {
                log.error("住院医保撤销本地终态落账失败, 转补偿任务: settleNo={}, 原因: {}", ctx.settle.getSettleNo(), e.getMessage());
                createCancelCompTaskAndThrow(ctx, o, "医保结算撤销已完成但本地落账异常, 系统已登记补偿任务自动核对, 请稍后刷新查看");
            }
        } else if (o.unknown) {
            createCancelCompTaskAndThrow(ctx, o,
                    "医保结算撤销结果未知(网络超时/异常), 系统已登记补偿任务自动核对, 请稍后刷新查看; 详情: " + o.errMsg);
        } else {
            markCancelFailed(ctx, o);
        }
        return buildCancelResult(ctx);
    }

    /**
     * T1 撤销占位(在事务内执行): 校验 + 回查 setl_id + 乐观占位 yb_status(2->3)。
     * 自费(yb_status=0)在此直接同步完成撤销并落本地账, 不走 T2/T3。
     */
    private CancelCtx cancelStage1(Long settleId) {
        if (settleId == null) {
            throw new BizException(400, "结算ID不能为空");
        }
        HisInpSettle settle = settleMapper.selectById(settleId);
        if (settle == null) {
            throw new BizException(404, "结算记录不存在");
        }
        Integer ybStatus = settle.getYbStatus();
        if (ybStatus != null && ybStatus == 4) {
            throw new BizException("该结算已撤销, 不能重复撤销");
        }
        if (ybStatus != null && ybStatus == 3) {
            throw new BizException("该结算撤销处理中, 请勿重复操作");
        }
        if (ybStatus != null && ybStatus == 1) {
            throw new BizException("该结算尚在结算中(结果未收敛), 不能撤销, 请待结算完成或补偿收敛后处理");
        }
        boolean midSettle = settle.getSettleType() != null && settle.getSettleType() == SETTLE_TYPE_MID;
        HisInpVisit visit = visitMapper.selectById(settle.getInpVisitId());
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        // 中途撤销要求就诊在院(2); 出院撤销在 T3/本地落账时按 visit_status=4 条件校验
        if (midSettle && (visit.getVisitStatus() == null || visit.getVisitStatus() != 2)) {
            throw new BizException("该就诊不在院(状态=" + visit.getVisitStatus() + "), 无法撤销中途结算");
        }
        CancelCtx ctx = new CancelCtx();
        ctx.tenantId = tenantId();
        ctx.settle = settle;
        ctx.visit = visit;
        ctx.midSettle = midSettle;
        ctx.bedId = visit.getBedId();

        boolean insurance = ybStatus != null && ybStatus == 2;
        if (!insurance) {
            // 自费单(yb_status=0): 无医保撤销环节, 本事务直接同步完成撤销(0->4)+本地解挂/回状态/冲回
            int done = jdbcTemplate.update(
                    "UPDATE his_inp_settle SET yb_status = 4, update_time = NOW()"
                            + " WHERE id = ? AND yb_status = 0 AND deleted = 0 AND tenant_id = ?",
                    settleId, ctx.tenantId);
            if (done == 0) {
                throw new BizException("结算状态已变化, 请刷新后重试");
            }
            settle.setYbStatus(4);
            applyCancelLocal(ctx, currentStaffId());
            ctx.selfPayDone = true;
            return ctx;
        }
        // 医保已结算: 回查 setl_record(2304留存)取 setl_id(2305 入参), 缺失则不可撤销(避免悬空)
        String setlId = findYbSetlId(visit.getMdtrtId());
        if (!StringUtils.hasText(setlId)) {
            throw new BizException("未找到该就诊的医保结算记录(setl_id), 无法撤销, 请核对医保结算流水");
        }
        ctx.setlId = setlId;
        // 乐观占位 2->3(撤销中): 并发重复撤销在此被拦截; 独立提交释放行锁后再调 2305
        int locked = jdbcTemplate.update(
                "UPDATE his_inp_settle SET yb_status = 3, update_time = NOW()"
                        + " WHERE id = ? AND yb_status = 2 AND deleted = 0 AND tenant_id = ?",
                settleId, ctx.tenantId);
        if (locked == 0) {
            throw new BizException("结算状态已变化, 请刷新后重试");
        }
        settle.setYbStatus(3);
        return ctx;
    }

    /**
     * T2: 医保撤销交易 2305(无事务)。平台明确拒绝=FAIL(复位可重撤),
     * 超时/网络异常=UNKNOWN(补偿任务挂起, 绝不重发), 成功才返回 SUCCESS 触发 T3 本地落账。
     */
    private CancelOutcome callYbCancel(CancelCtx ctx) {
        SetlCancelReq req = new SetlCancelReq();
        req.setSetlId(ctx.setlId);
        req.setMdtrtId(ctx.visit.getMdtrtId());
        req.setPsnNo(ctx.visit.getPsnNo());
        YbResponse resp = inpatientService.cancelSettlement(req);
        if (resp == null) {
            return CancelOutcome.unknown(null, "医保结算撤销(2305)无响应");
        }
        if (resp.isUnknown()) {
            return CancelOutcome.unknown(findInpTxnLogId(resp), "医保结算撤销(2305)网络异常, 结果未知");
        }
        if (!resp.isSuccess()) {
            return CancelOutcome.fail("医保结算撤销(2305)失败: " + resp.getErrMsg());
        }
        return CancelOutcome.success();
    }

    /** T3: 医保撤销成功本地终态落账(独立事务, 条件更新 yb_status 3->4 防并发/重复落账) */
    private void finalizeCancelSuccess(CancelCtx ctx) {
        txTemplate.executeWithoutResult(st -> {
            int updated = jdbcTemplate.update(
                    "UPDATE his_inp_settle SET yb_status = 4, update_time = NOW()"
                            + " WHERE id = ? AND tenant_id = ? AND yb_status = 3 AND deleted = 0",
                    ctx.settle.getId(), ctx.tenantId);
            if (updated != 1) {
                log.error("住院撤销单终态条件更新失败: settleId={}, affected={}", ctx.settle.getId(), updated);
                throw new BizException("结算单状态已变化, 请刷新后重试");
            }
            ctx.settle.setYbStatus(4);
            applyCancelLocal(ctx, currentStaffId());
            log.info("住院医保结算撤销完成: settleId={}, setlId={}", ctx.settle.getId(), ctx.setlId);
        });
    }

    /**
     * 撤销本地落账(出院/中途共用, 须在事务内): visit 回在院(出院)/保持(中途) + 明细解挂回待结算池 + 预交金冲回+流水。
     * 出院: visit_status 4->2 且清空出院时间(条件更新防并发); 中途: 就诊保持2在院仅解挂本单明细。
     */
    private void applyCancelLocal(CancelCtx ctx, Long operatorId) {
        HisInpVisit visit = ctx.visit;
        HisInpSettle settle = ctx.settle;
        if (!ctx.midSettle) {
            int restored = jdbcTemplate.update(
                    "UPDATE his_inp_visit SET visit_status = 2, discharge_date = NULL, update_time = NOW()"
                            + " WHERE id = ? AND visit_status = 4 AND deleted = 0 AND tenant_id = ?",
                    visit.getId(), ctx.tenantId);
            if (restored == 0) {
                throw new BizException("就诊状态异常(非已出院), 无法撤销结算");
            }
        }
        int unmarked = jdbcTemplate.update(
                "UPDATE his_inp_charge_detail SET settle_id = NULL, update_time = NOW()"
                        + " WHERE settle_id = ? AND deleted = 0 AND tenant_id = ?",
                settle.getId(), ctx.tenantId);
        ctx.unmarkedDetails = unmarked;
        BigDecimal preBalance = nvl(settle.getDepositDeduct()).add(nvl(settle.getRefundAmount()));
        ctx.balanceRestored = preBalance;
        if (preBalance.signum() > 0) {
            jdbcTemplate.update(
                    "UPDATE his_inp_visit SET deposit_balance = IFNULL(deposit_balance, 0) + ?, update_time = NOW()"
                            + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    preBalance, visit.getId(), ctx.tenantId);
            HisInpDeposit flow = new HisInpDeposit();
            flow.setOrgId(visit.getOrgId());
            flow.setInpVisitId(visit.getId());
            flow.setAmount(preBalance);
            flow.setPayType("CASH");
            flow.setDirection(1);
            flow.setBalanceAfter(preBalance);
            flow.setOperatorId(operatorId);
            flow.setRemark("撤销结算(" + settle.getSettleNo() + ")冲回预交金");
            depositMapper.insert(flow);
        }
    }

    /** FAIL(平台明确拒绝撤销): 复位 yb_status 3->2(可重撤), 独立事务 */
    private void markCancelFailed(CancelCtx ctx, CancelOutcome o) {
        txTemplate.executeWithoutResult(st -> {
            jdbcTemplate.update(
                    "UPDATE his_inp_settle SET yb_status = 2, update_time = NOW()"
                            + " WHERE id = ? AND tenant_id = ? AND yb_status = 3 AND deleted = 0",
                    ctx.settle.getId(), ctx.tenantId);
            log.warn("医保结算撤销明确失败, 复位已结算可重试: settleNo={}, 原因: {}", ctx.settle.getSettleNo(), o.errMsg);
        });
        throw new BizException(o.errMsg == null ? "医保结算撤销失败" : o.errMsg);
    }

    /** UNKNOWN(超时/网络异常): 补偿任务独立事务落库, 单据留撤销中(3)挂起, 由 CompTaskSweeper 收敛 */
    private void createCancelCompTaskAndThrow(CancelCtx ctx, CancelOutcome o, String userMsg) {
        txTemplate.executeWithoutResult(st -> {
            HisCompTask task = new HisCompTask();
            task.setBizType(HisCompTask.BIZ_INP_RTN);
            task.setRefId(ctx.settle.getId());
            task.setAction(HisCompTask.ACT_RESOLVE_UNKNOWN);
            task.setTxnLogId(o.txnLogId);
            task.setStatus(HisCompTask.ST_PENDING);
            task.setAttempts(0);
            task.setNextRun(LocalDateTime.now());
            task.setMemo(o.errMsg);
            compTaskMapper.insert(task);
            uploadStatusService.record(ctx.tenantId, HisUploadStatus.BIZ_INP_SETL, ctx.settle.getId(),
                    ctx.visit.getMdtrtId(), false, null, o.errMsg);
        });
        throw new BizException(userMsg);
    }

    /**
     * 补偿收敛入口(CompTaskSweeper 驱动): 住院 UNKNOWN 撤销单终态回填或复位(对齐门诊 resolveUnknownRefund)。
     * cancelled=true(mock 核对平台侧原结算已撤销): 条件置已撤销(3->4)+本地解挂/回状态/冲回;
     * false(平台未撤销原结算): 复位已结算(3->2)允许重新撤销。
     * 调度线程无请求上下文, 显式传 tenantId 并临时设置 TenantContext。
     * @return 收敛结果说明(由补偿任务记入 memo)
     */
    public String resolveUnknownCancel(Long tenantId, Long settleId, boolean cancelled) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, inp_visit_id, settle_type, deposit_deduct, refund_amount, settle_no, operator_id"
                        + " FROM his_inp_settle WHERE id = ? AND tenant_id = ? AND deleted = 0 AND yb_status = 3",
                settleId, tenantId);
        if (rows.isEmpty()) {
            return "撤销中间态已不存在(可能已人工处理)";
        }
        Map<String, Object> r = rows.get(0);
        TenantContext.set(tenantId);
        try {
            return txTemplate.execute(st -> {
                if (!cancelled) {
                    int reverted = jdbcTemplate.update(
                            "UPDATE his_inp_settle SET yb_status = 2, update_time = NOW()"
                                    + " WHERE id = ? AND tenant_id = ? AND yb_status = 3 AND deleted = 0",
                            settleId, tenantId);
                    return reverted == 1 ? "平台未撤销原结算, 已复位为已结算可重试撤销" : "复位失败(结算状态已变化)";
                }
                HisInpSettle settle = new HisInpSettle();
                settle.setId(settleId);
                settle.setSettleNo(str(r.get("settle_no")));
                settle.setDepositDeduct(toBd(r.get("deposit_deduct")));
                settle.setRefundAmount(toBd(r.get("refund_amount")));
                boolean midSettle = ((Number) r.get("settle_type")).intValue() == SETTLE_TYPE_MID;
                Long visitId = toLong(r.get("inp_visit_id"));
                HisInpVisit visit = visitMapper.selectById(visitId);
                CancelCtx ctx = new CancelCtx();
                ctx.tenantId = tenantId;
                ctx.settle = settle;
                ctx.visit = visit;
                ctx.midSettle = midSettle;
                ctx.bedId = visit == null ? null : visit.getBedId();
                int updated = jdbcTemplate.update(
                        "UPDATE his_inp_settle SET yb_status = 4, update_time = NOW()"
                                + " WHERE id = ? AND tenant_id = ? AND yb_status = 3 AND deleted = 0",
                        settleId, tenantId);
                if (updated != 1) {
                    return "回填失败(结算状态已变化)";
                }
                applyCancelLocal(ctx, toLong(r.get("operator_id")));
                log.info("补偿收敛-住院撤销平台已撤销: settleId={}", settleId);
                return "平台已撤销, 撤销终态已补录";
            });
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 供补偿核对(CompTaskSweeper): 取该结算单对应就诊的医保 setl_id(2304 留存),
     * 由 sweeper 据此判定 mock 平台侧 2305 是否已撤销原结算。调度线程无请求上下文, 显式传 tenantId 并临时设置 TenantContext。
     */
    public String findSetlIdForCancel(Long tenantId, Long settleId) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            HisInpSettle settle = settleMapper.selectById(settleId);
            if (settle == null) {
                return null;
            }
            HisInpVisit visit = visitMapper.selectById(settle.getInpVisitId());
            return visit == null ? null : findYbSetlId(visit.getMdtrtId());
        } finally {
            if (outer == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(outer);
            }
        }
    }

    private Map<String, Object> buildCancelResult(CancelCtx ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settle", settleMapper.selectById(ctx.settle.getId()));
        result.put("visit", visitMapper.selectById(ctx.visit.getId()));
        result.put("balanceRestored", ctx.balanceRestored == null ? BigDecimal.ZERO : ctx.balanceRestored);
        result.put("unmarkedDetails", ctx.unmarkedDetails == null ? 0 : ctx.unmarkedDetails);
        result.put("bedNote", ctx.midSettle
                ? "中途结算撤销, 就诊仍在院, 床位不受影响"
                : "床位已释放不会自动恢复, 请通过转床或床位管理为患者重新安排床位");
        return result;
    }

    /** 撤销两阶段 T1->T2->T3 上下文 */
    private static final class CancelCtx {
        long tenantId;
        HisInpSettle settle;
        HisInpVisit visit;
        boolean midSettle;
        boolean selfPayDone;
        String setlId;
        Long bedId;
        Integer unmarkedDetails;
        BigDecimal balanceRestored;
    }

    /** T2 医保撤销结果三分载体 */
    private static final class CancelOutcome {
        boolean success;
        boolean unknown;
        String errMsg;
        Long txnLogId;

        static CancelOutcome success() {
            CancelOutcome o = new CancelOutcome();
            o.success = true;
            return o;
        }

        static CancelOutcome fail(String msg) {
            CancelOutcome o = new CancelOutcome();
            o.errMsg = msg;
            return o;
        }

        static CancelOutcome unknown(Long txnLogId, String msg) {
            CancelOutcome o = fail(msg);
            o.unknown = true;
            o.txnLogId = txnLogId;
            return o;
        }
    }


    /* ==================== 日结 ==================== */

    /** 住院日结统计: 当日结算/出入院/预交金三类汇总(orgId=null 时全机构, 供牵头机构汇总) */
    public Map<String, Object> dailySummary(Long orgId, String date) {
        LocalDate day = parseDate(date);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", day.toString());

        // 1) 当日结算汇总(settle_time 为准)
        StringBuilder setlSql = new StringBuilder(
                "SELECT COUNT(*) cnt, IFNULL(SUM(total_amount), 0) total_amount, IFNULL(SUM(self_pay), 0) self_pay,"
                        + " IFNULL(SUM(fund_pay), 0) fund_pay, IFNULL(SUM(cash_pay), 0) cash_pay,"
                        + " IFNULL(SUM(acct_pay), 0) acct_pay, IFNULL(SUM(deposit_deduct), 0) deposit_deduct,"
                        + " IFNULL(SUM(refund_amount), 0) refund_amount"
                        + " FROM his_inp_settle WHERE DATE(settle_time) = ? AND deleted = 0 AND tenant_id = ?");
        List<Object> setlArgs = new ArrayList<>();
        setlArgs.add(day);
        setlArgs.add(tenantId());
        if (orgId != null) {
            setlSql.append(" AND org_id = ?");
            setlArgs.add(orgId);
        }
        Map<String, Object> setl = jdbcTemplate.queryForMap(setlSql.toString(), setlArgs.toArray());
        Map<String, Object> settleStat = new LinkedHashMap<>();
        settleStat.put("count", ((Number) setl.get("cnt")).longValue());
        settleStat.put("totalAmount", toBd(setl.get("total_amount")));
        settleStat.put("selfPay", toBd(setl.get("self_pay")));
        settleStat.put("fundPay", toBd(setl.get("fund_pay")));
        settleStat.put("cashPay", toBd(setl.get("cash_pay")));
        settleStat.put("acctPay", toBd(setl.get("acct_pay")));
        settleStat.put("depositDeduct", toBd(setl.get("deposit_deduct")));
        settleStat.put("refundAmount", toBd(setl.get("refund_amount")));
        result.put("settle", settleStat);

        // 2) 出入院统计
        StringBuilder vSql = new StringBuilder(
                "SELECT IFNULL(SUM(CASE WHEN DATE(admit_date) = ? AND visit_status <> 5 THEN 1 ELSE 0 END), 0) admit_cnt,"
                        + " IFNULL(SUM(CASE WHEN DATE(discharge_date) = ? AND visit_status = 4 THEN 1 ELSE 0 END), 0) discharge_cnt,"
                        + " IFNULL(SUM(CASE WHEN visit_status = 2 THEN 1 ELSE 0 END), 0) in_hospital_cnt"
                        + " FROM his_inp_visit WHERE deleted = 0 AND tenant_id = ?");
        List<Object> vArgs = new ArrayList<>();
        vArgs.add(day);
        vArgs.add(day);
        vArgs.add(tenantId());
        if (orgId != null) {
            vSql.append(" AND org_id = ?");
            vArgs.add(orgId);
        }
        Map<String, Object> v = jdbcTemplate.queryForMap(vSql.toString(), vArgs.toArray());
        Map<String, Object> visitStat = new LinkedHashMap<>();
        visitStat.put("admitCount", ((Number) v.get("admit_cnt")).longValue());
        visitStat.put("dischargeCount", ((Number) v.get("discharge_cnt")).longValue());
        visitStat.put("inHospitalCount", ((Number) v.get("in_hospital_cnt")).longValue());
        result.put("visit", visitStat);

        // 3) 当日预交金收退汇总(create_time 为准)
        StringBuilder dSql = new StringBuilder(
                "SELECT IFNULL(SUM(CASE WHEN direction = 1 THEN 1 ELSE 0 END), 0) pay_cnt,"
                        + " IFNULL(SUM(CASE WHEN direction = 1 THEN amount ELSE 0 END), 0) pay_amount,"
                        + " IFNULL(SUM(CASE WHEN direction = 2 THEN 1 ELSE 0 END), 0) refund_cnt,"
                        + " IFNULL(SUM(CASE WHEN direction = 2 THEN amount ELSE 0 END), 0) refund_amount"
                        + " FROM his_inp_deposit WHERE DATE(create_time) = ? AND deleted = 0 AND tenant_id = ?");
        List<Object> dArgs = new ArrayList<>();
        dArgs.add(day);
        dArgs.add(tenantId());
        if (orgId != null) {
            dSql.append(" AND org_id = ?");
            dArgs.add(orgId);
        }
        Map<String, Object> d = jdbcTemplate.queryForMap(dSql.toString(), dArgs.toArray());
        Map<String, Object> depositStat = new LinkedHashMap<>();
        depositStat.put("payCount", ((Number) d.get("pay_cnt")).longValue());
        depositStat.put("payAmount", toBd(d.get("pay_amount")));
        depositStat.put("refundCount", ((Number) d.get("refund_cnt")).longValue());
        depositStat.put("refundAmount", toBd(d.get("refund_amount")));
        result.put("deposit", depositStat);
        return result;
    }

    /* ==================== 工具 ==================== */

    /** 就诊校验(预结算要求在院或出院办理中) */
    private HisInpVisit requireVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        if (visit.getVisitStatus() == null || (visit.getVisitStatus() != 2 && visit.getVisitStatus() != 3)) {
            throw new BizException("该就诊不在院或未提交出院申请, 无法预结算");
        }
        return visit;
    }

    /** 是否走医保链路(psn_no 与 mdtrt_id 齐全) */
    private static boolean hasYbIdentity(HisInpVisit visit) {
        return StringUtils.hasText(visit.getPsnNo()) && StringUtils.hasText(visit.getMdtrtId());
    }

    /** 汇总就诊费用(全量 status=1 正常明细, 出院结算校准 total_cost 用) */
    private BigDecimal sumCharges(Long visitId) {
        BigDecimal total = jdbcTemplate.queryForObject(
                "SELECT IFNULL(SUM(amount), 0) FROM his_inp_charge_detail"
                        + " WHERE inp_visit_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?",
                BigDecimal.class, visitId, tenantId());
        return total == null ? BigDecimal.ZERO : total;
    }

    /** 最近一次有效结算的业务日期(含出院/中途, 排除已撤销; 无记录返回null, 用于分次结算区间起点) */
    private LocalDate lastSettleDate(Long visitId) {
        List<java.sql.Date> rows = jdbcTemplate.queryForList(
                "SELECT MAX(DATE(settle_time)) FROM his_inp_settle"
                        + " WHERE inp_visit_id = ? AND settle_type IN (1, 2) AND yb_status <> 4"
                        + " AND deleted = 0 AND tenant_id = ?",
                java.sql.Date.class, visitId, tenantId());
        if (rows.isEmpty() || rows.get(0) == null) {
            return null;
        }
        return rows.get(0).toLocalDate();
    }

    /** 区间起点缺省值: 入院日期(异常缺失时取很早日期兜底, 保证不漏费用) */
    private static LocalDate admitLocalDate(HisInpVisit visit) {
        return visit.getAdmitDate() != null ? visit.getAdmitDate().toLocalDate() : LocalDate.of(2000, 1, 1);
    }

    /**
     * 查询结算范围内未结算明细(status=1 且 settle_id IS NULL):
     * 中途结算按 charge_date BETWEEN start AND end 隔离区间; 出院结算(区间为null)取全部未结算。
     */
    private List<HisInpChargeDetail> queryUnsettledDetails(Long visitId, LocalDate start, LocalDate end) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM his_inp_charge_detail WHERE inp_visit_id = ? AND status = 1 AND deleted = 0"
                        + " AND tenant_id = ? AND settle_id IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(visitId);
        args.add(tenantId());
        if (start != null && end != null) {
            sql.append(" AND charge_date BETWEEN ? AND ?");
            args.add(start);
            args.add(end);
        }
        return jdbcTemplate.query(sql.toString(), (rs, i) -> {
            HisInpChargeDetail d = new HisInpChargeDetail();
            d.setId(rs.getLong("id"));
            d.setChargeItemId(rs.getObject("charge_item_id") == null ? null : rs.getLong("charge_item_id"));
            d.setOrderId(rs.getObject("order_id") == null ? null : rs.getLong("order_id"));
            d.setQuantity(rs.getBigDecimal("quantity"));
            d.setUnitPrice(rs.getBigDecimal("unit_price"));
            d.setAmount(rs.getBigDecimal("amount"));
            d.setChargeDate(rs.getDate("charge_date") == null ? null : rs.getDate("charge_date").toLocalDate());
            d.setFeeType(rs.getObject("fee_type") == null ? null : rs.getInt("fee_type"));
            return d;
        }, args.toArray());
    }

    /** 结算范围内未结算明细汇总(status=1 且 settle_id IS NULL; 区间非空时按记账日期隔离) */
    private BigDecimal sumUnsettled(Long visitId, LocalDate start, LocalDate end) {
        StringBuilder sql = new StringBuilder(
                "SELECT IFNULL(SUM(amount), 0) FROM his_inp_charge_detail"
                        + " WHERE inp_visit_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ? AND settle_id IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(visitId);
        args.add(tenantId());
        if (start != null && end != null) {
            sql.append(" AND charge_date BETWEEN ? AND ?");
            args.add(start);
            args.add(end);
        }
        BigDecimal total = jdbcTemplate.queryForObject(sql.toString(), BigDecimal.class, args.toArray());
        return total == null ? BigDecimal.ZERO : total;
    }

    /**
     * 构建2301上传明细: 回填 his_charge_item.medListCodg(医共体统一对照编码),
     * 无医保编码的自费明细不上传(与门诊2204口径一致, 金额计入全自费)。
     */
    private List<FeeDetailReq> buildFeeDetails(HisInpVisit visit, List<HisInpChargeDetail> details) {
        // 批量回填收费项目医保编码(chargeItemId -> medListCodg)
        Map<Long, String[]> ybCodes = new HashMap<>();
        List<Long> itemIds = new ArrayList<>();
        for (HisInpChargeDetail d : details) {
            if (d.getChargeItemId() != null) {
                itemIds.add(d.getChargeItemId());
            }
        }
        if (!itemIds.isEmpty()) {
            String in = StringUtils.collectionToCommaDelimitedString(itemIds);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, med_list_codg, medins_list_codg FROM his_charge_item"
                            + " WHERE id IN (" + in + ") AND deleted = 0 AND tenant_id = " + tenantId());
            for (Map<String, Object> row : rows) {
                ybCodes.put(((Number) row.get("id")).longValue(),
                        new String[]{str(row.get("med_list_codg")), str(row.get("medins_list_codg"))});
            }
        }
        List<FeeDetailReq> feeDetails = new ArrayList<>();
        for (HisInpChargeDetail d : details) {
            String[] codes = d.getChargeItemId() == null ? null : ybCodes.get(d.getChargeItemId());
            String medListCodg = codes == null ? null : codes[0];
            if (!StringUtils.hasText(medListCodg)) {
                continue;
            }
            FeeDetailReq f = new FeeDetailReq();
            f.setFeedetlSn(String.valueOf(d.getId()));
            f.setMdtrtId(visit.getMdtrtId());
            f.setPsnNo(visit.getPsnNo());
            f.setMedType(StringUtils.hasText(visit.getMedType()) ? visit.getMedType() : MED_TYPE_INPATIENT);
            f.setFeeOcurTime((d.getChargeDate() == null ? LocalDate.now() : d.getChargeDate()) + " 00:00:00");
            f.setMedListCodg(medListCodg);
            if (codes != null && StringUtils.hasText(codes[1])) {
                f.setMedinsListCodg(codes[1]);
            }
            f.setDetItemFeeSumamt(d.getAmount());
            f.setCnt(d.getQuantity());
            f.setPric(d.getUnitPrice());
            if (d.getOrderId() != null) {
                f.setDrordNo(String.valueOf(d.getOrderId()));
            }
            feeDetails.add(f);
        }
        return feeDetails;
    }

    /** 组装2303/2304结算请求(与门诊2207口径一致: psn_setlway=01, medType 缺省住院21) */
    private SettlementReq buildSettlementReq(HisInpVisit visit, BigDecimal totalAmount) {
        SettlementReq req = new SettlementReq();
        req.setPsnNo(visit.getPsnNo());
        req.setMdtrtId(visit.getMdtrtId());
        req.setInsutype(visit.getInsutype());
        req.setMedType(StringUtils.hasText(visit.getMedType()) ? visit.getMedType() : MED_TYPE_INPATIENT);
        req.setMedfeeSumamt(totalAmount);
        req.setPsnSetlway("01");
        return req;
    }

    /** 回查医保结算ID(setl_record: 住院2304已结算的最新一条) */
    private String findYbSetlId(String mdtrtId) {
        List<SetlRecord> records = setlRecordMapper.selectList(
                new LambdaQueryWrapper<SetlRecord>()
                        .eq(SetlRecord::getMdtrtId, mdtrtId)
                        .eq(SetlRecord::getBizType, "inpatient")
                        .eq(SetlRecord::getInfno, "2304")
                        .eq(SetlRecord::getStatus, "1")
                        .orderByDesc(SetlRecord::getId));
        return records.isEmpty() ? null : records.get(0).getSetlId();
    }

    /** 结算单号: ST + yyMMddHHmmss + 3位序号 */
    private String genSettleNo() {
        int s = SEQ.incrementAndGet() % 1000;
        return "ST" + DateUtil.currentTimeCompact() + String.format("%03d", s);
    }

    /** 患者姓名(his_patient, 查不到返回空串) */
    private String patientName(Long patientId) {
        if (patientId == null) {
            return "";
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT name FROM his_patient WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                String.class, patientId, tenantId());
        return names.isEmpty() ? "" : names.get(0);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    /** Object 转 Long(补偿收敛从 queryForList 行取值, 无请求上下文) */
    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    /** 当前登录用户关联职工ID(操作员留痕) */
    private static Long currentStaffId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getStaffId();
    }

    /** 解析 yyyy-MM-dd(空返回当天, 格式错误抛400) */
    private static LocalDate parseDate(String d) {
        if (!StringUtils.hasText(d)) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(d.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(400, "日期格式错误, 应为 yyyy-MM-dd: " + d);
        }
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
