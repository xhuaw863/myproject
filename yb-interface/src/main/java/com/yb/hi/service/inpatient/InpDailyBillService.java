package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpDailyBill;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpChargeDetailMapper;
import com.yb.hi.mapper.inpatient.HisInpDailyBillMapper;
import com.yb.hi.mapper.inpatient.HisInpDepositMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 住院每日费用清单服务: 单患者/全院批量生成、查询与打印留痕。
 * 生成口径:
 * - 幂等: 就诊×日期一份(uk_daily_bill 唯一键), 已生成直接返回既有清单, 批量时计为 skipped;
 * - 当日明细 = his_inp_charge_detail(inp_visit_id + charge_date, status=1), items 落 JSON 数组;
 * - 在院累计 = SUM(amount) WHERE charge_date <= 清单日期(status=1);
 * - 预交金余额取 his_inp_visit.deposit_balance(生成时点的最新余额);
 * - 批量逐单独立落库(单条失败不中断批次, 计入 failed)。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class InpDailyBillService {

    private final HisInpDailyBillMapper dailyBillMapper;
    private final HisInpChargeDetailMapper chargeMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisInpDepositMapper depositMapper;
    private final JdbcTemplate jdbcTemplate;

    public InpDailyBillService(HisInpDailyBillMapper dailyBillMapper, HisInpChargeDetailMapper chargeMapper,
                               HisInpVisitMapper visitMapper, HisInpDepositMapper depositMapper,
                               JdbcTemplate jdbcTemplate) {
        this.dailyBillMapper = dailyBillMapper;
        this.chargeMapper = chargeMapper;
        this.visitMapper = visitMapper;
        this.depositMapper = depositMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 生成 ==================== */

    /** 为某患者生成某日费用清单(幂等: 已生成直接返回既有记录)。 */
    @Transactional(rollbackFor = Exception.class)
    public R<HisInpDailyBill> generateDailyBill(Long visitId, LocalDate date) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (date == null) {
            throw new BizException(400, "清单日期不能为空");
        }
        HisInpVisit visit = requireVisit(visitId);
        HisInpDailyBill exist = findBill(visitId, date);
        if (exist != null) {
            return R.ok(exist);
        }
        return R.ok(createBill(visit, date));
    }

    /**
     * 全院批量生成某日清单: 查询在院患者(visit_status=2)逐个生成。
     * 返回 {total, success, skipped(已生成), failed}。
     */
    public R<Map<String, Object>> batchGenerate(LocalDate date, Long orgId) {
        if (date == null) {
            throw new BizException(400, "清单日期不能为空");
        }
        Long targetOrg = orgId != null ? orgId : currentOrgId();
        List<HisInpVisit> visits = visitMapper.selectList(new LambdaQueryWrapper<HisInpVisit>()
                .eq(HisInpVisit::getVisitStatus, 2)
                .eq(HisInpVisit::getOrgId, targetOrg)
                .orderByAsc(HisInpVisit::getId));
        // 该日已生成的就诊(幂等跳过)
        Set<Long> existing = new HashSet<>();
        for (HisInpDailyBill b : dailyBillMapper.selectList(new LambdaQueryWrapper<HisInpDailyBill>()
                .eq(HisInpDailyBill::getBillDate, date)
                .eq(HisInpDailyBill::getOrgId, targetOrg))) {
            if (b.getInpVisitId() != null) {
                existing.add(b.getInpVisitId());
            }
        }
        int success = 0;
        int skipped = 0;
        int failed = 0;
        for (HisInpVisit v : visits) {
            if (existing.contains(v.getId())) {
                skipped++;
                continue;
            }
            try {
                createBill(v, date);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("住院日清单生成失败: visitId={}, date={}, cause={}", v.getId(), date, e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("orgId", targetOrg);
        result.put("total", visits.size());
        result.put("success", success);
        result.put("skipped", skipped);
        result.put("failed", failed);
        log.info("住院日清单批量生成完成: date={}, orgId={}, 在院{}人, 新生成{}, 已有{}, 失败{}",
                date, targetOrg, visits.size(), success, skipped, failed);
        return R.ok(result);
    }

    /* ==================== 查询 ==================== */

    /** 获取某患者某日清单(未生成报404)。 */
    public R<HisInpDailyBill> getDailyBill(Long visitId, LocalDate date) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (date == null) {
            throw new BizException(400, "清单日期不能为空");
        }
        HisInpDailyBill bill = findBill(visitId, date);
        if (bill == null) {
            throw new BizException(404, "该日费用清单未生成: " + date + ", 请先调用生成接口");
        }
        return R.ok(bill);
    }

    /** 患者清单分页列表(按清单日期倒序, 每页行数上限200)。 */
    public R<IPage<HisInpDailyBill>> listByVisit(Long visitId, long page, long size) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        int p = page < 1 ? 1 : (int) Math.min(page, Integer.MAX_VALUE);
        int s = size < 1 ? 20 : (int) Math.min(size, 200);
        IPage<HisInpDailyBill> result = dailyBillMapper.selectPage(new Page<>(p, s),
                new LambdaQueryWrapper<HisInpDailyBill>()
                        .eq(HisInpDailyBill::getInpVisitId, visitId)
                        .orderByDesc(HisInpDailyBill::getBillDate));
        return R.ok(result);
    }

    /* ==================== 打印留痕 ==================== */

    /** 标记已打印(printed_flag=1 + 打印时间)。 */
    public R<Void> markPrinted(Long id) {
        if (id == null) {
            throw new BizException(400, "清单ID不能为空");
        }
        HisInpDailyBill bill = dailyBillMapper.selectById(id);
        if (bill == null) {
            throw new BizException(404, "费用清单不存在");
        }
        dailyBillMapper.update(null, new LambdaUpdateWrapper<HisInpDailyBill>()
                .eq(HisInpDailyBill::getId, id)
                .set(HisInpDailyBill::getPrintedFlag, 1)
                .set(HisInpDailyBill::getPrintTime, LocalDateTime.now()));
        return R.ok();
    }

    /* ==================== 内部实现 ==================== */

    /** 就诊×日期查既有清单(uk_daily_bill 幂等判据)。 */
    private HisInpDailyBill findBill(Long visitId, LocalDate date) {
        return dailyBillMapper.selectOne(new LambdaQueryWrapper<HisInpDailyBill>()
                .eq(HisInpDailyBill::getInpVisitId, visitId)
                .eq(HisInpDailyBill::getBillDate, date)
                .last("LIMIT 1"));
    }

    /** 组装并落库一份日清单(调用方保证该就诊×日期未生成)。 */
    private HisInpDailyBill createBill(HisInpVisit visit, LocalDate date) {
        List<HisInpChargeDetail> details = chargeMapper.selectList(new LambdaQueryWrapper<HisInpChargeDetail>()
                .eq(HisInpChargeDetail::getInpVisitId, visit.getId())
                .eq(HisInpChargeDetail::getChargeDate, date)
                .eq(HisInpChargeDetail::getStatus, 1)
                .orderByAsc(HisInpChargeDetail::getId));
        JSONArray items = new JSONArray();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (HisInpChargeDetail d : details) {
            JSONObject it = new JSONObject();
            it.put("itemName", d.getItemName());
            it.put("itemCode", d.getItemCode());
            it.put("feeType", d.getFeeType());
            it.put("quantity", d.getQuantity());
            it.put("unitPrice", d.getUnitPrice());
            it.put("amount", d.getAmount());
            items.add(it);
            totalAmount = totalAmount.add(nvl(d.getAmount()));
        }
        // 在院累计费用(含当日及以前全部正常明细)
        BigDecimal cumulative = jdbcTemplate.queryForObject(
                "SELECT IFNULL(SUM(amount), 0) FROM his_inp_charge_detail"
                        + " WHERE inp_visit_id = ? AND charge_date <= ? AND status = 1 AND deleted = 0 AND tenant_id = ?",
                BigDecimal.class, visit.getId(), date, tenantId());
        HisInpDailyBill bill = new HisInpDailyBill();
        bill.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : currentOrgId());
        bill.setInpVisitId(visit.getId());
        bill.setBillDate(date);
        bill.setItems(items.toJSONString());
        bill.setTotalAmount(totalAmount);
        bill.setCumulativeAmount(cumulative == null ? BigDecimal.ZERO : cumulative);
        bill.setDepositBalance(nvl(visit.getDepositBalance()));
        bill.setGeneratedTime(LocalDateTime.now());
        bill.setPrintedFlag(0);
        dailyBillMapper.insert(bill);
        log.info("生成住院日清单: visitId={}, date={}, 当日{}项/{}元, 累计{}元, 预交金余额{}元",
                visit.getId(), date, items.size(), totalAmount, bill.getCumulativeAmount(),
                bill.getDepositBalance());
        return bill;
    }

    /** 就诊存在性校验。 */
    private HisInpVisit requireVisit(Long visitId) {
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        return visit;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 当前登录机构ID(未登录回落0)。 */
    private static Long currentOrgId() {
        LoginUser lu = UserContext.get();
        return lu == null || lu.getOrgId() == null ? 0L : lu.getOrgId();
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致)。 */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
