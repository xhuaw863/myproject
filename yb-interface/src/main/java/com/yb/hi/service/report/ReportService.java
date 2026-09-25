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
 * orgId 过滤仅对 his_charge_bill/his_dispense/his_daily_settle 生效; orgId=null 表示全部(牵头机构视角)。
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
                + " cash_total AS cashTotal, fund_total AS fundTotal, acct_total AS acctTotal, status,"
                + " DATE_FORMAT(settle_time, '%Y-%m-%d %H:%i:%s') AS settleTime";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        long[] ps = normPage(page, size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                cols + where + " ORDER BY settle_date DESC, id DESC LIMIT ?, ?",
                appendArgs(args, (ps[0] - 1) * ps[1], ps[1]));
        return pageOf(rows, total, ps[0], ps[1]);
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

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
