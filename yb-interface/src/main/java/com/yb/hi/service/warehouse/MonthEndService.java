package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.warehouse.MonthEndReq;
import com.yb.hi.entity.warehouse.HisDrugPriceAdjust;
import com.yb.hi.entity.warehouse.HisStockIn;
import com.yb.hi.entity.warehouse.HisStockMonthEnd;
import com.yb.hi.entity.warehouse.HisStockOut;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.warehouse.HisDrugPriceAdjustMapper;
import com.yb.hi.mapper.warehouse.HisStockInMapper;
import com.yb.hi.mapper.warehouse.HisStockMonthEndMapper;
import com.yb.hi.mapper.warehouse.HisStockOutMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库房月结 + 账簿查询服务(批次D)。
 * monthEnd: 校验本期入库/出库/调价单是否均已确认(有草稿则拦截), 起始须衔接上次月结终止+1, 按记账标准(进价/零售)
 *   汇总财务账(期初/期末/收入/支出金额)与实物账(期初/期末/入/出数量)落库。
 * unmonthEnd: 仅允许取消最后一个月结期(逐月不跳月)。
 * bookReport: 复用 StockLedgerService 台账, 按记账标准输出收发存/账簿(财务账金额+实物账数量分离)。
 */
@Slf4j
@Service
public class MonthEndService {

    private final HisStockMonthEndMapper monthEndMapper;
    private final HisStockInMapper stockInMapper;
    private final HisStockOutMapper stockOutMapper;
    private final HisDrugPriceAdjustMapper priceAdjustMapper;
    private final StockLedgerService ledgerService;

    public MonthEndService(HisStockMonthEndMapper monthEndMapper, HisStockInMapper stockInMapper,
                           HisStockOutMapper stockOutMapper, HisDrugPriceAdjustMapper priceAdjustMapper,
                           StockLedgerService ledgerService) {
        this.monthEndMapper = monthEndMapper;
        this.stockInMapper = stockInMapper;
        this.stockOutMapper = stockOutMapper;
        this.priceAdjustMapper = priceAdjustMapper;
        this.ledgerService = ledgerService;
    }

    /* ================= 月结记录查询 ================= */

    public IPage<HisStockMonthEnd> page(Long orgId, Long warehouseId, Integer status, long page, long size) {
        LambdaQueryWrapper<HisStockMonthEnd> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisStockMonthEnd::getOrgId, orgId)
                .eq(warehouseId != null, HisStockMonthEnd::getWarehouseId, warehouseId)
                .eq(status != null, HisStockMonthEnd::getStatus, status)
                .orderByDesc(HisStockMonthEnd::getPeriodEnd).orderByDesc(HisStockMonthEnd::getId);
        return monthEndMapper.selectPage(new Page<>(page, size), w);
    }

    /* ================= 月结 / 取消月结 ================= */

    @SuppressWarnings("unchecked")
    @Transactional(rollbackFor = Exception.class)
    public HisStockMonthEnd monthEnd(MonthEndReq req) {
        Long orgId = req.getOrgId();
        Long warehouseId = req.getWarehouseId();
        int standard = req.getAcctStandard() == null ? 1 : req.getAcctStandard();
        if (standard != 1 && standard != 3) {
            throw new BizException(400, "记账标准仅支持 1进价 或 3零售价(批发价后补)");
        }
        LocalDate start = parseDate(req.getPeriodStart(), "本期起始日");
        LocalDate end = parseDate(req.getPeriodEnd(), "本期终止日");
        if (start == null || end == null || start.isAfter(end)) {
            throw new BizException(400, "本期起始日不得晚于终止日");
        }
        // 衔接校验: 非首次须 = 上次月结终止+1
        HisStockMonthEnd last = lastClosed(orgId, warehouseId);
        if (last != null) {
            LocalDate expect = last.getPeriodEnd().plusDays(1);
            if (!expect.equals(start)) {
                throw new BizException("月结须逐期衔接, 本期起始应为 " + expect + " (上次月结止于 " + last.getPeriodEnd() + ")");
            }
        }
        // 未确认单据拦截
        assertNoUnconfirmed(orgId, warehouseId, start, end);

        // 台账聚合(财务账 + 实物账)
        Map<String, Object> ledger = ledgerService.ledger(orgId, warehouseId, null, start, end);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ledger.get("rows");
        Agg agg = aggregate(rows, standard);

        HisStockMonthEnd me = new HisStockMonthEnd();
        me.setOrgId(orgId);
        me.setWarehouseId(warehouseId);
        me.setPeriodStart(start);
        me.setPeriodEnd(end);
        me.setAcctStandard(standard);
        me.setStatus(1);
        me.setOpeningAmount(agg.openingAmount);
        me.setClosingAmount(agg.closingAmount);
        me.setIncomeAmount(agg.incomeAmount);
        me.setExpenseAmount(agg.expenseAmount);
        me.setOpeningQty(agg.openingQty);
        me.setClosingQty(agg.closingQty);
        me.setInQty(agg.inQty);
        me.setOutQty(agg.outQty);
        me.setDrugCount(rows == null ? 0 : rows.size());
        me.setConfirmBy(currentUserName());
        me.setConfirmTime(LocalDateTime.now());
        me.setRemark(StringUtils.hasText(req.getRemark()) ? req.getRemark().trim() : null);
        monthEndMapper.insert(me);
        log.info("月结完成: orgId={}, warehouseId={}, period={}~{}, standard={}, closingAmount={}",
                orgId, warehouseId, start, end, standard, me.getClosingAmount());
        return monthEndMapper.selectById(me.getId());
    }

    /** 取消月结: 仅最后一个月结期可取消(逐月不跳月) */
    @Transactional(rollbackFor = Exception.class)
    public HisStockMonthEnd unmonthEnd(Long id) {
        HisStockMonthEnd me = monthEndMapper.selectById(id);
        if (me == null) {
            throw new BizException(400, "月结记录不存在");
        }
        if (me.getStatus() == null || me.getStatus() != 1) {
            throw new BizException("仅已月结记录可取消");
        }
        HisStockMonthEnd last = lastClosed(me.getOrgId(), me.getWarehouseId());
        if (last == null || !last.getId().equals(id)) {
            throw new BizException("只能取消最后一个月结期(逐月不跳月), 其后仍有已月结期间");
        }
        me.setStatus(-1);
        monthEndMapper.updateById(me);
        log.info("取消月结: id={}, orgId={}, warehouseId={}, period={}~{}", id, me.getOrgId(), me.getWarehouseId(), me.getPeriodStart(), me.getPeriodEnd());
        return monthEndMapper.selectById(id);
    }

    /* ================= 账簿查询(收发存/财务/保管员) ================= */

    @SuppressWarnings("unchecked")
    public Map<String, Object> bookReport(Long orgId, Long warehouseId, LocalDate begin, LocalDate end, Integer standard) {
        int std = standard == null ? 1 : standard;
        if (std != 1 && std != 3) {
            throw new BizException(400, "记账标准仅支持 1进价 或 3零售价");
        }
        Map<String, Object> ledger = ledgerService.ledger(orgId, warehouseId, null, begin, end);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ledger.get("rows");
        List<Map<String, Object>> enriched = new ArrayList<>();
        Agg agg = new Agg();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                BigDecimal price = priceOf(r, std);
                BigDecimal openQty = toBD(r.get("openingQty"));
                BigDecimal closeQty = toBD(r.get("closingQty"));
                BigDecimal inQty = toBD(r.get("inQty"));
                BigDecimal outQty = toBD(r.get("outQty"));
                Map<String, Object> row = new LinkedHashMap<>(r);
                row.put("unitPrice", price);
                row.put("openingAmount", openQty.multiply(price).setScale(2, RoundingMode.HALF_UP));
                row.put("closingAmount", closeQty.multiply(price).setScale(2, RoundingMode.HALF_UP));
                row.put("inAmountStd", inQty.multiply(price).setScale(2, RoundingMode.HALF_UP));
                row.put("outAmountStd", outQty.multiply(price).setScale(2, RoundingMode.HALF_UP));
                agg.openingQty = agg.openingQty.add(openQty);
                agg.closingQty = agg.closingQty.add(closeQty);
                agg.inQty = agg.inQty.add(inQty);
                agg.outQty = agg.outQty.add(outQty);
                agg.openingAmount = agg.openingAmount.add(openQty.multiply(price));
                agg.closingAmount = agg.closingAmount.add(closeQty.multiply(price));
                agg.incomeAmount = agg.incomeAmount.add(inQty.multiply(price));
                agg.expenseAmount = agg.expenseAmount.add(outQty.multiply(price));
                enriched.add(row);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("drugCount", enriched.size());
        summary.put("openingQty", agg.openingQty);
        summary.put("closingQty", agg.closingQty);
        summary.put("inQty", agg.inQty);
        summary.put("outQty", agg.outQty);
        summary.put("openingAmount", agg.openingAmount.setScale(2, RoundingMode.HALF_UP));
        summary.put("closingAmount", agg.closingAmount.setScale(2, RoundingMode.HALF_UP));
        summary.put("incomeAmount", agg.incomeAmount.setScale(2, RoundingMode.HALF_UP));
        summary.put("expenseAmount", agg.expenseAmount.setScale(2, RoundingMode.HALF_UP));
        summary.put("acctStandard", std);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", enriched);
        out.put("summary", summary);
        return out;
    }

    /* ================= 内部实现 ================= */

    private HisStockMonthEnd lastClosed(Long orgId, Long warehouseId) {
        return monthEndMapper.selectOne(new LambdaQueryWrapper<HisStockMonthEnd>()
                .eq(orgId != null, HisStockMonthEnd::getOrgId, orgId)
                .eq(warehouseId != null, HisStockMonthEnd::getWarehouseId, warehouseId)
                .eq(HisStockMonthEnd::getStatus, 1)
                .orderByDesc(HisStockMonthEnd::getPeriodEnd)
                .orderByDesc(HisStockMonthEnd::getId)
                .last("LIMIT 1"));
    }

    private void assertNoUnconfirmed(Long orgId, Long warehouseId, LocalDate start, LocalDate end) {
        LocalDateTime b = start.atStartOfDay();
        LocalDateTime e = end.atTime(23, 59, 59);
        long inDraft = stockInMapper.selectCount(new LambdaQueryWrapper<HisStockIn>()
                .eq(orgId != null, HisStockIn::getOrgId, orgId)
                .eq(warehouseId != null, HisStockIn::getWarehouseId, warehouseId)
                .eq(HisStockIn::getStatus, 0)
                .between(HisStockIn::getCreateTime, b, e));
        long outDraft = stockOutMapper.selectCount(new LambdaQueryWrapper<HisStockOut>()
                .eq(orgId != null, HisStockOut::getOrgId, orgId)
                .eq(warehouseId != null, HisStockOut::getWarehouseId, warehouseId)
                .eq(HisStockOut::getStatus, 0)
                .between(HisStockOut::getCreateTime, b, e));
        long adjDraft = priceAdjustMapper.selectCount(new LambdaQueryWrapper<HisDrugPriceAdjust>()
                .eq(orgId != null, HisDrugPriceAdjust::getOrgId, orgId)
                .eq(HisDrugPriceAdjust::getStatus, 0)
                .between(HisDrugPriceAdjust::getEffectiveDate, start, end));
        long total = inDraft + outDraft + adjDraft;
        if (total > 0) {
            throw new BizException("本期存在未确认单据(入库草稿" + inDraft + "/出库草稿" + outDraft + "/调价草稿" + adjDraft + "), 请先确认后再月结");
        }
    }

    private BigDecimal priceOf(Map<String, Object> row, int standard) {
        BigDecimal v = standard == 3 ? toBD(row.get("retailPrice")) : toBD(row.get("costPrice"));
        return v;
    }

    private Agg aggregate(List<Map<String, Object>> rows, int standard) {
        Agg a = new Agg();
        if (rows == null) {
            return a;
        }
        for (Map<String, Object> r : rows) {
            BigDecimal price = priceOf(r, standard);
            BigDecimal openQty = toBD(r.get("openingQty"));
            BigDecimal closeQty = toBD(r.get("closingQty"));
            BigDecimal inQty = toBD(r.get("inQty"));
            BigDecimal outQty = toBD(r.get("outQty"));
            a.openingQty = a.openingQty.add(openQty);
            a.closingQty = a.closingQty.add(closeQty);
            a.inQty = a.inQty.add(inQty);
            a.outQty = a.outQty.add(outQty);
            a.openingAmount = a.openingAmount.add(openQty.multiply(price));
            a.closingAmount = a.closingAmount.add(closeQty.multiply(price));
            a.incomeAmount = a.incomeAmount.add(inQty.multiply(price));
            a.expenseAmount = a.expenseAmount.add(outQty.multiply(price));
        }
        a.openingAmount = a.openingAmount.setScale(2, RoundingMode.HALF_UP);
        a.closingAmount = a.closingAmount.setScale(2, RoundingMode.HALF_UP);
        a.incomeAmount = a.incomeAmount.setScale(2, RoundingMode.HALF_UP);
        a.expenseAmount = a.expenseAmount.setScale(2, RoundingMode.HALF_UP);
        return a;
    }

    private static class Agg {
        BigDecimal openingAmount = BigDecimal.ZERO;
        BigDecimal closingAmount = BigDecimal.ZERO;
        BigDecimal incomeAmount = BigDecimal.ZERO;
        BigDecimal expenseAmount = BigDecimal.ZERO;
        BigDecimal openingQty = BigDecimal.ZERO;
        BigDecimal closingQty = BigDecimal.ZERO;
        BigDecimal inQty = BigDecimal.ZERO;
        BigDecimal outQty = BigDecimal.ZERO;
    }

    private static BigDecimal toBD(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    private LocalDate parseDate(String s, String field) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new BizException(400, field + "格式应为 yyyy-MM-dd");
        }
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }
}
