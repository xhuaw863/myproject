package com.yb.hi.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S-4(2026-10-03 安全审计): 长期医嘱逐日记账定时任务。
 * <p>背景: 住院费用明细表 his_inp_charge_detail 设计上即"逐日记账"(医嘱驱动+固定费, 结算前汇总),
 * 但既往仅临时医嘱(order_type=2)开立时一次性记账, 长期医嘱(order_type=1)执行链路(护士 batchExecute)
 * 全程不产生费用明细 —— 导致护理/治疗等按天发生的长期医嘱漏计费(延续上轮 P0-4)。
 * <p>本任务每个自然日拾取"活动长期医嘱"(已审核/执行中/当日停嘱, 单价非空, 频次非 prn),
 * 在其有效区间 [开始日 .. min(停嘱日, 记账日)] 内为每个尚未记账的自然日补生成一条当日费用明细,
 * 并原子累加就诊 total_cost; 停嘱次日之后不再拾取。幂等键 (order_id, charge_date, status=1),
 * 故 cron 与手动补记重复触发均不会重复计费; 服务停机漏跑的日期会在下次运行自动回填。
 * <p>多租户: 调度线程无请求上下文, 租户插件不作用于 jdbcTemplate, 故原生 SQL 一律显式带 tenant_id;
 * 定时扫全部租户(tenantFilter=null), 手动触发按登录租户收敛(tenantFilter 非空, 禁止跨租户补记)。
 */
@Slf4j
@Component
public class InpLongOrderChargeSweeper {

    /** 长期医嘱 */
    private static final int ORDER_TYPE_LONG = 1;

    /** 频次 -> 每日执行次数(与 InpOrderExecService.FREQ_PLAN 时间点个数同口径); 未知频次按每日 1 次 */
    private static final Map<String, Integer> FREQ_DAILY_COUNT;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("qd", 1);
        m.put("bid", 2);
        m.put("tid", 3);
        m.put("qid", 4);
        m.put("q6h", 4);
        FREQ_DAILY_COUNT = Collections.unmodifiableMap(m);
    }

    /** 单次回填每医嘱最多回溯天数(防御: 避免异常 start_time 触发超长循环) */
    private static final int MAX_BACKFILL_DAYS = 400;

    private final JdbcTemplate jdbcTemplate;

    public InpLongOrderChargeSweeper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 每日 00:05 自动拾取(对全部租户); 可用 yb.inp.long-order-charge-cron 覆盖 */
    @Scheduled(cron = "${yb.inp.long-order-charge-cron:0 5 0 * * *}")
    public void sweep() {
        try {
            Map<String, Object> res = postForDate(LocalDate.now(), null);
            int posted = toInt(res.get("posted"));
            if (posted > 0) {
                log.info("长期医嘱逐日记账: day={}, 新增明细={}条, 涉及医嘱={}条, 金额合计={}",
                        res.get("day"), posted, res.get("orders"), res.get("amount"));
            }
        } catch (Exception e) {
            log.error("长期医嘱逐日记账执行异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 对指定记账日回填活动长期医嘱的当日/历史缺失费用明细。
     *
     * @param day          记账日(含)之前的有效区间将逐日补记; 一般传当天
     * @param tenantFilter 非空时仅处理该租户(手动触发按登录租户收敛); 为空则扫全部租户(定时任务)
     * @return {day, scanned, orders, posted, amount}
     */
    public Map<String, Object> postForDate(LocalDate day, Long tenantFilter) {
        List<Object> args = new ArrayList<>(Arrays.asList(day, day));
        String tenantClause = "";
        if (tenantFilter != null) {
            tenantClause = " AND o.tenant_id = ?";
            args.add(tenantFilter);
        }
        // 拾取区间与记账日相交、且落在计费窗口内的活动长期医嘱(跨全部所需字段一次取回, 避免逐条回查)
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT o.id, o.tenant_id AS tenantId, o.org_id AS orgId, o.inp_visit_id AS inpVisitId,"
                        + " o.charge_item_id AS chargeItemId, o.order_content AS orderContent,"
                        + " o.quantity, o.unit_price AS unitPrice, o.order_category AS orderCategory,"
                        + " o.freq_code AS freqCode, o.doctor_id AS doctorId,"
                        + " DATE(o.start_time) AS startDate, DATE(o.stop_time) AS stopDate"
                        + " FROM his_inp_order o"
                        + " WHERE o.deleted = 0 AND o.order_type = " + ORDER_TYPE_LONG
                        + " AND o.order_status IN (2, 3, 5)"
                        + " AND o.unit_price IS NOT NULL AND o.inp_visit_id IS NOT NULL"
                        + " AND LOWER(IFNULL(o.freq_code, '')) <> 'prn'"
                        + " AND DATE(o.start_time) <= ?"
                        + " AND (o.stop_time IS NULL OR DATE(o.stop_time) >= ?)"
                        + tenantClause
                        + " ORDER BY o.tenant_id, o.id",
                args.toArray());

        LocalDate from0 = day.minusDays(MAX_BACKFILL_DAYS);
        int posted = 0;
        int touchedOrders = 0;
        BigDecimal sumAmount = BigDecimal.ZERO;
        for (Map<String, Object> o : orders) {
            Long orderId = toLong(o.get("id"));
            Long tenantId = toLong(o.get("tenantId"));
            LocalDate from = toLocalDate(o.get("startDate"));
            LocalDate stop = toLocalDate(o.get("stopDate"));
            if (from == null || from.isAfter(day)) {
                continue;
            }
            if (from.isBefore(from0)) {
                from = from0; // 防御: 过老的 start_time 不回溯无限天
            }
            LocalDate to = (stop != null && stop.isBefore(day)) ? stop : day;
            int times = dailyTimes(o.get("freqCode"));
            BigDecimal perDoseQty = o.get("quantity") == null ? BigDecimal.ONE : toBigDecimal(o.get("quantity"));
            BigDecimal unitPrice = toBigDecimal(o.get("unitPrice"));
            BigDecimal dailyQty = perDoseQty.multiply(BigDecimal.valueOf(times));
            BigDecimal amount = unitPrice.multiply(dailyQty).setScale(2, RoundingMode.HALF_UP);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            boolean postedThisOrder = false;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (dayExists(orderId, d, tenantId)) {
                    continue;
                }
                insertCharge(o, d, dailyQty, unitPrice, amount);
                accumulateTotalCost(o.get("inpVisitId"), amount, tenantId);
                posted++;
                postedThisOrder = true;
                sumAmount = sumAmount.add(amount);
            }
            if (postedThisOrder) {
                touchedOrders++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.toString());
        out.put("scanned", orders.size());
        out.put("orders", touchedOrders);
        out.put("posted", posted);
        out.put("amount", sumAmount.toPlainString());
        return out;
    }

    /** 该医嘱当日是否已有正常费用明细(幂等判定, 含手动与定时重复触发) */
    private boolean dayExists(Long orderId, LocalDate chargeDate, Long tenantId) {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_inp_charge_detail"
                        + " WHERE order_id = ? AND charge_date = ? AND status = 1 AND deleted = 0 AND tenant_id = ?",
                Long.class, orderId, chargeDate, tenantId);
        return cnt != null && cnt > 0;
    }

    /** 生成并写入一条当日费用明细(单价×日数量, 记账日=d, 状态1正常; id 由 DB 自增, tenant_id 显式携带) */
    private void insertCharge(Map<String, Object> o, LocalDate chargeDate,
                              BigDecimal dailyQty, BigDecimal unitPrice, BigDecimal amount) {
        jdbcTemplate.update(
                "INSERT INTO his_inp_charge_detail"
                        + " (tenant_id, org_id, inp_visit_id, charge_item_id, item_name, item_code,"
                        + "  quantity, unit_price, amount, charge_date, order_id, fee_type, status,"
                        + "  operator_id, create_by, create_time, update_time, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, 1, ?, 'system-daily-charge', NOW(), NOW(), 0)",
                toLong(o.get("tenantId")), toLong(o.get("orgId")), toLong(o.get("inpVisitId")),
                toLong(o.get("chargeItemId")), str(o.get("orderContent")),
                dailyQty, unitPrice, amount, chargeDate, toLong(o.get("id")),
                mapFeeType(toInteger(o.get("orderCategory"))), toLong(o.get("doctorId")));
    }

    /** 就诊总费用原子累加(与 InpOrderService.accumulateTotalCost 同口径; 金额为纯数字无注入面) */
    private void accumulateTotalCost(Object visitIdObj, BigDecimal amount, Long tenantId) {
        Long visitId = toLong(visitIdObj);
        if (visitId == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE his_inp_visit SET total_cost = IFNULL(total_cost, 0) + ?, update_time = NOW()"
                        + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                amount, visitId, tenantId);
    }

    /** 频次 -> 每日次数(小写匹配, 未知按 1; prn 已在候选过滤排除) */
    private static int dailyTimes(Object freqCode) {
        String freq = freqCode == null ? "" : String.valueOf(freqCode).trim().toLowerCase();
        Integer n = FREQ_DAILY_COUNT.get(freq);
        return n == null ? 1 : n;
    }

    /** 医嘱分类 -> 费用类别(1药品 2检查 3检验 4治疗 5护理 6膳食 -> 1西药 3检查 4检验 5治疗 6护理 9其他), 与 InpOrderService 同映射 */
    private static int mapFeeType(Integer orderCategory) {
        if (orderCategory == null) {
            return 9;
        }
        switch (orderCategory) {
            case 1: return 1;
            case 2: return 3;
            case 3: return 4;
            case 4: return 5;
            case 5: return 6;
            case 6: return 9;
            default: return 9;
        }
    }

    /* ---------- 类型转换小工具(queryForList 返回 Map<String,Object>) ---------- */

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInteger(Object v) {
        Long l = toLong(v);
        return l == null ? null : l.intValue();
    }

    private static int toInt(Object v) {
        Long l = toLong(v);
        return l == null ? 0 : l.intValue();
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Date) {
            return ((Date) v).toLocalDate();
        }
        if (v instanceof LocalDate) {
            return (LocalDate) v;
        }
        String s = v.toString();
        if (s.length() >= 10) {
            s = s.substring(0, 10);
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
