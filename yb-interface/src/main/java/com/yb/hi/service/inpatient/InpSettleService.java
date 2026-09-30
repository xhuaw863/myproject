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
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.SetlRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpDepositMapper;
import com.yb.hi.mapper.inpatient.HisInpSettleMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.service.InpatientService;
import com.yb.hi.service.SetlResultHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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

    public InpSettleService(HisInpSettleMapper settleMapper, HisInpVisitMapper visitMapper,
                            HisInpDepositMapper depositMapper, InpBedService bedService,
                            InpatientService inpatientService, SetlResultHandler setlResultHandler,
                            SetlRecordMapper setlRecordMapper, JdbcTemplate jdbcTemplate) {
        this.settleMapper = settleMapper;
        this.visitMapper = visitMapper;
        this.depositMapper = depositMapper;
        this.bedService = bedService;
        this.inpatientService = inpatientService;
        this.setlResultHandler = setlResultHandler;
        this.setlRecordMapper = setlRecordMapper;
        this.jdbcTemplate = jdbcTemplate;
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
     * 结算完整流程(出院/中途共用):
     * 出院结算(type=1): 汇总全部未结算费用 -> 乐观占住visit_status(2/3->4, 防并发重复结算) -> 医保链路
     *   (2301明细上传+2304结算) -> 创建his_inp_settle -> 多缴预交金退还流水 -> 回写visit(出院时间/总费用校准/余额清零) -> 释放床位;
     * 中途结算(type=2): 区间隔离(上次结算次日~今日, 仅汇总未结算明细) -> 医保链路(同上) -> 创建his_inp_settle
     *   -> 明细回写settle_id -> 只扣减预交金抵扣额(余额留存), 不改visit_status不释放床位。
     * 医保调用失败抛异常整体回滚(未提交事务内医保侧回执为失败时安全)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settle(InpSettleDTO dto, Long orgId) {
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
        if (midSettle) {
            // 中途结算: 要求在院(2), 不改状态不释放床位; 行锁串行化同一就诊的并发结算(无状态翻转, 乐观锁不可用)
            if (visit.getVisitStatus() == null || visit.getVisitStatus() != 2) {
                throw new BizException("该就诊不在院(状态=" + visit.getVisitStatus() + "), 不能办理中途结算");
            }
            jdbcTemplate.queryForObject(
                    "SELECT id FROM his_inp_visit WHERE id = ? AND deleted = 0 AND tenant_id = ? FOR UPDATE",
                    Long.class, visitId, tenantId());
        } else if (visit.getVisitStatus() == null || (visit.getVisitStatus() != 2 && visit.getVisitStatus() != 3)) {
            throw new BizException("该就诊不在院或未提交出院申请(状态=" + visit.getVisitStatus() + "), 不能结算");
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

        // 出院结算先乐观占住状态(2/3->4): 并发重复结算在此被拦截; 后续失败整体回滚(中途结算不改状态)
        if (!midSettle) {
            int locked = jdbcTemplate.update(
                    "UPDATE his_inp_visit SET visit_status = 4, update_time = NOW()"
                            + " WHERE id = ? AND visit_status IN (2, 3) AND deleted = 0 AND tenant_id = ?",
                    visitId, tenantId());
            if (locked == 0) {
                throw new BizException("该就诊已被结算或状态已变化, 请刷新后重试");
            }
        }

        // 医保链路: 2301明细上传(有医保编码的明细, 无编码自费行不上传与门诊2204口径一致) -> 2304结算
        // 零金额出院结算(费用已全部中途结清)不调医保, 避免0元结算请求被拒
        boolean ybFlag = hasYbIdentity(visit) && totalAmount.signum() > 0;
        BigDecimal fundPay = BigDecimal.ZERO;
        BigDecimal acctPay = BigDecimal.ZERO;
        BigDecimal selfPay = totalAmount;
        int ybStatus = 0;
        // DRG/DIP 分组与支付方式(2304 setlinfo 扩展键解析暂存, 自费/缺失为空)
        String ybDrgCode = null;
        String ybDipCode = null;
        Integer ybPayMethod = null;
        if (ybFlag) {
            List<FeeDetailReq> feeDetails = buildFeeDetails(visit, details);
            if (!feeDetails.isEmpty()) {
                YbResponse feeResp = inpatientService.uploadFeeDetail(feeDetails);
                if (feeResp == null || !feeResp.isSuccess()) {
                    String err = feeResp == null ? "医保无响应" : feeResp.getErrMsg();
                    throw new BizException("医保费用明细上传(2301)失败: " + err);
                }
            }
            SettlementReq req = buildSettlementReq(visit, totalAmount);
            YbResponse resp = inpatientService.settlement(req);
            if (resp == null || !resp.isSuccess()) {
                String err = resp == null ? "医保无响应" : resp.getErrMsg();
                throw new BizException("医保结算(2304)失败: " + err);
            }
            ybStatus = 2;
            SetlInfoResult setlInfo = setlResultHandler.parse(resp);
            if (setlInfo != null) {
                fundPay = setlInfo.getFundPaySumamt() == null ? BigDecimal.ZERO : setlInfo.getFundPaySumamt();
                acctPay = setlInfo.getAcctPay() == null ? BigDecimal.ZERO : setlInfo.getAcctPay();
                selfPay = setlInfo.getPsnPartAmt() == null
                        ? totalAmount.subtract(fundPay) : setlInfo.getPsnPartAmt();
            }
            // DRG/DIP 分组与支付方式解析(2304 setlinfo 扩展键, 多键名兼容; 缺失不阻断结算)
            JSONObject setlNode = resp.getOutputNode("setlinfo");
            if (setlNode != null) {
                ybDrgCode = firstText(setlNode, "drgGroupCode", "drg_group_code", "drgCode", "drg_code");
                ybDipCode = firstText(setlNode, "dipCode", "dip_code");
                ybPayMethod = firstInt(setlNode, "payMethod", "pay_method", "clrWay", "clr_way");
            }
        }

        // 院内落账口径: 现金应付=个人负担-个账; 预交金抵扣=min(余额,现金应付);
        // 出院结算多缴退还(余额清零), 中途结算余额留存(患者仍在院, 后续费用继续使用)
        BigDecimal cashOwed = selfPay.subtract(acctPay).max(BigDecimal.ZERO);
        BigDecimal depositDeduct = depositBalance.min(cashOwed);
        BigDecimal cashPay = cashOwed.subtract(depositDeduct);
        BigDecimal refundAmount = midSettle ? BigDecimal.ZERO : depositBalance.subtract(depositDeduct);

        HisInpSettle settle = new HisInpSettle();
        settle.setOrgId(orgId);
        settle.setInpVisitId(visitId);
        settle.setSettleNo(genSettleNo());
        settle.setTotalAmount(totalAmount);
        settle.setSelfPay(selfPay);
        settle.setFundPay(fundPay);
        settle.setCashPay(cashPay);
        settle.setAcctPay(acctPay);
        settle.setDepositDeduct(depositDeduct);
        settle.setRefundAmount(refundAmount);
        settle.setSettleType(settleType);
        settle.setYbStatus(ybStatus);
        // DRG/DIP 分组与支付方式(来源: 2304结算返回 setlinfo 扩展键; 自费患者为空)
        settle.setDrgGroupCode(ybDrgCode);
        settle.setDipCode(ybDipCode);
        settle.setPayMethod(ybPayMethod);
        settle.setSettleTime(LocalDateTime.now());
        settle.setOperatorId(currentStaffId());
        settleMapper.insert(settle);

        // 多缴预交金退还流水(余额清零)
        if (refundAmount.signum() > 0) {
            HisInpDeposit flow = new HisInpDeposit();
            flow.setOrgId(orgId);
            flow.setInpVisitId(visitId);
            flow.setAmount(refundAmount);
            flow.setPayType(1);
            flow.setDirection(2);
            flow.setBalanceAfter(BigDecimal.ZERO);
            flow.setOperatorId(currentStaffId());
            flow.setRemark("出院结算(" + settle.getSettleNo() + ")退还多缴预交金");
            depositMapper.insert(flow);
        }

        // 费用明细挂结算单: 结算范围内未结算明细回写 settle_id(支撑按结算单追溯费用组成)
        String markSql = "UPDATE his_inp_charge_detail SET settle_id = ?, update_time = NOW()"
                + " WHERE inp_visit_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ? AND settle_id IS NULL"
                + (midSettle ? " AND charge_date BETWEEN ? AND ?" : "");
        if (midSettle) {
            jdbcTemplate.update(markSql, settle.getId(), visitId, tenantId(), scopeStart, scopeEnd);
        } else {
            jdbcTemplate.update(markSql, settle.getId(), visitId, tenantId());
        }

        if (midSettle) {
            // 中途结算: 仅扣减预交金抵扣额, 状态保持2在院, 不释放床位, 不覆盖累计费用
            jdbcTemplate.update(
                    "UPDATE his_inp_visit SET deposit_balance = IFNULL(deposit_balance, 0) - ?, update_time = NOW()"
                            + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    depositDeduct, visitId, tenantId());
        } else {
            // 出院结算: 出院时间/累计费用校准(全量正常明细)/余额清零(状态已在前面临优占用为4)
            BigDecimal allCost = sumCharges(visitId);
            jdbcTemplate.update(
                    "UPDATE his_inp_visit SET discharge_date = NOW(), total_cost = ?, deposit_balance = 0,"
                            + " update_time = NOW() WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    allCost, visitId, tenantId());
            // 释放床位(结算出院后床位回到空床池)
            if (visit.getBedId() != null) {
                bedService.releaseBed(visit.getBedId());
            }
        }
        log.info("住院结算完成: visitId={}, settleNo={}, 类型={}(1出院/2中途), 区间={}~{}, total={}, selfPay={}, fundPay={}, depositDeduct={}, refund={}",
                visitId, settle.getSettleNo(), settleType, scopeStart, scopeEnd,
                totalAmount, selfPay, fundPay, depositDeduct, refundAmount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settle", settle);
        result.put("cashPay", cashPay);
        result.put("refundAmount", refundAmount);
        result.put("ybFlag", ybFlag);
        result.put("settleType", settleType);
        result.put("scopeStart", scopeStart);
        result.put("scopeEnd", scopeEnd);
        return result;
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
     * 撤销结算(出院/中途共用): 乐观占住结算状态 -> 医保患者回查setl_record取setl_id调2305撤销
     * -> 结算单置已撤销(4) -> 出院: visit回在院(2, 出院时间清空); 中途: 就诊状态保持2在院
     * -> 费用明细解挂(settle_id置空, 费用回待结算池) -> 预交金余额恢复并落冲回流水。
     * 床位不自动恢复: 原床位可能已被新患者占用, 请通过转床或床位管理重新安排(中途撤销未释放床位不受影响)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelSettle(Long settleId) {
        HisInpSettle settle = settleMapper.selectById(settleId);
        if (settle == null) {
            throw new BizException(404, "结算记录不存在");
        }
        if (settle.getYbStatus() != null && settle.getYbStatus() == 4) {
            throw new BizException("该结算已撤销, 不能重复撤销");
        }
        if (settle.getYbStatus() != null && settle.getYbStatus() == 3) {
            throw new BizException("该结算撤销处理中, 请勿重复操作");
        }
        boolean midSettle = settle.getSettleType() != null && settle.getSettleType() == SETTLE_TYPE_MID;
        HisInpVisit visit = visitMapper.selectById(settle.getInpVisitId());
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        // 先乐观占住结算状态(0/2->4): 并发重复撤销在此被拦截
        int locked = jdbcTemplate.update(
                "UPDATE his_inp_settle SET yb_status = 4, update_time = NOW()"
                        + " WHERE id = ? AND yb_status IN (0, 2) AND deleted = 0 AND tenant_id = ?",
                settleId, tenantId());
        if (locked == 0) {
            throw new BizException("结算状态已变化, 请刷新后重试");
        }

        // 医保链路撤销: 回查 setl_record(2304已结算留存)取 setl_id 调 2305
        if (hasYbIdentity(visit)) {
            String setlId = findYbSetlId(visit.getMdtrtId());
            if (!StringUtils.hasText(setlId)) {
                throw new BizException("未找到该就诊的医保结算记录(setl_id), 无法撤销, 请核对医保结算流水");
            }
            SetlCancelReq req = new SetlCancelReq();
            req.setSetlId(setlId);
            req.setMdtrtId(visit.getMdtrtId());
            req.setPsnNo(visit.getPsnNo());
            YbResponse resp = inpatientService.cancelSettlement(req);
            if (resp == null || !resp.isSuccess()) {
                String err = resp == null ? "医保无响应" : resp.getErrMsg();
                throw new BizException("医保结算撤销(2305)失败: " + err);
            }
        }

        // visit 状态处理: 出院结算撤销回在院(2, 出院时间清空); 中途结算撤销就诊保持2在院(不重复改状态)
        if (midSettle) {
            if (visit.getVisitStatus() == null || visit.getVisitStatus() != 2) {
                throw new BizException("该就诊不在院(状态=" + visit.getVisitStatus() + "), 无法撤销中途结算");
            }
        } else {
            int restored = jdbcTemplate.update(
                    "UPDATE his_inp_visit SET visit_status = 2, discharge_date = NULL, update_time = NOW()"
                            + " WHERE id = ? AND visit_status = 4 AND deleted = 0 AND tenant_id = ?",
                    visit.getId(), tenantId());
            if (restored == 0) {
                throw new BizException("就诊状态异常(非已出院), 无法撤销结算");
            }
        }

        // 费用明细解挂: 本次结算单关联明细 settle_id 置空, 费用回到待结算池(支撑撤销后重新结算不漏费用)
        int unmarked = jdbcTemplate.update(
                "UPDATE his_inp_charge_detail SET settle_id = NULL, update_time = NOW()"
                        + " WHERE settle_id = ? AND deleted = 0 AND tenant_id = ?",
                settleId, tenantId());

        BigDecimal preBalance = nvl(settle.getDepositDeduct()).add(nvl(settle.getRefundAmount()));
        if (preBalance.signum() > 0) {
            jdbcTemplate.update(
                    "UPDATE his_inp_visit SET deposit_balance = IFNULL(deposit_balance, 0) + ?, update_time = NOW()"
                            + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    preBalance, visit.getId(), tenantId());
            // 冲回流水(对账): 方向=缴纳, 金额=结算前余额, 余额=恢复后余额
            HisInpDeposit flow = new HisInpDeposit();
            flow.setOrgId(visit.getOrgId());
            flow.setInpVisitId(visit.getId());
            flow.setAmount(preBalance);
            flow.setPayType(1);
            flow.setDirection(1);
            flow.setBalanceAfter(preBalance);
            flow.setOperatorId(currentStaffId());
            flow.setRemark("撤销结算(" + settle.getSettleNo() + ")冲回预交金");
            depositMapper.insert(flow);
        }
        log.info("撤销住院结算: settleId={}, settleNo={}, visitId={}, 类型={}(1出院/2中途), 明细解挂={}条, 恢复预交金余额={}",
                settleId, settle.getSettleNo(), visit.getId(), settle.getSettleType(), unmarked, preBalance);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settle", settleMapper.selectById(settleId));
        result.put("visit", visitMapper.selectById(visit.getId()));
        result.put("balanceRestored", preBalance);
        result.put("unmarkedDetails", unmarked);
        result.put("bedNote", midSettle
                ? "中途结算撤销, 就诊仍在院, 床位不受影响"
                : "床位已释放不会自动恢复, 请通过转床或床位管理为患者重新安排床位");
        return result;
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
