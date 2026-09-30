package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yb.hi.dto.inpatient.InpDepositDTO;
import com.yb.hi.entity.inpatient.HisInpDeposit;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpDepositMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院预交金服务: 缴纳/退还双向流水, balance_after 记录操作后余额便于对账,
 * his_inp_visit.deposit_balance 原子更新(退还带余额充足条件防并发超退)。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class InpDepositService {

    private final HisInpDepositMapper depositMapper;
    private final HisInpVisitMapper visitMapper;
    private final InpFeeAlertService feeAlertService;
    private final JdbcTemplate jdbcTemplate;

    public InpDepositService(HisInpDepositMapper depositMapper, HisInpVisitMapper visitMapper,
                             InpFeeAlertService feeAlertService, JdbcTemplate jdbcTemplate) {
        this.depositMapper = depositMapper;
        this.visitMapper = visitMapper;
        this.feeAlertService = feeAlertService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 缴纳/退还预交金:
     * - 缴纳: 余额原子累加; 退还: 余额原子扣减且 WHERE 余额充足(affected=0 抛"余额不足");
     * - 落流水(balance_after=操作后余额), visit.deposit_balance 同步。
     * 仅未结案就诊(待入院/在院/出院办理中)可操作, 已出院/已取消不可操作。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deposit(InpDepositDTO dto, Long orgId) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (dto.getAmount() == null || dto.getAmount().signum() <= 0) {
            throw new BizException(400, "预交金金额必须大于0");
        }
        if (dto.getDirection() == null || (dto.getDirection() != 1 && dto.getDirection() != 2)) {
            throw new BizException(400, "方向必须为: 1缴纳 2退还");
        }
        if (dto.getPayType() == null) {
            throw new BizException(400, "支付方式不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(dto.getInpVisitId());
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        if (visit.getVisitStatus() == null || visit.getVisitStatus() == 4 || visit.getVisitStatus() == 5) {
            throw new BizException("该就诊已出院或已取消, 不能操作预交金");
        }

        int affected;
        if (dto.getDirection() == 1) {
            // 缴纳: 余额原子累加
            affected = jdbcTemplate.update(
                    "UPDATE his_inp_visit SET deposit_balance = IFNULL(deposit_balance, 0) + ?,"
                            + " update_time = NOW() WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    dto.getAmount(), dto.getInpVisitId(), tenantId());
        } else {
            // 退还: 余额原子扣减, WHERE 余额充足防并发超退
            affected = jdbcTemplate.update(
                    "UPDATE his_inp_visit SET deposit_balance = IFNULL(deposit_balance, 0) - ?,"
                            + " update_time = NOW() WHERE id = ? AND deleted = 0 AND tenant_id = ?"
                            + " AND IFNULL(deposit_balance, 0) >= ?",
                    dto.getAmount(), dto.getInpVisitId(), tenantId(), dto.getAmount());
            if (affected == 0) {
                throw new BizException("预交金余额不足, 当前余额: " + getBalance(dto.getInpVisitId())
                        + ", 退还金额: " + dto.getAmount());
            }
        }
        if (affected == 0) {
            throw new BizException("预交金操作失败, 就诊记录状态已变化");
        }

        // 操作后余额(原子更新后回读, 对账依据)
        BigDecimal balanceAfter = readBalance(dto.getInpVisitId());

        HisInpDeposit flow = new HisInpDeposit();
        flow.setOrgId(orgId);
        flow.setInpVisitId(dto.getInpVisitId());
        flow.setAmount(dto.getAmount());
        flow.setPayType(dto.getPayType());
        flow.setDirection(dto.getDirection());
        flow.setBalanceAfter(balanceAfter);
        flow.setOperatorId(currentStaffId());
        flow.setRemark(StringUtils.hasText(dto.getRemark()) ? dto.getRemark().trim() : null);
        depositMapper.insert(flow);

        log.info("预交金{}: visitId={}, amount={}, balanceAfter={}",
                dto.getDirection() == 1 ? "缴纳" : "退还", dto.getInpVisitId(),
                dto.getAmount(), balanceAfter);

        // 预交金不足预警联动(退还导致余额低于预警线时生成预警, 同类型未处理去重)
        checkDepositWarning(dto.getInpVisitId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flow", flow);
        result.put("balance", balanceAfter);
        return result;
    }

    /** 预交金流水列表(按就诊, 最近的在前) */
    public List<HisInpDeposit> listByVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        return depositMapper.selectList(new LambdaQueryWrapper<HisInpDeposit>()
                .eq(HisInpDeposit::getInpVisitId, visitId)
                .orderByDesc(HisInpDeposit::getId));
    }

    /** 当前预交金余额(visit.deposit_balance, null 按 0) */
    public BigDecimal getBalance(Long visitId) {
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        return visit.getDepositBalance() == null ? BigDecimal.ZERO : visit.getDepositBalance();
    }

    /**
     * 预交金不足预警检查: 余额 < 预警线(且预警线已配置)时生成 type=3 预警, 同就诊未处理预警去重。
     * 供预交金缴纳/退还及记费链路在余额变动后调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void checkDepositWarning(Long visitId) {
        if (visitId == null) {
            return;
        }
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            return;
        }
        BigDecimal warning = visit.getDepositWarningAmount();
        if (warning == null || warning.signum() <= 0) {
            return;
        }
        BigDecimal balance = visit.getDepositBalance() == null ? BigDecimal.ZERO : visit.getDepositBalance();
        if (balance.compareTo(warning) < 0) {
            feeAlertService.createAlert(visitId, 3, null, balance, warning);
            log.warn("预交金不足预警: visitId={}, balance={}, warningLine={}", visitId, balance, warning);
        }
    }

    /** 原子更新后回读余额(JdbcTemplate 直连, 不受一级缓存影响) */
    private BigDecimal readBalance(Long visitId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT IFNULL(deposit_balance, 0) bal FROM his_inp_visit"
                        + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                visitId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        return new BigDecimal(String.valueOf(rows.get(0).get("bal")));
    }

    /** 当前登录用户关联职工ID(操作员留痕) */
    private static Long currentStaffId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getStaffId();
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致) */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
