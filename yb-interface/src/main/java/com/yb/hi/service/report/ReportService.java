package com.yb.hi.service.report;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表服务(纯查询聚合, 不建Entity直查已有表)。
 * 本服务走 JdbcTemplate, 不经过 MyBatis-Plus 租户插件, 故所有 SQL 显式携带
 * tenant_id(取自 {@link TenantContext})与 deleted=0 条件。
 * 机构维度说明: his_registration/his_visit/setl_record 为租户级表(无 org_id 列),
 * 其余业务表(his_charge_bill/his_dispense/his_daily_settle/his_drug_stock/his_stock_in/his_stock_out/
 * his_drug_return/his_invoice)支持 orgId 过滤; orgId=null 表示全部(牵头机构视角)。
 * 日期区间缺省最近30天; 时间列统一在 SQL 内 DATE_FORMAT 成字符串, 避免驱动/Jackson 时区差异。
 */
@Service
public class ReportService {

    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ============================== Dashboard 概览 ==============================

    /**
     * Dashboard 概览: 今日/昨日/本月(至今)三组运营指标, 前端以 yesterday 计算环比。
     * 返回: {today:{regCount,visitCount,chargeCount,chargeAmount,dispenseCount}, yesterday:{...}, month:{...}}
     */
    public Map<String, Object> dashboardOverview(Long orgId) {
        LocalDate today = LocalDate.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", snapshot(orgId, today, today.plusDays(1)));
        out.put("yesterday", snapshot(orgId, today.minusDays(1), today));
        out.put("month", snapshot(orgId, today.withDayOfMonth(1), today.plusDays(1)));
        return out;
    }

    /** 区间运营快照(左闭右开): 挂号数/就诊数(未取消)/收费笔数与金额(已收费门诊单)/发药笔数 */
    private Map<String, Object> snapshot(Long orgId, LocalDate start, LocalDate end) {
        Long tid = TenantContext.require();
        String s = start.toString();
        String e = end.toString();
        Map<String, Object> m = new LinkedHashMap<>();
        // his_registration 无 org_id 列, 挂号数为租户级口径
        m.put("regCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_registration WHERE tenant_id=? AND deleted=0 AND reg_time>=? AND reg_time<?",
                Long.class, tid, s, e));
        // his_visit 无 org_id 列, 就诊数按就诊日期且排除已取消
        m.put("visitCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM his_visit WHERE tenant_id=? AND deleted=0 AND work_date>=? AND work_date<? AND visit_status<>4",
                Long.class, tid, s, e));
        StringBuilder cs = new StringBuilder("SELECT COUNT(*) AS cnt, IFNULL(SUM(total_amount),0) AS amt "
                + "FROM his_charge_bill WHERE tenant_id=? AND deleted=0 AND bill_type=1 AND status=1 "
                + "AND charge_time>=? AND charge_time<?");
        List<Object> ca = new ArrayList<>(Arrays.asList(tid, s, e));
        if (orgId != null) {
            cs.append(" AND org_id=?");
            ca.add(orgId);
        }
        Map<String, Object> charge = jdbc.queryForMap(cs.toString(), ca.toArray());
        m.put("chargeCount", charge.get("cnt"));
        m.put("chargeAmount", charge.get("amt"));
        StringBuilder ds = new StringBuilder("SELECT COUNT(*) FROM his_dispense "
                + "WHERE tenant_id=? AND deleted=0 AND dispense_time>=? AND dispense_time<?");
        List<Object> da = new ArrayList<>(Arrays.asList(tid, s, e));
        if (orgId != null) {
            ds.append(" AND org_id=?");
            da.add(orgId);
        }
        m.put("dispenseCount", jdbc.queryForObject(ds.toString(), Long.class, da.toArray()));
        return m;
    }

    // ============================== 收入趋势 / 就诊量 ==============================

    /**
     * 收入趋势聚合: 只统计 bill_type=1(门诊收费) AND status=1(已收费)。
     * granularity: day=按天 / week=按ISO周(返回周一日期) / month=按月, 其他值按天。
     * 返回: [{date:"2026-09-01", amount:12345.00, count:25}, ...]
     */
    public List<Map<String, Object>> revenueStats(Long orgId, String startDate, String endDate, String granularity) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        String expr = timeExpr("charge_time", granularity);
        StringBuilder sql = new StringBuilder("SELECT ").append(expr).append(" AS `date`, ")
                .append("IFNULL(SUM(total_amount),0) AS amount, COUNT(*) AS `count` ")
                .append("FROM his_charge_bill WHERE tenant_id=? AND deleted=0 AND bill_type=1 AND status=1 ")
                .append("AND charge_time>=? AND charge_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) {
            sql.append(" AND org_id=?");
            args.add(orgId);
        }
        sql.append(" GROUP BY ").append(expr).append(" ORDER BY `date`");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 就诊量统计(his_visit, 排除已取消): granularity=day/week/month, 按就诊日期 work_date 闭区间统计。
     * 返回: [{date:"2026-09-20", count:35}, ...] (his_visit 为租户级表, orgId 不参与过滤)
     */
    public List<Map<String, Object>> visitStats(Long orgId, String startDate, String endDate, String granularity) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        String expr = timeExpr("work_date", granularity);
        String sql = "SELECT " + expr + " AS `date`, COUNT(*) AS `count` "
                + "FROM his_visit WHERE tenant_id=? AND deleted=0 AND visit_status<>4 AND work_date>=? AND work_date<=?"
                + " GROUP BY " + expr + " ORDER BY `date`";
        return jdbc.queryForList(sql, tid, range[0], range[1]);
    }

    /** 时间分组表达式: day=按天 / week=按ISO周取周一日期 / month=按月, 其他值按天(列名为白名单, 无注入风险) */
    private static String timeExpr(String column, String granularity) {
        String g = StringUtils.hasText(granularity) ? granularity.trim().toLowerCase() : "day";
        switch (g) {
            case "week":
                return "DATE_FORMAT(DATE_SUB(" + column + ", INTERVAL WEEKDAY(" + column + ") DAY), '%Y-%m-%d')";
            case "month":
                return "DATE_FORMAT(" + column + ", '%Y-%m')";
            default:
                return "DATE_FORMAT(" + column + ", '%Y-%m-%d')";
        }
    }

    // ============================== 科室收入 / 药品使用 ==============================

    /**
     * 科室收入排名: 已收费门诊单 JOIN his_visit 取科室, 按金额降序, ratio=该科室金额/总金额(4位小数)。
     * 返回: [{deptName:"内科", amount:50000.00, count:120, ratio:0.35}, ...]
     */
    public List<Map<String, Object>> deptRevenueStats(Long orgId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        StringBuilder sql = new StringBuilder("SELECT IFNULL(v.dept_name,'未知科室') AS deptName, ")
                .append("IFNULL(SUM(b.total_amount),0) AS amount, COUNT(*) AS `count` ")
                .append("FROM his_charge_bill b JOIN his_visit v ON v.id=b.visit_id AND v.tenant_id=b.tenant_id AND v.deleted=0 ")
                .append("WHERE b.tenant_id=? AND b.deleted=0 AND b.bill_type=1 AND b.status=1 ")
                .append("AND b.charge_time>=? AND b.charge_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) {
            sql.append(" AND b.org_id=?");
            args.add(orgId);
        }
        sql.append(" GROUP BY v.dept_name ORDER BY amount DESC");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            total = total.add((BigDecimal) r.get("amount"));
        }
        for (Map<String, Object> r : rows) {
            BigDecimal amount = (BigDecimal) r.get("amount");
            r.put("ratio", total.signum() == 0 ? 0.0 : amount.divide(total, 4, RoundingMode.HALF_UP).doubleValue());
        }
        return rows;
    }

    /**
     * 药品使用排名 TOP N: 收费明细(item_type=1药品) JOIN 已收费门诊单, 按金额降序。
     * 返回: [{drugName:"阿莫西林", qty:500, amount:25000.00}, ...]
     */
    public List<Map<String, Object>> drugUsageStats(Long orgId, String startDate, String endDate, int topN) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        int limit = topN <= 0 ? 10 : Math.min(topN, 200);
        StringBuilder sql = new StringBuilder("SELECT i.item_name AS drugName, IFNULL(SUM(i.qty),0) AS qty, ")
                .append("IFNULL(SUM(i.amount),0) AS amount ")
                .append("FROM his_charge_bill_item i JOIN his_charge_bill b ON b.id=i.bill_id AND b.tenant_id=i.tenant_id AND b.deleted=0 ")
                .append("WHERE i.tenant_id=? AND i.deleted=0 AND i.item_type=1 AND b.bill_type=1 AND b.status=1 ")
                .append("AND b.charge_time>=? AND b.charge_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) {
            sql.append(" AND b.org_id=?");
            args.add(orgId);
        }
        sql.append(" GROUP BY i.item_name ORDER BY amount DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ============================== 结算记录 ==============================

    /**
     * 结算记录分页: type=inpatient 查医保住院结算留存(setl_record, 本地收费单无住院业务);
     * 其余(outpatient/null) 查本地收费单 his_charge_bill LEFT JOIN his_visit(补科室/医生)。
     * keyword 匹配患者姓名或单号; 返回列 billNo/patientName/billType/totalAmount/selfPay/fundPay/acctPay/
     * chargeBy/chargeTime/status/deptName/drName/bizType(outpatient|inpatient)。
     */
    public IPage<Map<String, Object>> settleRecordPage(Long orgId, String type, String startDate, String endDate,
                                                       String keyword, long page, long size) {
        if ("inpatient".equalsIgnoreCase(type == null ? null : type.trim())) {
            return inpatientSettlePage(startDate, endDate, keyword, page, size);
        }
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        StringBuilder where = new StringBuilder(" FROM his_charge_bill b")
                .append(" LEFT JOIN his_visit v ON v.id=b.visit_id AND v.tenant_id=b.tenant_id AND v.deleted=0")
                .append(" WHERE b.tenant_id=? AND b.deleted=0 AND b.charge_time>=? AND b.charge_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) {
            where.append(" AND b.org_id=?");
            args.add(orgId);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (b.patient_name LIKE ? OR b.bill_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        String cols = "SELECT b.bill_no AS billNo, b.patient_name AS patientName, b.bill_type AS billType,"
                + " b.total_amount AS totalAmount, b.self_pay AS selfPay, b.fund_pay AS fundPay, b.acct_pay AS acctPay,"
                + " b.charge_by AS chargeBy, DATE_FORMAT(b.charge_time, '%Y-%m-%d %H:%i:%s') AS chargeTime, b.status AS status,"
                + " v.dept_name AS deptName, v.dr_name AS drName, 'outpatient' AS bizType";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        long[] ps = normPage(page, size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                cols + where + " ORDER BY b.charge_time DESC, b.id DESC LIMIT ?, ?",
                appendArgs(args, (ps[0] - 1) * ps[1], ps[1]));
        return pageOf(rows, total, ps[0], ps[1]);
    }

    /** 住院结算分页(setl_record biz_type='inpatient', 租户级表无 org_id 列, orgId 过滤不生效) */
    private IPage<Map<String, Object>> inpatientSettlePage(String startDate, String endDate, String keyword,
                                                           long page, long size) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        StringBuilder where = new StringBuilder(" FROM setl_record s")
                .append(" WHERE s.tenant_id=? AND s.biz_type='inpatient' AND s.crte_time>=? AND s.crte_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (s.psn_name LIKE ? OR s.setl_id LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        String cols = "SELECT s.setl_id AS billNo, s.psn_name AS patientName, NULL AS billType,"
                + " s.medfee_sumamt AS totalAmount, s.psn_part_amt AS selfPay, s.fund_pay_sumamt AS fundPay, s.acct_pay AS acctPay,"
                + " NULL AS chargeBy, IFNULL(s.setl_time, DATE_FORMAT(s.crte_time, '%Y-%m-%d %H:%i:%s')) AS chargeTime,"
                + " CAST(s.status AS SIGNED) AS status, NULL AS deptName, NULL AS drName, 'inpatient' AS bizType";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        long[] ps = normPage(page, size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                cols + where + " ORDER BY s.crte_time DESC, s.id DESC LIMIT ?, ?",
                appendArgs(args, (ps[0] - 1) * ps[1], ps[1]));
        return pageOf(rows, total, ps[0], ps[1]);
    }

    /**
     * 结算记录导出(head/rows/total 供 EasyExcel): 门诊收费单(含退费) + 住院医保结算, 按时间降序。
     * 区间缺省最近30天; 门诊部分受 orgId 过滤, 住院部分为租户级口径。
     */
    public Map<String, Object> exportSettleRecords(Long orgId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        StringBuilder sql = new StringBuilder("SELECT b.bill_no AS billNo, b.patient_name AS patientName, b.bill_type AS billType,")
                .append(" b.total_amount AS totalAmount, b.self_pay AS selfPay, b.fund_pay AS fundPay, b.acct_pay AS acctPay,")
                .append(" b.charge_by AS chargeBy, DATE_FORMAT(b.charge_time, '%Y-%m-%d %H:%i:%s') AS chargeTime, b.status AS status,")
                .append(" v.dept_name AS deptName, v.dr_name AS drName")
                .append(" FROM his_charge_bill b LEFT JOIN his_visit v ON v.id=b.visit_id AND v.tenant_id=b.tenant_id AND v.deleted=0")
                .append(" WHERE b.tenant_id=? AND b.deleted=0 AND b.charge_time>=? AND b.charge_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) {
            sql.append(" AND b.org_id=?");
            args.add(orgId);
        }
        sql.append(" ORDER BY b.charge_time DESC, b.id DESC");
        List<Map<String, Object>> bills = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> setls = jdbc.queryForList(
                "SELECT s.setl_id AS billNo, s.psn_name AS patientName, NULL AS billType,"
                + " s.medfee_sumamt AS totalAmount, s.psn_part_amt AS selfPay, s.fund_pay_sumamt AS fundPay, s.acct_pay AS acctPay,"
                + " IFNULL(s.setl_time, DATE_FORMAT(s.crte_time, '%Y-%m-%d %H:%i:%s')) AS chargeTime,"
                + " CAST(s.status AS SIGNED) AS status"
                + " FROM setl_record s WHERE s.tenant_id=? AND s.biz_type='inpatient' AND s.crte_time>=? AND s.crte_time<?"
                + " ORDER BY s.crte_time DESC, s.id DESC",
                tid, range[0], nextDay(range[1]));

        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"收费单号", "患者姓名", "类型", "总金额", "自付金额", "基金支付", "个账支付",
                "收费员", "收费时间", "状态", "科室", "医生"}) {
            head.add(Collections.singletonList(h));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> b : bills) {
            rows.add(Arrays.asList(
                    text(b.get("billNo")), text(b.get("patientName")), billTypeText(b.get("billType")),
                    b.get("totalAmount"), b.get("selfPay"), b.get("fundPay"), b.get("acctPay"),
                    text(b.get("chargeBy")), text(b.get("chargeTime")), chargeStatusText(b.get("status")),
                    text(b.get("deptName")), text(b.get("drName"))));
        }
        for (Map<String, Object> r : setls) {
            rows.add(Arrays.asList(
                    text(r.get("billNo")), text(r.get("patientName")), "住院结算",
                    r.get("totalAmount"), r.get("selfPay"), r.get("fundPay"), r.get("acctPay"),
                    "", text(r.get("chargeTime")), setlStatusText(r.get("status")), "", ""));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    // ============================== 日结记录 ==============================

    /** 日结记录分页(his_daily_settle 直查, 暂无收费员维度过滤): 按结算日期降序, 区间缺省最近30天 */
    public IPage<Map<String, Object>> dailySettlePage(Long orgId, String startDate, String endDate, long page, long size) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        StringBuilder where = new StringBuilder(" FROM his_daily_settle WHERE tenant_id=? AND deleted=0 AND settle_date>=? AND settle_date<=?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], range[1]));
        if (orgId != null) {
            where.append(" AND org_id=?");
            args.add(orgId);
        }
        String cols = "SELECT DATE_FORMAT(settle_date, '%Y-%m-%d') AS settleDate, operator, total_count AS totalCount,"
                + " total_amount AS totalAmount, refund_count AS refundCount, refund_amount AS refundAmount,"
                + " cash_total AS cashTotal, fund_total AS fundTotal, acct_total AS acctTotal,"
                + " reg_count AS regCount, reg_amount AS regAmount,"
                + " wechat_total AS wechatTotal, alipay_total AS alipayTotal, card_total AS cardTotal, free_total AS freeTotal,"
                + " status, DATE_FORMAT(settle_time, '%Y-%m-%d %H:%i:%s') AS settleTime";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        long[] ps = normPage(page, size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                cols + where + " ORDER BY settle_date DESC, id DESC LIMIT ?, ?",
                appendArgs(args, (ps[0] - 1) * ps[1], ps[1]));
        return pageOf(rows, total, ps[0], ps[1]);
    }

    /**
     * 日结挂号费预览(P1-15): 按 his_reg_payment 汇总当日挂号/退号净额与全渠道分项,
     * 口径对齐 CashierService.dailySettle 的挂号侧聚合(方向1收款/-1退款)。
     */
    public Map<String, Object> regPaymentPreview(Long orgId, String date) {
        Long tid = TenantContext.require();
        if (!StringUtils.hasText(date)) {
            throw new BizException(400, "日期不能为空(yyyy-MM-dd)");
        }
        StringBuilder where = new StringBuilder(
                " FROM his_reg_payment WHERE tenant_id=? AND deleted=0 AND biz_time>=? AND biz_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, date + " 00:00:00", date + " 23:59:59"));
        if (orgId != null) {
            where.append(" AND org_id=?");
            args.add(orgId);
        }
        return jdbc.queryForMap(
                "SELECT"
                        + " IFNULL(SUM(direction), 0) AS regCount,"
                        + " IFNULL(SUM(direction * amount), 0) AS regAmount,"
                        + " IFNULL(SUM(CASE WHEN pay_method = 'WECHAT' THEN direction * amount ELSE 0 END), 0) AS wechatTotal,"
                        + " IFNULL(SUM(CASE WHEN pay_method = 'ALIPAY' THEN direction * amount ELSE 0 END), 0) AS alipayTotal,"
                        + " IFNULL(SUM(CASE WHEN pay_method = 'CARD' THEN direction * amount ELSE 0 END), 0) AS cardTotal,"
                        + " IFNULL(SUM(CASE WHEN pay_method = 'FREE' THEN direction * amount ELSE 0 END), 0) AS freeTotal"
                        + where, args.toArray());
    }

    // ============================== 医生工作日志 ==============================

    /**
     * 医生工作量汇总: 为避免多表 LEFT JOIN 聚合行膨胀, 分三步查询(就诊/处方/检查单)再按 staffId 在 Java 侧合并,
     * 处方/检查单无匹配的医生填0。his_visit/his_prescription/his_order 均为租户级表(无 org_id 列),
     * orgId 仅保留签名一致性不参与过滤; 就诊口径排除已取消(visit_status=4)。
     * 返回: [{staffId, drName, deptName, visitCount, finishCount, finishRate:"XX.X%",
     *        rxCount, rxAmount, orderCount, orderAmount}](按接诊数降序)
     */
    public List<Map<String, Object>> doctorWorklogSummary(Long orgId, String startDate, String endDate,
                                                          Long staffId, Long deptId) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        // 步骤1: 就诊汇总(按医师分组, 完成数=visit_status=3)
        StringBuilder vs = new StringBuilder("SELECT v.staff_id AS staffId, v.dr_name AS drName, v.dept_name AS deptName,")
                .append(" COUNT(*) AS visitCount, SUM(CASE WHEN v.visit_status=3 THEN 1 ELSE 0 END) AS finishCount")
                .append(" FROM his_visit v")
                .append(" WHERE v.tenant_id=? AND v.deleted=0 AND v.visit_status<>4")
                .append(" AND v.work_date>=? AND v.work_date<=?");
        List<Object> va = new ArrayList<>(Arrays.asList(tid, range[0], range[1]));
        if (staffId != null) {
            vs.append(" AND v.staff_id=?");
            va.add(staffId);
        }
        if (deptId != null) {
            vs.append(" AND v.dept_id=?");
            va.add(deptId);
        }
        vs.append(" GROUP BY v.staff_id, v.dr_name, v.dept_name ORDER BY visitCount DESC");
        List<Map<String, Object>> rows = jdbc.queryForList(vs.toString(), va.toArray());
        // 步骤2/3: 处方与检查单按开单医生(dr_id)汇总(JOIN his_visit 限定日期区间与未取消就诊)
        Map<Long, Map<String, Object>> rx = worklogItemAgg("his_prescription", "p", "rxCount", "rxAmount",
                tid, range, staffId, deptId);
        Map<Long, Map<String, Object>> od = worklogItemAgg("his_order", "o", "orderCount", "orderAmount",
                tid, range, staffId, deptId);
        // Java侧合并: 无匹配填0, 并补完成率(XX.X%)
        for (Map<String, Object> r : rows) {
            // 候诊/未分配医师的就诊 staff_id 为 NULL, 不能直接拆箱(否则整个接口 NPE)
            Object sidObj = r.get("staffId");
            long visitCount = ((Number) r.get("visitCount")).longValue();
            long finishCount = r.get("finishCount") == null ? 0L : ((Number) r.get("finishCount")).longValue();
            r.put("visitCount", visitCount);
            r.put("finishCount", finishCount);
            r.put("finishRate", (visitCount == 0 ? BigDecimal.ZERO.setScale(1)
                    : BigDecimal.valueOf(finishCount).multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(visitCount), 1, RoundingMode.HALF_UP)).toPlainString() + "%");
            if (sidObj == null) {
                r.put("rxCount", 0L);
                r.put("rxAmount", BigDecimal.ZERO);
                r.put("orderCount", 0L);
                r.put("orderAmount", BigDecimal.ZERO);
                continue;
            }
            long sid = ((Number) sidObj).longValue();
            Map<String, Object> rxRow = rx.get(sid);
            Map<String, Object> odRow = od.get(sid);
            r.put("rxCount", rxRow == null ? 0L : ((Number) rxRow.get("rxCount")).longValue());
            r.put("rxAmount", rxRow == null ? BigDecimal.ZERO : rxRow.get("rxAmount"));
            r.put("orderCount", odRow == null ? 0L : ((Number) odRow.get("orderCount")).longValue());
            r.put("orderAmount", odRow == null ? BigDecimal.ZERO : odRow.get("orderAmount"));
        }
        return rows;
    }

    /** 处方/检查单按开单医生聚合(JOIN his_visit 限定区间且排除已取消就诊, 有效单 status>0): 返回 staffId → {countCol, amountCol} */
    private Map<Long, Map<String, Object>> worklogItemAgg(String table, String alias, String countCol, String amountCol,
                                                          Long tid, String[] range, Long staffId, Long deptId) {
        StringBuilder sql = new StringBuilder("SELECT ").append(alias).append(".dr_id AS staffId,")
                .append(" COUNT(*) AS ").append(countCol)
                .append(", IFNULL(SUM(").append(alias).append(".total_amount),0) AS ").append(amountCol)
                .append(" FROM ").append(table).append(" ").append(alias)
                .append(" JOIN his_visit v ON v.id=").append(alias).append(".visit_id AND v.tenant_id=")
                .append(alias).append(".tenant_id AND v.deleted=0")
                .append(" WHERE ").append(alias).append(".tenant_id=? AND ").append(alias).append(".deleted=0 AND ")
                .append(alias).append(".status>0")
                .append(" AND v.work_date>=? AND v.work_date<=? AND v.visit_status<>4");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], range[1]));
        if (staffId != null) {
            sql.append(" AND ").append(alias).append(".dr_id=?");
            args.add(staffId);
        }
        if (deptId != null) {
            sql.append(" AND ").append(alias).append(".dept_id=?");
            args.add(deptId);
        }
        sql.append(" GROUP BY ").append(alias).append(".dr_id");
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql.toString(), args.toArray())) {
            Object sid = row.get("staffId");
            if (sid != null) {
                out.put(((Number) sid).longValue(), row);
            }
        }
        return out;
    }

    /**
     * 医生工作日志明细分页(his_visit 为主表, 处方/检查单数量与金额以相关子查询带出避免JOIN膨胀):
     * keyword 匹配患者姓名; 排序 work_date DESC, id DESC; 区间缺省最近30天。
     * 返回列: visitId/workDate/patientName/gender/age/drName/deptName/chiefComplaint/visitStatus/
     *         visitTime/finishTime/rxCount/rxAmount/orderCount/orderAmount。
     */
    public IPage<Map<String, Object>> doctorWorklogDetail(Long orgId, String startDate, String endDate,
                                                          Long staffId, Long deptId, String keyword,
                                                          long page, long size) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        Object[] w = worklogDetailWhere(tid, range, staffId, deptId, keyword);
        String where = (String) w[0];
        Object[] args = (Object[]) w[1];
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args);
        long[] ps = normPage(page, size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                worklogDetailCols() + where + " ORDER BY v.work_date DESC, v.id DESC LIMIT ?, ?",
                appendArgs(Arrays.asList(args), (ps[0] - 1) * ps[1], ps[1]));
        return pageOf(rows, total, ps[0], ps[1]);
    }

    /** 医生日志明细 SELECT 列(处方/检查单以相关子查询带出有效单 status>0 的数量与金额) */
    private static String worklogDetailCols() {
        return "SELECT v.id AS visitId, DATE_FORMAT(v.work_date, '%Y-%m-%d') AS workDate,"
                + " v.patient_name AS patientName, v.gender, v.age,"
                + " v.dr_name AS drName, v.dept_name AS deptName, v.chief_complaint AS chiefComplaint,"
                + " v.visit_status AS visitStatus,"
                + " DATE_FORMAT(v.visit_time, '%H:%i') AS visitTime, DATE_FORMAT(v.finish_time, '%H:%i') AS finishTime,"
                + " (SELECT COUNT(*) FROM his_prescription p WHERE p.visit_id=v.id AND p.tenant_id=v.tenant_id"
                + " AND p.deleted=0 AND p.status>0) AS rxCount,"
                + " (SELECT IFNULL(SUM(p2.total_amount),0) FROM his_prescription p2 WHERE p2.visit_id=v.id"
                + " AND p2.tenant_id=v.tenant_id AND p2.deleted=0 AND p2.status>0) AS rxAmount,"
                + " (SELECT COUNT(*) FROM his_order o WHERE o.visit_id=v.id AND o.tenant_id=v.tenant_id"
                + " AND o.deleted=0 AND o.status>0) AS orderCount,"
                + " (SELECT IFNULL(SUM(o2.total_amount),0) FROM his_order o2 WHERE o2.visit_id=v.id"
                + " AND o2.tenant_id=v.tenant_id AND o2.deleted=0 AND o2.status>0) AS orderAmount";
    }

    /** 医生日志明细共用条件构造(不含 SELECT 前缀): 返回 [whereSql, Object[] args] */
    private Object[] worklogDetailWhere(Long tid, String[] range, Long staffId, Long deptId, String keyword) {
        StringBuilder where = new StringBuilder(" FROM his_visit v")
                .append(" WHERE v.tenant_id=? AND v.deleted=0 AND v.visit_status<>4")
                .append(" AND v.work_date>=? AND v.work_date<=?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], range[1]));
        if (staffId != null) {
            where.append(" AND v.staff_id=?");
            args.add(staffId);
        }
        if (deptId != null) {
            where.append(" AND v.dept_id=?");
            args.add(deptId);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND v.patient_name LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        return new Object[]{where.toString(), args.toArray()};
    }

    /**
     * 医生工作日志导出(双Sheet): Sheet1 工作量汇总(复用 doctorWorklogSummary, 完成率"XX.X%"),
     * Sheet2 接诊明细(不分页, 上限10000行, 复用明细查询)。返回 summaryHead/summaryRows/detailHead/detailRows。
     */
    public Map<String, Object> exportDoctorWorklog(Long orgId, String startDate, String endDate,
                                                   Long staffId, Long deptId) {
        // Sheet1: 工作量汇总
        List<Map<String, Object>> summary = doctorWorklogSummary(orgId, startDate, endDate, staffId, deptId);
        List<List<String>> summaryHead = new ArrayList<>();
        for (String h : new String[]{"医生姓名", "科室", "接诊数", "完成数", "完成率",
                "处方数", "处方金额", "检查单数", "检查金额"}) {
            summaryHead.add(Collections.singletonList(h));
        }
        List<List<Object>> summaryRows = new ArrayList<>();
        for (Map<String, Object> s : summary) {
            summaryRows.add(Arrays.asList(
                    text(s.get("drName")), text(s.get("deptName")),
                    s.get("visitCount"), s.get("finishCount"), text(s.get("finishRate")),
                    s.get("rxCount"), s.get("rxAmount"), s.get("orderCount"), s.get("orderAmount")));
        }
        // Sheet2: 接诊明细(无keyword全量口径, LIMIT 10000)
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        Object[] w = worklogDetailWhere(tid, range, staffId, deptId, null);
        List<Map<String, Object>> details = jdbc.queryForList(
                worklogDetailCols() + w[0] + " ORDER BY v.work_date DESC, v.id DESC LIMIT 10000", (Object[]) w[1]);
        List<List<String>> detailHead = new ArrayList<>();
        for (String h : new String[]{"就诊日期", "患者姓名", "性别", "年龄", "主诉", "医生", "科室", "状态",
                "接诊时间", "完成时间", "处方数", "处方金额", "检查单数", "检查金额"}) {
            detailHead.add(Collections.singletonList(h));
        }
        List<List<Object>> detailRows = new ArrayList<>();
        for (Map<String, Object> d : details) {
            detailRows.add(Arrays.asList(
                    text(d.get("workDate")), text(d.get("patientName")), text(d.get("gender")), d.get("age"),
                    text(d.get("chiefComplaint")), text(d.get("drName")), text(d.get("deptName")),
                    visitStatusText(d.get("visitStatus")), text(d.get("visitTime")), text(d.get("finishTime")),
                    d.get("rxCount"), d.get("rxAmount"), d.get("orderCount"), d.get("orderAmount")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summaryHead", summaryHead);
        out.put("summaryRows", summaryRows);
        out.put("detailHead", detailHead);
        out.put("detailRows", detailRows);
        return out;
    }

    // ============================== 药库统计 ==============================

    /**
     * 药库库存概况(his_drug_stock 批次级): 品种数/库存总金额(数量x零售价)/低库存批次数(qty>0且qty<=warn_qty)/
     * 近效期批次数(qty>0且有效期在[今天,30天内]到期; 已过期批次不算"近效期", 归过期统计口径)。orgId=null 表示全医共体; warehouseId=null 表示机构下全部药库。
     */
    public Map<String, Object> warehouseStockSummary(Long orgId, Long warehouseId) {
        Long tid = TenantContext.require();
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT drug_catalog_id) AS drugCount,")
                .append(" IFNULL(SUM(qty * IFNULL(retail_price,0)),0) AS totalValue,")
                .append(" IFNULL(SUM(CASE WHEN qty>0 AND qty<=warn_qty THEN 1 ELSE 0 END),0) AS lowStockCount,")
                .append(" IFNULL(SUM(CASE WHEN qty>0 AND exp_date>=CURDATE() AND exp_date<=DATE_ADD(CURDATE(), INTERVAL 30 DAY)")
                .append(" THEN 1 ELSE 0 END),0) AS nearExpCount")
                .append(" FROM his_drug_stock WHERE tenant_id=? AND deleted=0");
        List<Object> args = new ArrayList<>(Collections.singletonList(tid));
        if (orgId != null) {
            sql.append(" AND org_id=?");
            args.add(orgId);
        }
        if (warehouseId != null) {
            sql.append(" AND warehouse_id=?");
            args.add(warehouseId);
        }
        return jdbc.queryForMap(sql.toString(), args.toArray());
    }

    /**
     * 药库入出库统计: 已确认(status=1)的入库/出库单按类型分组汇总单据数与金额, 确认时间左闭右开。
     * 类型映射: 入库 1采购/2退药回库/3盘盈/4调拨入; 出库 1处方发药/2报损/3盘亏/4调拨出。
     * 返回: {inflows:[{type,typeName,cnt,amount}], outflows:[{type,typeName,cnt,amount}]}
     */
    public Map<String, Object> warehouseFlowStats(Long orgId, Long warehouseId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        String start = range[0];
        String end = nextDay(range[1]);
        List<Map<String, Object>> ins = stockFlowAgg("his_stock_in", "in_type", tid, orgId, warehouseId, start, end);
        for (Map<String, Object> r : ins) {
            r.put("typeName", inTypeText(r.get("type")));
        }
        List<Map<String, Object>> outs = stockFlowAgg("his_stock_out", "out_type", tid, orgId, warehouseId, start, end);
        for (Map<String, Object> r : outs) {
            r.put("typeName", outTypeText(r.get("type")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inflows", ins);
        out.put("outflows", outs);
        return out;
    }

    /** 入/出库单按类型聚合(已确认, 确认时间左闭右开): [{type, cnt, amount}] */
    private List<Map<String, Object>> stockFlowAgg(String table, String typeCol, Long tid, Long orgId,
                                                   Long warehouseId, String start, String end) {
        StringBuilder sql = new StringBuilder("SELECT ").append(typeCol).append(" AS `type`, COUNT(*) AS cnt,")
                .append(" IFNULL(SUM(total_amount),0) AS amount")
                .append(" FROM ").append(table)
                .append(" WHERE tenant_id=? AND deleted=0 AND status=1 AND confirm_time>=? AND confirm_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) {
            sql.append(" AND org_id=?");
            args.add(orgId);
        }
        if (warehouseId != null) {
            sql.append(" AND warehouse_id=?");
            args.add(warehouseId);
        }
        sql.append(" GROUP BY ").append(typeCol).append(" ORDER BY ").append(typeCol);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 药库报表导出(双Sheet): Sheet1 库存概况(批次级全量, 不限日期, 上限10000行);
     * Sheet2 入出库统计(已确认单 JOIN 明细逐行: 日期/单号/类型/入出/药品/数量/金额, 入库行在前出库行在后, 各上限10000行)。
     * 返回: {sheets:[{sheetName, head, rows}]}
     */
    public Map<String, Object> exportWarehouseStats(Long orgId, Long warehouseId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        // Sheet1: 库存概况(批次级快照)
        StringBuilder s1 = new StringBuilder("SELECT drug_name AS drugName, IFNULL(spec,'') AS spec, batch_no AS batchNo,")
                .append(" qty, retail_price, qty*IFNULL(retail_price,0) AS amount,")
                .append(" DATE_FORMAT(exp_date,'%Y-%m-%d') AS expDate, warn_qty AS warnQty")
                .append(" FROM his_drug_stock WHERE tenant_id=? AND deleted=0");
        List<Object> a1 = new ArrayList<>(Collections.singletonList(tid));
        if (orgId != null) {
            s1.append(" AND org_id=?");
            a1.add(orgId);
        }
        if (warehouseId != null) {
            s1.append(" AND warehouse_id=?");
            a1.add(warehouseId);
        }
        s1.append(" ORDER BY drug_name, batch_no LIMIT 10000");
        List<List<Object>> stockRows = new ArrayList<>();
        for (Map<String, Object> s : jdbc.queryForList(s1.toString(), a1.toArray())) {
            stockRows.add(Arrays.asList(text(s.get("drugName")), text(s.get("spec")), text(s.get("batchNo")),
                    s.get("qty"), s.get("retail_price"), s.get("amount"), text(s.get("expDate")), s.get("warnQty")));
        }
        // Sheet2: 入出库明细
        String[] range = normRange(startDate, endDate);
        String start = range[0];
        String end = nextDay(range[1]);
        List<Map<String, Object>> flows = new ArrayList<>();
        flows.addAll(stockFlowDetail("his_stock_in", "his_stock_in_item", "stock_in_id", "in_no", "in_type",
                "入库", tid, orgId, warehouseId, start, end));
        flows.addAll(stockFlowDetail("his_stock_out", "his_stock_out_item", "stock_out_id", "out_no", "out_type",
                "出库", tid, orgId, warehouseId, start, end));
        List<List<Object>> flowRows = new ArrayList<>();
        for (Map<String, Object> f : flows) {
            flowRows.add(Arrays.asList(text(f.get("flowTime")), text(f.get("flowNo")), text(f.get("typeName")),
                    text(f.get("direction")), text(f.get("drugName")), f.get("qty"), f.get("amount")));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheetOf("库存概况", headOf("药品名称", "规格", "批号", "库存量", "零售价", "金额", "有效期", "预警量"), stockRows));
        sheets.add(sheetOf("入出库统计", headOf("日期", "单号", "类型", "入出", "药品", "数量", "金额"), flowRows));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheets", sheets);
        return out;
    }

    /**
     * 入/出库单明细行(主表 JOIN 明细表, 已确认, 确认时间左闭右开, LIMIT 10000):
     * 每行 {flowTime, flowNo, typeName, direction, drugName, qty, amount}; 表/列名均为白名单常量, 无注入风险。
     */
    private List<Map<String, Object>> stockFlowDetail(String mainTable, String itemTable, String itemRefCol,
                                                      String noCol, String typeCol, String direction,
                                                      Long tid, Long orgId, Long warehouseId, String start, String end) {
        StringBuilder sql = new StringBuilder("SELECT DATE_FORMAT(m.confirm_time,'%Y-%m-%d %H:%i:%s') AS flowTime,")
                .append(" m.").append(noCol).append(" AS flowNo, m.").append(typeCol).append(" AS flowType,")
                .append(" it.drug_name AS drugName, it.qty AS qty, IFNULL(it.amount,0) AS amount")
                .append(" FROM ").append(mainTable).append(" m")
                .append(" JOIN ").append(itemTable).append(" it ON it.").append(itemRefCol).append("=m.id")
                .append(" AND it.tenant_id=m.tenant_id AND it.deleted=0")
                .append(" WHERE m.tenant_id=? AND m.deleted=0 AND m.status=1")
                .append(" AND m.confirm_time>=? AND m.confirm_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) {
            sql.append(" AND m.org_id=?");
            args.add(orgId);
        }
        if (warehouseId != null) {
            sql.append(" AND m.warehouse_id=?");
            args.add(warehouseId);
        }
        sql.append(" ORDER BY m.confirm_time DESC, m.id DESC LIMIT 10000");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("typeName", "入库".equals(direction) ? inTypeText(r.get("flowType")) : outTypeText(r.get("flowType")));
            r.put("direction", direction);
        }
        return rows;
    }

    // ============================== 药房统计 ==============================

    /**
     * 药房发药按日统计(his_dispense status=2已发药, 发药时间左闭右开):
     * 返回 [{dt:"2026-09-01", cnt:35, amount:1234.50}, ...](按日升序)
     */
    public List<Map<String, Object>> pharmacyDispenseStats(Long orgId, Long pharmacyId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        StringBuilder sql = new StringBuilder("SELECT DATE_FORMAT(dispense_time,'%Y-%m-%d') AS dt, COUNT(*) AS cnt,")
                .append(" IFNULL(SUM(total_amount),0) AS amount")
                .append(" FROM his_dispense WHERE tenant_id=? AND deleted=0 AND status=2")
                .append(" AND dispense_time>=? AND dispense_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) {
            sql.append(" AND org_id=?");
            args.add(orgId);
        }
        if (pharmacyId != null) {
            sql.append(" AND pharmacy_id=?");
            args.add(pharmacyId);
        }
        sql.append(" GROUP BY DATE_FORMAT(dispense_time,'%Y-%m-%d') ORDER BY dt");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 药房退药统计: 已退药(status=1, 审批时间口径)的退药单数/金额 + 同区间已发药单数, 退药率=退药数/发药数*100
     * (HALF_UP 保留1位, "XX.X%"; 分母为0时记0.0%)。his_drug_return 无 pharmacy_id 列,
     * pharmacyId 过滤经 JOIN his_dispense(d.id=r.dispense_id AND d.pharmacy_id=?)实现。
     */
    public Map<String, Object> pharmacyReturnStats(Long orgId, Long pharmacyId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        String start = range[0];
        String end = nextDay(range[1]);
        // 退药数/金额(参数顺序: JOIN 上的 pharmacyId 先于 WHERE 上的租户/日期)
        StringBuilder rsql = new StringBuilder("SELECT COUNT(*) AS returnCount,")
                .append(" IFNULL(SUM(return_amount),0) AS returnAmount")
                .append(" FROM his_drug_return r");
        List<Object> rargs = new ArrayList<>();
        if (pharmacyId != null) {
            rsql.append(" JOIN his_dispense d ON d.id=r.dispense_id AND d.tenant_id=r.tenant_id")
                    .append(" AND d.deleted=0 AND d.pharmacy_id=?");
            rargs.add(pharmacyId);
        }
        rsql.append(" WHERE r.tenant_id=? AND r.deleted=0 AND r.status=1 AND r.approve_time>=? AND r.approve_time<?");
        rargs.add(tid);
        rargs.add(start);
        rargs.add(end);
        if (orgId != null) {
            rsql.append(" AND r.org_id=?");
            rargs.add(orgId);
        }
        Map<String, Object> ret = jdbc.queryForMap(rsql.toString(), rargs.toArray());
        // 同区间发药数(退药率分母)
        StringBuilder dsql = new StringBuilder("SELECT COUNT(*) FROM his_dispense")
                .append(" WHERE tenant_id=? AND deleted=0 AND status=2 AND dispense_time>=? AND dispense_time<?");
        List<Object> dargs = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) {
            dsql.append(" AND org_id=?");
            dargs.add(orgId);
        }
        if (pharmacyId != null) {
            dsql.append(" AND pharmacy_id=?");
            dargs.add(pharmacyId);
        }
        long dispenseCount = jdbc.queryForObject(dsql.toString(), Long.class, dargs.toArray());
        long returnCount = ((Number) ret.get("returnCount")).longValue();
        BigDecimal rate = dispenseCount == 0 ? BigDecimal.ZERO.setScale(1)
                : BigDecimal.valueOf(returnCount).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(dispenseCount), 1, RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("returnCount", returnCount);
        out.put("returnAmount", ret.get("returnAmount"));
        out.put("dispenseCount", dispenseCount);
        out.put("returnRate", rate.toPlainString() + "%");
        return out;
    }

    /**
     * 药房报表导出(双Sheet): Sheet1 发药统计(按日, 复用 pharmacyDispenseStats),
     * Sheet2 退药统计(已退药单逐单, 上限10000行)。返回: {sheets:[{sheetName, head, rows}]}
     */
    public Map<String, Object> exportPharmacyStats(Long orgId, Long pharmacyId, String startDate, String endDate) {
        // Sheet1: 发药按日汇总
        List<List<Object>> dayRows = new ArrayList<>();
        for (Map<String, Object> d : pharmacyDispenseStats(orgId, pharmacyId, startDate, endDate)) {
            dayRows.add(Arrays.asList(text(d.get("dt")), d.get("cnt"), d.get("amount")));
        }
        // Sheet2: 退药明细(参数顺序: JOIN 上的 pharmacyId 先于 WHERE 上的租户/日期)
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        StringBuilder rsql = new StringBuilder("SELECT r.return_no AS returnNo, r.patient_name AS patientName,")
                .append(" r.return_amount AS returnAmount, IFNULL(r.reason,'') AS reason,")
                .append(" DATE_FORMAT(r.approve_time,'%Y-%m-%d %H:%i:%s') AS approveTime")
                .append(" FROM his_drug_return r");
        List<Object> rargs = new ArrayList<>();
        if (pharmacyId != null) {
            rsql.append(" JOIN his_dispense d ON d.id=r.dispense_id AND d.tenant_id=r.tenant_id")
                    .append(" AND d.deleted=0 AND d.pharmacy_id=?");
            rargs.add(pharmacyId);
        }
        rsql.append(" WHERE r.tenant_id=? AND r.deleted=0 AND r.status=1 AND r.approve_time>=? AND r.approve_time<?");
        rargs.add(tid);
        rargs.add(range[0]);
        rargs.add(nextDay(range[1]));
        if (orgId != null) {
            rsql.append(" AND r.org_id=?");
            rargs.add(orgId);
        }
        rsql.append(" ORDER BY r.approve_time DESC, r.id DESC LIMIT 10000");
        List<List<Object>> returnRows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(rsql.toString(), rargs.toArray())) {
            returnRows.add(Arrays.asList(text(r.get("returnNo")), text(r.get("patientName")),
                    r.get("returnAmount"), text(r.get("reason")), text(r.get("approveTime"))));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheetOf("发药统计", headOf("日期", "发药单数", "金额"), dayRows));
        sheets.add(sheetOf("退药统计", headOf("退药单号", "患者姓名", "退药金额", "退药原因", "审批时间"), returnRows));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheets", sheets);
        return out;
    }

    // ============================== 收费统计 ==============================

    /**
     * 支付方式构成(已收费门诊单, 收费时间左闭右开): 按 his_payment_detail(混合支付逐笔)汇总,
     * 并逐单 UNION 回退——无支付明细的老单按主表 pay_method 计入(空值视为CASH), 同一单不会双计。
     * his_payment_detail 无 org_id, 机构过滤经 JOIN 收费单实现。返回 [{payMethod, payMethodName, cnt, amount}](按金额降序)。
     */
    public List<Map<String, Object>> chargePayMethodStats(Long orgId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        String start = range[0];
        String end = nextDay(range[1]);
        StringBuilder psql = new StringBuilder("SELECT pd.pay_method AS payMethod, COUNT(*) AS cnt,")
                .append(" IFNULL(SUM(pd.amount),0) AS amount")
                .append(" FROM his_payment_detail pd")
                .append(" JOIN his_charge_bill b ON b.id=pd.bill_id AND b.tenant_id=pd.tenant_id")
                .append(" AND b.deleted=0 AND b.status=1 AND b.bill_type=1")
                .append(" WHERE pd.tenant_id=? AND pd.deleted=0 AND b.charge_time>=? AND b.charge_time<?");
        List<Object> pargs = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) {
            psql.append(" AND b.org_id=?");
            pargs.add(orgId);
        }
        psql.append(" GROUP BY pd.pay_method");
        // 回退按单维度: 区间内无任何支付明细的收费单, 按主表 pay_method 归集(老数据兼容, 不再是整表判空才回退)
        StringBuilder bsql = new StringBuilder("SELECT IFNULL(b.pay_method,'CASH') AS payMethod, COUNT(*) AS cnt,")
                .append(" IFNULL(SUM(b.total_amount),0) AS amount")
                .append(" FROM his_charge_bill b WHERE b.tenant_id=? AND b.deleted=0 AND b.status=1 AND b.bill_type=1")
                .append(" AND b.charge_time>=? AND b.charge_time<?")
                .append(" AND NOT EXISTS (SELECT 1 FROM his_payment_detail pd WHERE pd.bill_id=b.id AND pd.tenant_id=b.tenant_id AND pd.deleted=0)");
        List<Object> bargs = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) {
            bsql.append(" AND b.org_id=?");
            bargs.add(orgId);
        }
        bsql.append(" GROUP BY IFNULL(b.pay_method,'CASH')");
        // 两路结果在内存合并(同支付方式 cnt/amount 相加后按金额降序), 避免 UNION 外层再套子查询
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(psql.toString(), pargs.toArray())) {
            mergePayStat(merged, r);
        }
        for (Map<String, Object> r : jdbc.queryForList(bsql.toString(), bargs.toArray())) {
            mergePayStat(merged, r);
        }
        List<Map<String, Object>> rows = new ArrayList<>(merged.values());
        rows.sort((x, y) -> toBigDecimal(y.get("amount")).compareTo(toBigDecimal(x.get("amount"))));
        for (Map<String, Object> r : rows) {
            r.put("payMethodName", payMethodText(r.get("payMethod")));
        }
        return rows;
    }

    /** 支付构成两路结果合并: 按支付方式累加 cnt/amount */
    private static void mergePayStat(Map<String, Map<String, Object>> merged, Map<String, Object> r) {
        String key = String.valueOf(r.get("payMethod"));
        Map<String, Object> exist = merged.get(key);
        if (exist == null) {
            merged.put(key, r);
            return;
        }
        exist.put("cnt", toBigDecimal(exist.get("cnt")).add(toBigDecimal(r.get("cnt"))));
        exist.put("amount", toBigDecimal(exist.get("amount")).add(toBigDecimal(r.get("amount"))));
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(String.valueOf(v));
    }

    /**
     * 退费统计(his_charge_bill bill_type=2, 收费时间左闭右开): 退费笔数/退费金额/
     * 部分退费数(origin_bill_id 非空)/全额退费数(origin_bill_id 为空)。
     * 注: 老数据退费单 origin_bill_id 可能为空(经 remark 关联原单), 部分退费数对老数据存在偏差, 属预期口径。
     */
    public Map<String, Object> chargeRefundStats(Long orgId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS refundCount,")
                .append(" IFNULL(SUM(total_amount),0) AS refundAmount,")
                .append(" IFNULL(SUM(CASE WHEN origin_bill_id IS NOT NULL THEN 1 ELSE 0 END),0) AS partialRefundCount,")
                .append(" IFNULL(SUM(CASE WHEN origin_bill_id IS NULL THEN 1 ELSE 0 END),0) AS fullRefundCount")
                .append(" FROM his_charge_bill WHERE tenant_id=? AND deleted=0 AND bill_type=2")
                .append(" AND charge_time>=? AND charge_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) {
            sql.append(" AND org_id=?");
            args.add(orgId);
        }
        return jdbc.queryForMap(sql.toString(), args.toArray());
    }

    /**
     * 发票统计(his_invoice, 创建时间左闭右开): 普通发票数(invoice_type=NORMAL且status=1)/
     * 作废数(status=2)/红冲数(status=3)/总数。
     */
    public Map<String, Object> invoiceStats(Long orgId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        StringBuilder sql = new StringBuilder("SELECT")
                .append(" IFNULL(SUM(CASE WHEN invoice_type='NORMAL' AND status=1 THEN 1 ELSE 0 END),0) AS normalCount,")
                .append(" IFNULL(SUM(CASE WHEN status=2 THEN 1 ELSE 0 END),0) AS voidCount,")
                .append(" IFNULL(SUM(CASE WHEN status=3 THEN 1 ELSE 0 END),0) AS redCount,")
                .append(" COUNT(*) AS totalCount")
                .append(" FROM his_invoice WHERE tenant_id=? AND deleted=0 AND create_time>=? AND create_time<?");
        List<Object> args = new ArrayList<>(Arrays.asList(tid, range[0], nextDay(range[1])));
        if (orgId != null) {
            sql.append(" AND org_id=?");
            args.add(orgId);
        }
        return jdbc.queryForMap(sql.toString(), args.toArray());
    }

    /**
     * 收费统计导出(三Sheet): Sheet1 支付方式构成(复用 chargePayMethodStats),
     * Sheet2 退费统计(逐单: 单号/患者/金额/全额|部分/原因/时间, 上限10000行),
     * Sheet3 发票统计(逐张: 发票号/类型/金额/患者/状态/时间, 上限10000行)。返回: {sheets:[{sheetName, head, rows}]}
     */
    public Map<String, Object> exportChargeStats(Long orgId, String startDate, String endDate) {
        Long tid = TenantContext.require();
        String[] range = normRange(startDate, endDate);
        String start = range[0];
        String end = nextDay(range[1]);
        // Sheet1: 支付方式构成
        List<List<Object>> payRows = new ArrayList<>();
        for (Map<String, Object> p : chargePayMethodStats(orgId, startDate, endDate)) {
            payRows.add(Arrays.asList(text(p.get("payMethodName")), p.get("cnt"), p.get("amount")));
        }
        // Sheet2: 退费明细
        StringBuilder rsql = new StringBuilder("SELECT bill_no AS billNo, patient_name AS patientName,")
                .append(" total_amount AS totalAmount, origin_bill_id AS originBillId, IFNULL(remark,'') AS remark,")
                .append(" DATE_FORMAT(charge_time,'%Y-%m-%d %H:%i:%s') AS chargeTime")
                .append(" FROM his_charge_bill WHERE tenant_id=? AND deleted=0 AND bill_type=2")
                .append(" AND charge_time>=? AND charge_time<?");
        List<Object> rargs = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) {
            rsql.append(" AND org_id=?");
            rargs.add(orgId);
        }
        rsql.append(" ORDER BY charge_time DESC, id DESC LIMIT 10000");
        List<List<Object>> refundRows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(rsql.toString(), rargs.toArray())) {
            refundRows.add(Arrays.asList(text(r.get("billNo")), text(r.get("patientName")), r.get("totalAmount"),
                    r.get("originBillId") == null ? "全额退费" : "部分退费",
                    text(r.get("remark")), text(r.get("chargeTime"))));
        }
        // Sheet3: 发票明细
        StringBuilder isql = new StringBuilder("SELECT invoice_no AS invoiceNo, invoice_type AS invoiceType,")
                .append(" amount, patient_name AS patientName, status,")
                .append(" DATE_FORMAT(create_time,'%Y-%m-%d %H:%i:%s') AS createTime")
                .append(" FROM his_invoice WHERE tenant_id=? AND deleted=0 AND create_time>=? AND create_time<?");
        List<Object> iargs = new ArrayList<>(Arrays.asList(tid, start, end));
        if (orgId != null) {
            isql.append(" AND org_id=?");
            iargs.add(orgId);
        }
        isql.append(" ORDER BY create_time DESC, id DESC LIMIT 10000");
        List<List<Object>> invoiceRows = new ArrayList<>();
        for (Map<String, Object> iv : jdbc.queryForList(isql.toString(), iargs.toArray())) {
            invoiceRows.add(Arrays.asList(text(iv.get("invoiceNo")), invoiceTypeText(iv.get("invoiceType")),
                    iv.get("amount"), text(iv.get("patientName")), invoiceStatusText(iv.get("status")),
                    text(iv.get("createTime"))));
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheetOf("支付方式构成", headOf("支付方式", "笔数", "金额"), payRows));
        sheets.add(sheetOf("退费统计", headOf("退费单号", "患者姓名", "退费金额", "退费类型", "退费原因", "退费时间"), refundRows));
        sheets.add(sheetOf("发票统计", headOf("发票号", "发票类型", "开票金额", "患者姓名", "状态", "开票时间"), invoiceRows));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheets", sheets);
        return out;
    }

    // ============================== 药库/药房/收费统计辅助 ==============================

    /** 单行表头构造(EasyExcel head 格式 List<List<String>>) */
    private static List<List<String>> headOf(String... cols) {
        List<List<String>> head = new ArrayList<>();
        for (String c : cols) {
            head.add(Collections.singletonList(c));
        }
        return head;
    }

    /** 单个Sheet描述: {sheetName, head, rows} */
    private static Map<String, Object> sheetOf(String sheetName, List<List<String>> head, List<List<Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sheetName", sheetName);
        m.put("head", head);
        m.put("rows", rows);
        return m;
    }

    /** 入库类型文本: 1采购 2退药回库 3盘盈 4调拨入 */
    private static String inTypeText(Object type) {
        if (type == null) {
            return "";
        }
        int t = ((Number) type).intValue();
        if (t == 1) {
            return "采购";
        }
        if (t == 2) {
            return "退药回库";
        }
        if (t == 3) {
            return "盘盈";
        }
        return t == 4 ? "调拨入" : String.valueOf(t);
    }

    /** 出库类型文本: 1处方发药 2报损 3盘亏 4调拨出 */
    private static String outTypeText(Object type) {
        if (type == null) {
            return "";
        }
        int t = ((Number) type).intValue();
        if (t == 1) {
            return "处方发药";
        }
        if (t == 2) {
            return "报损";
        }
        if (t == 3) {
            return "盘亏";
        }
        return t == 4 ? "调拨出" : String.valueOf(t);
    }

    /** 支付方式文本: CASH现金 WECHAT微信 ALIPAY支付宝 CARD银行卡 INSURANCE医保 FREE免收(空值视为CASH) */
    private static String payMethodText(Object payMethod) {
        String m = payMethod == null ? "CASH" : String.valueOf(payMethod).trim().toUpperCase();
        switch (m) {
            case "CASH":
                return "现金";
            case "WECHAT":
                return "微信";
            case "ALIPAY":
                return "支付宝";
            case "CARD":
                return "银行卡";
            case "INSURANCE":
                return "医保";
            case "FREE":
                return "免收";
            default:
                return m;
        }
    }

    /** 发票类型文本: NORMAL普通 ELECTRONIC电子 VOID作废 RED红冲 */
    private static String invoiceTypeText(Object type) {
        String t = type == null ? "" : String.valueOf(type).trim();
        switch (t) {
            case "NORMAL":
                return "普通发票";
            case "ELECTRONIC":
                return "电子发票";
            case "VOID":
                return "作废发票";
            case "RED":
                return "红冲发票";
            default:
                return t;
        }
    }

    /** 发票状态文本: 1正常 2已作废 3已红冲 */
    private static String invoiceStatusText(Object status) {
        if (status == null) {
            return "";
        }
        int s = ((Number) status).intValue();
        if (s == 1) {
            return "正常";
        }
        return s == 2 ? "已作废" : (s == 3 ? "已红冲" : String.valueOf(s));
    }

    // ============================== 辅助方法 ==============================

    /** 规范日期区间: end缺省今天, start缺省end-29天(最近30天); 格式非法抛400; 返回 [start, end](yyyy-MM-dd闭区间) */
    static String[] normRange(String startDate, String endDate) {
        LocalDate end = parseDate(endDate);
        if (end == null) {
            end = LocalDate.now();
        }
        LocalDate start = parseDate(startDate);
        if (start == null) {
            start = end.minusDays(29);
        }
        if (start.isAfter(end)) {
            throw new BizException(400, "开始日期不能晚于结束日期");
        }
        return new String[]{start.toString(), end.toString()};
    }

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(400, "日期格式错误(应为yyyy-MM-dd): " + s);
        }
    }

    /** 次日(yyyy-MM-dd), 作为左闭右开区间的右端点 */
    private static String nextDay(String date) {
        return LocalDate.parse(date).plusDays(1).toString();
    }

    /** 规范分页参数: page<1→1, size<1→20, size上限500; 返回 [page, size] */
    private static long[] normPage(long page, long size) {
        long p = page < 1 ? 1 : page;
        long sz = size < 1 ? 20 : Math.min(size, 500);
        return new long[]{p, sz};
    }

    private static Object[] appendArgs(List<Object> args, Object... more) {
        List<Object> all = new ArrayList<>(args);
        all.addAll(Arrays.asList(more));
        return all.toArray();
    }

    private static Page<Map<String, Object>> pageOf(List<Map<String, Object>> rows, long total, long page, long size) {
        Page<Map<String, Object>> p = new Page<>(page, size);
        p.setRecords(rows == null ? Collections.emptyList() : rows);
        p.setTotal(total);
        return p;
    }

    private static String billTypeText(Object billType) {
        if (billType == null) {
            return "门诊";
        }
        int t = ((Number) billType).intValue();
        return t == 1 ? "门诊收费" : (t == 2 ? "门诊退费" : "门诊");
    }

    private static String chargeStatusText(Object status) {
        if (status == null) {
            return "";
        }
        int s = ((Number) status).intValue();
        return s == 0 ? "待收费" : (s == 1 ? "已收费" : (s == 2 ? "已退费" : String.valueOf(s)));
    }

    private static String setlStatusText(Object status) {
        if (status == null) {
            return "";
        }
        return ((Number) status).intValue() == 1 ? "已结算" : "已撤销";
    }

    /** 就诊状态文本: 1-候诊 2-接诊中 3-已完成(4-已取消已在查询口径排除) */
    private static String visitStatusText(Object status) {
        if (status == null) {
            return "";
        }
        int s = ((Number) status).intValue();
        if (s == 1) {
            return "候诊";
        }
        if (s == 2) {
            return "接诊中";
        }
        return s == 3 ? "已完成" : String.valueOf(s);
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
