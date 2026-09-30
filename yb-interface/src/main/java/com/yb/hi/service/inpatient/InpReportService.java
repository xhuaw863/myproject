package com.yb.hi.service.inpatient;

import com.yb.hi.dto.inpatient.ReportQueryDTO;
import com.yb.hi.entity.inpatient.HisInpReportSnapshot;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisBedMapper;
import com.yb.hi.mapper.inpatient.HisInpChargeDetailMapper;
import com.yb.hi.mapper.inpatient.HisInpReportSnapshotMapper;
import com.yb.hi.mapper.inpatient.HisInpSettleMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院报表服务: 床位统计/床位周转/费用分析/科室经营/住院日统计/DRG-DIP分析六类统计报表 + 报表快照落盘。
 * 口径说明:
 * - JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0;
 * - 日期区间缺省最近30天; 比率统一输出 0-1 小数(4位), 前端按需 ×100 展示;
 * - 平均开放床位数以当前非停用床位近似(无逐日床位历史): 周转率按"每床每日"口径、周转次数按"期内每床"口径;
 * - 在院口径 = visit_status IN (2, 3)(在院/出院办理中); 出院口径 = visit_status = 4;
 * - 转科转入/转出以 his_inp_transfer 已执行(status=4)且审批落在本日的记录计, 无科室过滤时两者同为当日转科总次数。
 */
@Slf4j
@Service
public class InpReportService {

    /** 费用类别名称(与 InpChargeService 同口径): 1西药 2中药 3检查 4检验 5治疗 6护理 7材料 8床位 9其他 */
    private static final Map<Integer, String> FEE_TYPE_NAMES = new LinkedHashMap<>();

    static {
        FEE_TYPE_NAMES.put(1, "西药");
        FEE_TYPE_NAMES.put(2, "中药");
        FEE_TYPE_NAMES.put(3, "检查");
        FEE_TYPE_NAMES.put(4, "检验");
        FEE_TYPE_NAMES.put(5, "治疗");
        FEE_TYPE_NAMES.put(6, "护理");
        FEE_TYPE_NAMES.put(7, "材料");
        FEE_TYPE_NAMES.put(8, "床位");
        FEE_TYPE_NAMES.put(9, "其他");
    }

    private final HisInpReportSnapshotMapper snapshotMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisInpChargeDetailMapper chargeMapper;
    private final HisBedMapper bedMapper;
    private final HisInpSettleMapper settleMapper;
    private final JdbcTemplate jdbcTemplate;

    public InpReportService(HisInpReportSnapshotMapper snapshotMapper, HisInpVisitMapper visitMapper,
                            HisInpChargeDetailMapper chargeMapper, HisBedMapper bedMapper,
                            HisInpSettleMapper settleMapper, JdbcTemplate jdbcTemplate) {
        this.snapshotMapper = snapshotMapper;
        this.visitMapper = visitMapper;
        this.chargeMapper = chargeMapper;
        this.bedMapper = bedMapper;
        this.settleMapper = settleMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 床位统计 ==================== */

    /**
     * 床位统计: his_bed 按病区/科室分组, 计算总床位数、占用床位数(status=1)与使用率。
     * 返回 {summary:{totalBeds,occupiedBeds,occupancyRate}, byDept:[...], byWard:[...]}。
     */
    public R<Map<String, Object>> getBedStats(ReportQueryDTO query, Long orgId) {
        ReportQueryDTO q = query == null ? new ReportQueryDTO() : query;
        StringBuilder sql = new StringBuilder(
                "SELECT b.ward_id, IFNULL(w.ward_name, '') ward_name, IFNULL(w.dept_id, 0) dept_id,"
                        + " IFNULL(d.dept_name, '未分科') dept_name, COUNT(*) total_beds,"
                        + " IFNULL(SUM(CASE WHEN b.status = 1 THEN 1 ELSE 0 END), 0) occupied_beds"
                        + " FROM his_bed b"
                        + " LEFT JOIN his_ward w ON w.id = b.ward_id AND w.deleted = 0"
                        + " LEFT JOIN his_dept d ON d.id = w.dept_id AND d.deleted = 0"
                        + " WHERE b.deleted = 0 AND b.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (orgId != null) {
            sql.append(" AND b.org_id = ?");
            args.add(orgId);
        }
        if (q.getWardId() != null) {
            sql.append(" AND b.ward_id = ?");
            args.add(q.getWardId());
        }
        if (q.getDeptId() != null) {
            sql.append(" AND w.dept_id = ?");
            args.add(q.getDeptId());
        }
        sql.append(" GROUP BY b.ward_id, w.ward_name, w.dept_id, d.dept_name ORDER BY total_beds DESC");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());

        long total = 0;
        long occupied = 0;
        Map<Long, Map<String, Object>> deptAgg = new LinkedHashMap<>();
        List<Map<String, Object>> byWard = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long t = toLong(row.get("total_beds"));
            long o = toLong(row.get("occupied_beds"));
            total += t;
            occupied += o;
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("wardId", toLong(row.get("ward_id")));
            w.put("wardName", str(row.get("ward_name")));
            w.put("deptId", toLong(row.get("dept_id")));
            w.put("deptName", str(row.get("dept_name")));
            w.put("total", t);
            w.put("occupied", o);
            w.put("rate", rate(o, t));
            byWard.add(w);
            final Map<String, Object> r = row;
            Map<String, Object> agg = deptAgg.computeIfAbsent(toLong(row.get("dept_id")), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("deptId", k);
                m.put("deptName", str(r.get("dept_name")));
                m.put("total", 0L);
                m.put("occupied", 0L);
                return m;
            });
            agg.put("total", (Long) agg.get("total") + t);
            agg.put("occupied", (Long) agg.get("occupied") + o);
        }
        List<Map<String, Object>> byDept = new ArrayList<>();
        for (Map<String, Object> agg : deptAgg.values()) {
            agg.put("rate", rate((Long) agg.get("occupied"), (Long) agg.get("total")));
            byDept.add(agg);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalBeds", total);
        summary.put("occupiedBeds", occupied);
        summary.put("occupancyRate", rate(occupied, total));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("byDept", byDept);
        result.put("byWard", byWard);
        return R.ok(result);
    }

    /* ==================== 床位周转 ==================== */

    /**
     * 床位周转: 统计期内已出院就诊(visit_status=4)的出院人次、平均住院日、占用总床日、
     * 周转率(出院人次/(开放床位数×天数), 每床每日)与周转次数(出院人次/开放床位数, 期内每床), 按科室分组。
     */
    public R<Map<String, Object>> getBedTurnover(ReportQueryDTO query, Long orgId) {
        ReportQueryDTO q = query == null ? new ReportQueryDTO() : query;
        LocalDate[] range = dateRange(q);
        LocalDate start = range[0];
        LocalDate end = range[1];
        long days = ChronoUnit.DAYS.between(start, end) + 1;

        StringBuilder sql = new StringBuilder(
                "SELECT IFNULL(v.dept_id, 0) dept_id, COUNT(*) discharge_cnt,"
                        + " IFNULL(AVG(TIMESTAMPDIFF(DAY, v.admit_date, v.discharge_date)), 0) avg_los,"
                        + " IFNULL(SUM(TIMESTAMPDIFF(DAY, v.admit_date, v.discharge_date)), 0) total_los"
                        + " FROM his_inp_visit v"
                        + " WHERE v.deleted = 0 AND v.tenant_id = ? AND v.visit_status = 4"
                        + " AND v.discharge_date IS NOT NULL AND DATE(v.discharge_date) BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(start);
        args.add(end);
        if (orgId != null) {
            sql.append(" AND v.org_id = ?");
            args.add(orgId);
        }
        if (q.getDeptId() != null) {
            sql.append(" AND v.dept_id = ?");
            args.add(q.getDeptId());
        }
        sql.append(" GROUP BY v.dept_id");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());

        // 实际开放床位数(非停用): 平均开放床位数的近似
        StringBuilder bedSql = new StringBuilder(
                "SELECT COUNT(*) FROM his_bed WHERE deleted = 0 AND tenant_id = ? AND status <> 2");
        List<Object> bedArgs = new ArrayList<>();
        bedArgs.add(tenantId());
        if (orgId != null) {
            bedSql.append(" AND org_id = ?");
            bedArgs.add(orgId);
        }
        long openBeds = toLong(jdbcTemplate.queryForObject(bedSql.toString(), Long.class, bedArgs.toArray()));

        List<Long> deptIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            deptIds.add(toLong(row.get("dept_id")));
        }
        Map<Long, String> names = deptNames(deptIds);

        long dischargeCount = 0;
        long totalLos = 0;
        List<Map<String, Object>> byDept = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long cnt = toLong(row.get("discharge_cnt"));
            long los = toLong(row.get("total_los"));
            dischargeCount += cnt;
            totalLos += los;
            Map<String, Object> d = new LinkedHashMap<>();
            Long deptId = toLong(row.get("dept_id"));
            d.put("deptId", deptId);
            d.put("deptName", names.getOrDefault(deptId, "未分科"));
            d.put("dischargeCount", cnt);
            d.put("avgLengthOfStay", div(BigDecimal.valueOf(los), BigDecimal.valueOf(cnt), 2));
            d.put("turnoverTimes", div(BigDecimal.valueOf(cnt), BigDecimal.valueOf(openBeds), 2));
            byDept.add(d);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("days", days);
        result.put("openBeds", openBeds);
        result.put("dischargeCount", dischargeCount);
        result.put("avgLengthOfStay", div(BigDecimal.valueOf(totalLos), BigDecimal.valueOf(dischargeCount), 2));
        result.put("totalOccupiedBedDays", totalLos);
        result.put("turnoverRate", div(BigDecimal.valueOf(dischargeCount),
                BigDecimal.valueOf(openBeds).multiply(BigDecimal.valueOf(days)), 4));
        result.put("turnoverTimes", div(BigDecimal.valueOf(dischargeCount), BigDecimal.valueOf(openBeds), 2));
        result.put("byDept", byDept);
        return R.ok(result);
    }

    /* ==================== 费用分析 ==================== */

    /**
     * 费用分析: 期内 his_inp_charge_detail(status=1)按费用类别构成、人均费用、每日趋势与科室TOP10。
     * deptId 过滤时先收敛该科室就诊ID集合再过滤明细。
     */
    public R<Map<String, Object>> getFeeAnalysis(ReportQueryDTO query, Long orgId) {
        ReportQueryDTO q = query == null ? new ReportQueryDTO() : query;
        LocalDate[] range = dateRange(q);
        LocalDate start = range[0];
        LocalDate end = range[1];

        String visitIn = null;
        if (q.getDeptId() != null) {
            List<Long> visitIds = jdbcTemplate.queryForList(
                    "SELECT id FROM his_inp_visit WHERE dept_id = ? AND deleted = 0 AND tenant_id = ?",
                    Long.class, q.getDeptId(), tenantId());
            if (visitIds.isEmpty()) {
                return R.ok(emptyFeeAnalysis(start, end));
            }
            visitIn = "(" + StringUtils.collectionToCommaDelimitedString(visitIds) + ")";
        }

        StringBuilder where = new StringBuilder(
                " FROM his_inp_charge_detail c WHERE c.deleted = 0 AND c.tenant_id = ?"
                        + " AND c.status = 1 AND c.charge_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(start);
        args.add(end);
        if (orgId != null) {
            where.append(" AND c.org_id = ?");
            args.add(orgId);
        }
        if (visitIn != null) {
            where.append(" AND c.inp_visit_id IN ").append(visitIn);
        }

        // 1) 费用构成(按类别)
        List<Map<String, Object>> typeRows = jdbcTemplate.queryForList(
                "SELECT c.fee_type, IFNULL(SUM(c.amount), 0) total_amount" + where
                        + " GROUP BY c.fee_type ORDER BY total_amount DESC", args.toArray());
        // 2) 总额与患者数(人均费用分母)
        Map<String, Object> totalRow = jdbcTemplate.queryForMap(
                "SELECT COUNT(DISTINCT c.inp_visit_id) patients, IFNULL(SUM(c.amount), 0) total_amount" + where,
                args.toArray());
        BigDecimal totalAmount = toBd(totalRow.get("total_amount"));
        long patients = toLong(totalRow.get("patients"));
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Map<String, Object> row : typeRows) {
            BigDecimal amt = toBd(row.get("total_amount"));
            Map<String, Object> t = new LinkedHashMap<>();
            int feeType = row.get("fee_type") == null ? 0 : ((Number) row.get("fee_type")).intValue();
            t.put("feeType", feeType);
            t.put("feeTypeName", FEE_TYPE_NAMES.getOrDefault(feeType, "其他"));
            t.put("totalAmount", amt);
            t.put("percentage", div(amt, totalAmount, 4).multiply(BigDecimal.valueOf(100)));
            byType.add(t);
        }
        // 3) 每日费用趋势
        List<Map<String, Object>> trendRows = jdbcTemplate.queryForList(
                "SELECT c.charge_date, IFNULL(SUM(c.amount), 0) total_amount" + where
                        + " GROUP BY c.charge_date ORDER BY c.charge_date", args.toArray());
        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        for (Map<String, Object> row : trendRows) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("date", str(row.get("charge_date")));
            t.put("amount", toBd(row.get("total_amount")));
            dailyTrend.add(t);
        }
        // 4) 科室TOP10(按就诊科室归集)
        List<Map<String, Object>> deptRows = jdbcTemplate.queryForList(
                "SELECT IFNULL(v.dept_id, 0) dept_id, IFNULL(SUM(c.amount), 0) total_amount"
                        + " FROM his_inp_charge_detail c JOIN his_inp_visit v ON v.id = c.inp_visit_id AND v.deleted = 0"
                        + " WHERE c.deleted = 0 AND c.tenant_id = ? AND c.status = 1"
                        + " AND c.charge_date BETWEEN ? AND ?"
                        + (orgId != null ? " AND c.org_id = ?" : "")
                        + (visitIn != null ? " AND c.inp_visit_id IN " + visitIn : "")
                        + " GROUP BY v.dept_id ORDER BY total_amount DESC LIMIT 10", args.toArray());
        List<Long> deptIds = new ArrayList<>();
        for (Map<String, Object> row : deptRows) {
            deptIds.add(toLong(row.get("dept_id")));
        }
        Map<Long, String> names = deptNames(deptIds);
        List<Map<String, Object>> topDepts = new ArrayList<>();
        for (Map<String, Object> row : deptRows) {
            Map<String, Object> d = new LinkedHashMap<>();
            Long deptId = toLong(row.get("dept_id"));
            d.put("deptId", deptId);
            d.put("deptName", names.getOrDefault(deptId, "未分科"));
            d.put("totalAmount", toBd(row.get("total_amount")));
            topDepts.add(d);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("totalAmount", totalAmount);
        result.put("patientCount", patients);
        result.put("avgCostPerPatient", div(totalAmount, BigDecimal.valueOf(patients), 2));
        result.put("byType", byType);
        result.put("dailyTrend", dailyTrend);
        result.put("topDepts", topDepts);
        return R.ok(result);
    }

    /* ==================== 科室经营 ==================== */

    /**
     * 科室经营: 按科室汇总期内收入(his_inp_charge_detail)、出入院人次、手术人次、药占比/材占比与平均住院日。
     * drugRatio = (西药+中药)/总费用, materialRatio = 材料/总费用, 三路统计(就诊/费用/手术)按科室合并。
     */
    public R<Map<String, Object>> getDeptBusiness(ReportQueryDTO query, Long orgId) {
        ReportQueryDTO q = query == null ? new ReportQueryDTO() : query;
        LocalDate[] range = dateRange(q);
        LocalDate start = range[0];
        LocalDate end = range[1];

        // 1) 就诊侧: 出入院人次 + 平均住院日(期内入院或期内出院的就诊)
        StringBuilder vSql = new StringBuilder(
                "SELECT IFNULL(v.dept_id, 0) dept_id,"
                        + " IFNULL(SUM(CASE WHEN DATE(v.admit_date) BETWEEN ? AND ? AND v.visit_status <> 5 THEN 1 ELSE 0 END), 0) admit_cnt,"
                        + " IFNULL(SUM(CASE WHEN v.visit_status = 4 AND DATE(v.discharge_date) BETWEEN ? AND ? THEN 1 ELSE 0 END), 0) discharge_cnt,"
                        + " IFNULL(AVG(CASE WHEN v.visit_status = 4 AND v.discharge_date IS NOT NULL"
                        + " AND DATE(v.discharge_date) BETWEEN ? AND ?"
                        + " THEN TIMESTAMPDIFF(DAY, v.admit_date, v.discharge_date) END), 0) avg_los"
                        + " FROM his_inp_visit v"
                        + " WHERE v.deleted = 0 AND v.tenant_id = ?"
                        + " AND ((v.admit_date IS NOT NULL AND DATE(v.admit_date) BETWEEN ? AND ?)"
                        + " OR (v.visit_status = 4 AND v.discharge_date IS NOT NULL AND DATE(v.discharge_date) BETWEEN ? AND ?))");
        List<Object> vArgs = new ArrayList<>();
        vArgs.add(start);
        vArgs.add(end);
        vArgs.add(start);
        vArgs.add(end);
        vArgs.add(start);
        vArgs.add(end);
        vArgs.add(tenantId());
        vArgs.add(start);
        vArgs.add(end);
        vArgs.add(start);
        vArgs.add(end);
        if (orgId != null) {
            vSql.append(" AND v.org_id = ?");
            vArgs.add(orgId);
        }
        if (q.getDeptId() != null) {
            vSql.append(" AND v.dept_id = ?");
            vArgs.add(q.getDeptId());
        }
        vSql.append(" GROUP BY v.dept_id");

        // 2) 费用侧: 总收入 + 药品费 + 材料费
        StringBuilder cSql = new StringBuilder(
                "SELECT IFNULL(v.dept_id, 0) dept_id, IFNULL(SUM(c.amount), 0) total_amount,"
                        + " IFNULL(SUM(CASE WHEN c.fee_type IN (1, 2) THEN c.amount ELSE 0 END), 0) drug_amount,"
                        + " IFNULL(SUM(CASE WHEN c.fee_type = 7 THEN c.amount ELSE 0 END), 0) material_amount"
                        + " FROM his_inp_charge_detail c JOIN his_inp_visit v ON v.id = c.inp_visit_id AND v.deleted = 0"
                        + " WHERE c.deleted = 0 AND c.tenant_id = ? AND c.status = 1"
                        + " AND c.charge_date BETWEEN ? AND ?");
        List<Object> cArgs = new ArrayList<>();
        cArgs.add(tenantId());
        cArgs.add(start);
        cArgs.add(end);
        if (orgId != null) {
            cSql.append(" AND c.org_id = ?");
            cArgs.add(orgId);
        }
        if (q.getDeptId() != null) {
            cSql.append(" AND v.dept_id = ?");
            cArgs.add(q.getDeptId());
        }
        cSql.append(" GROUP BY v.dept_id");

        // 3) 手术侧: 期内排程手术(取消除外, 按手术科室)
        StringBuilder sSql = new StringBuilder(
                "SELECT IFNULL(s.dept_id, 0) dept_id, COUNT(*) surgery_cnt FROM his_surgery s"
                        + " WHERE s.deleted = 0 AND s.tenant_id = ? AND IFNULL(s.status, 0) <> 6"
                        + " AND s.schedule_date BETWEEN ? AND ?");
        List<Object> sArgs = new ArrayList<>();
        sArgs.add(tenantId());
        sArgs.add(start);
        sArgs.add(end);
        if (orgId != null) {
            sSql.append(" AND s.org_id = ?");
            sArgs.add(orgId);
        }
        if (q.getDeptId() != null) {
            sSql.append(" AND s.dept_id = ?");
            sArgs.add(q.getDeptId());
        }
        sSql.append(" GROUP BY s.dept_id");

        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();
        Map<Long, Object[]> visitAgg = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(vSql.toString(), vArgs.toArray())) {
            visitAgg.put(toLong(row.get("dept_id")), new Object[]{
                    toLong(row.get("admit_cnt")), toLong(row.get("discharge_cnt")), row.get("avg_los")});
        }
        Map<Long, BigDecimal[]> chargeAgg = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(cSql.toString(), cArgs.toArray())) {
            chargeAgg.put(toLong(row.get("dept_id")), new BigDecimal[]{
                    toBd(row.get("total_amount")), toBd(row.get("drug_amount")), toBd(row.get("material_amount"))});
        }
        Map<Long, Long> surgeryAgg = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sSql.toString(), sArgs.toArray())) {
            surgeryAgg.put(toLong(row.get("dept_id")), toLong(row.get("surgery_cnt")));
        }
        for (Long deptId : visitAgg.keySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("deptId", deptId);
            d.put("totalIncome", BigDecimal.ZERO);
            d.put("admitCount", 0L);
            d.put("dischargeCount", 0L);
            d.put("surgeryCount", 0L);
            d.put("drugRatio", BigDecimal.ZERO);
            d.put("materialRatio", BigDecimal.ZERO);
            d.put("avgLos", BigDecimal.ZERO);
            merged.put(deptId, d);
        }
        for (Map.Entry<Long, Map<String, Object>> e : merged.entrySet()) {
            Map<String, Object> d = e.getValue();
            Object[] v = visitAgg.get(e.getKey());
            if (v != null) {
                d.put("admitCount", v[0]);
                d.put("dischargeCount", v[1]);
                d.put("avgLos", toBd(v[2]).setScale(2, RoundingMode.HALF_UP));
            }
            BigDecimal[] c = chargeAgg.get(e.getKey());
            if (c != null) {
                d.put("totalIncome", c[0]);
                d.put("drugRatio", div(c[1], c[0], 4));
                d.put("materialRatio", div(c[2], c[0], 4));
            }
            Long s = surgeryAgg.get(e.getKey());
            if (s != null) {
                d.put("surgeryCount", s);
            }
        }
        // 仅有费用/手术而无出入院的科室也纳入
        for (Long deptId : chargeAgg.keySet()) {
            if (!merged.containsKey(deptId)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("deptId", deptId);
                d.put("totalIncome", chargeAgg.get(deptId)[0]);
                d.put("admitCount", 0L);
                d.put("dischargeCount", 0L);
                d.put("surgeryCount", surgeryAgg.getOrDefault(deptId, 0L));
                d.put("drugRatio", div(chargeAgg.get(deptId)[1], chargeAgg.get(deptId)[0], 4));
                d.put("materialRatio", div(chargeAgg.get(deptId)[2], chargeAgg.get(deptId)[0], 4));
                d.put("avgLos", BigDecimal.ZERO);
                merged.put(deptId, d);
            }
        }
        Map<Long, String> names = deptNames(merged.keySet());
        List<Map<String, Object>> items = new ArrayList<>(merged.values());
        items.sort((a, b) -> ((BigDecimal) b.get("totalIncome")).compareTo((BigDecimal) a.get("totalIncome")));
        for (Map<String, Object> d : items) {
            d.put("deptName", names.getOrDefault((Long) d.get("deptId"), "未分科"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("items", items);
        return R.ok(result);
    }

    /* ==================== 住院日统计 ==================== */

    /**
     * 住院日统计: 指定日期(缺省当天)的出入院/在院/转科/死亡当日汇总 + 30天趋势。
     * 在院趋势按"入院日<=当日 且 (未出院或出院日晚于当日)"回溯推算; 死亡以死亡病历(record_type=9)计。
     */
    public R<Map<String, Object>> getDailySummary(ReportQueryDTO query, Long orgId) {
        ReportQueryDTO q = query == null ? new ReportQueryDTO() : query;
        LocalDate day = q.getStartDate() != null ? q.getStartDate()
                : (q.getEndDate() != null ? q.getEndDate() : LocalDate.now());

        Map<String, Object> today = dayAgg(day, orgId, q.getDeptId());
        // 转科转入/转出(已执行且审批日在当日; 指定科室时按转入/转出科室分别计)
        StringBuilder tSql = new StringBuilder(
                "SELECT COUNT(*) FROM his_inp_transfer t WHERE t.deleted = 0 AND t.tenant_id = ?"
                        + " AND t.status = 4 AND DATE(t.approve_time) = ?");
        List<Object> tArgs = new ArrayList<>();
        tArgs.add(tenantId());
        tArgs.add(day);
        if (orgId != null) {
            tSql.append(" AND t.org_id = ?");
            tArgs.add(orgId);
        }
        long transferTotal = toLong(jdbcTemplate.queryForObject(
                tSql.toString() + (q.getDeptId() != null ? " AND t.to_dept_id = ?" : ""), Long.class,
                withTail(tArgs, q.getDeptId()).toArray()));
        long transferOut = q.getDeptId() == null ? transferTotal : toLong(jdbcTemplate.queryForObject(
                tSql.toString() + " AND t.from_dept_id = ?", Long.class, withTail(tArgs, q.getDeptId()).toArray()));
        today.put("transferIn", transferTotal);
        today.put("transferOut", transferOut);
        // 死亡: 死亡病历(record_type=9)记录日落在本日
        StringBuilder dSql = new StringBuilder(
                "SELECT COUNT(*) FROM his_inp_medical_record r WHERE r.deleted = 0 AND r.tenant_id = ?"
                        + " AND r.record_type = 9 AND DATE(r.record_time) = ?");
        List<Object> dArgs = new ArrayList<>();
        dArgs.add(tenantId());
        dArgs.add(day);
        if (orgId != null) {
            dSql.append(" AND r.org_id = ?");
            dArgs.add(orgId);
        }
        if (q.getDeptId() != null) {
            // 死亡归属按就诊科室
            dSql.append(" AND r.inp_visit_id IN (SELECT id FROM his_inp_visit WHERE dept_id = ?"
                    + " AND deleted = 0 AND tenant_id = ?)");
            dArgs.add(q.getDeptId());
            dArgs.add(tenantId());
        }
        today.put("death", toLong(jdbcTemplate.queryForObject(dSql.toString(), Long.class, dArgs.toArray())));

        // 30天趋势(逐日聚合)
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            trend.add(dayAgg(day.minusDays(i), orgId, q.getDeptId()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", day.toString());
        result.put("today", today);
        result.put("trend", trend);
        return R.ok(result);
    }

    /* ==================== DRG/DIP 分析 ==================== */

    /**
     * DRG/DIP分析: 期内 his_inp_settle(已撤销除外)按 drg_group_code/dip_code 分组分布。
     * 数据不足(无分组编码)时返回空结构体 + 提示信息。
     */
    public R<Map<String, Object>> getDrgAnalysis(ReportQueryDTO query, Long orgId) {
        ReportQueryDTO q = query == null ? new ReportQueryDTO() : query;
        LocalDate[] range = dateRange(q);
        LocalDate start = range[0];
        LocalDate end = range[1];

        List<Map<String, Object>> groupDistribution = groupDistribution("drg_group_code", start, end, orgId);
        List<Map<String, Object>> dipDistribution = groupDistribution("dip_code", start, end, orgId);

        long groupCount = 0;
        for (Map<String, Object> g : groupDistribution) {
            groupCount += toLong(g.get("count"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("groupDistribution", groupDistribution);
        result.put("dipDistribution", dipDistribution);
        result.put("groupSettleCount", groupCount);
        result.put("message", groupDistribution.isEmpty() && dipDistribution.isEmpty()
                ? "暂无DRG/DIP分组数据(需在住院结算单回写 drg_group_code / dip_code 后再统计)" : null);
        return R.ok(result);
    }

    /* ==================== 报表快照 ==================== */

    /** 保存报表快照(床位/费用/科室/住院日/DRG五类按日落盘; orgId 缺省取当前登录机构)。 */
    public R<Void> saveSnapshot(Long orgId, Integer reportType, LocalDate date, Long deptId, Long wardId,
                                String dataJson) {
        if (reportType == null) {
            throw new BizException(400, "报表类型不能为空");
        }
        if (date == null) {
            throw new BizException(400, "报表日期不能为空");
        }
        HisInpReportSnapshot snapshot = new HisInpReportSnapshot();
        snapshot.setOrgId(orgId != null ? orgId : currentOrgId());
        snapshot.setReportType(reportType);
        snapshot.setReportDate(date);
        snapshot.setDeptId(deptId);
        snapshot.setWardId(wardId);
        snapshot.setData(dataJson);
        snapshot.setGeneratedTime(LocalDateTime.now());
        snapshotMapper.insert(snapshot);
        log.info("保存住院报表快照: reportType={}, date={}, orgId={}, deptId={}, wardId={}",
                reportType, date, snapshot.getOrgId(), deptId, wardId);
        return R.ok();
    }

    /* ==================== 报表钻取 ==================== */

    /** 就诊状态名称: 1待入院 2在院 3出院办理中 4已出院 5已取消。 */
    private static final Map<Integer, String> VISIT_STATUS_NAMES = new HashMap<>();

    static {
        VISIT_STATUS_NAMES.put(1, "待入院");
        VISIT_STATUS_NAMES.put(2, "在院");
        VISIT_STATUS_NAMES.put(3, "出院办理中");
        VISIT_STATUS_NAMES.put(4, "已出院");
        VISIT_STATUS_NAMES.put(5, "已取消");
    }

    /**
     * 报表钻取分页明细(报表数字下钻):
     * - fee/dept: 期内费用明细(his_inp_charge_detail status=1, 可按科室过滤), 对应费用分析/科室经营报表;
     * - bed: 当前在院患者列表(visit_status IN (2, 3)), 床位统计是当前快照, 忽略日期参数;
     * 其余类型返回400。返回 {reportType, deptId, dateFrom, dateTo, total, page, size, records}。
     */
    public R<Map<String, Object>> drillDown(String reportType, Long deptId, LocalDate dateFrom, LocalDate dateTo,
                                            Long orgId, int page, int size) {
        String type = reportType == null ? "" : reportType.trim().toLowerCase();
        int p = Math.max(page, 1);
        int s = size <= 0 ? 20 : Math.min(size, 100);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportType", type);
        result.put("deptId", deptId);
        if ("fee".equals(type) || "dept".equals(type)) {
            LocalDate start = dateFrom != null ? dateFrom : LocalDate.now().minusDays(29);
            LocalDate end = dateTo != null ? dateTo : LocalDate.now();
            if (end.isBefore(start)) {
                throw new BizException(400, "结束日期不能早于开始日期");
            }
            result.put("dateFrom", start.toString());
            result.put("dateTo", end.toString());
            result.put("records", drillChargeDetails(deptId, start, end, orgId, p, s));
            result.put("total", drillChargeCount(deptId, start, end, orgId));
        } else if ("bed".equals(type)) {
            result.put("dateFrom", "");
            result.put("dateTo", "");
            result.put("records", drillVisitPatients(deptId, orgId, p, s));
            result.put("total", drillVisitCount(deptId, orgId));
        } else {
            throw new BizException(400, "不支持的钻取类型: " + type + "(支持 fee/dept/bed)");
        }
        result.put("page", p);
        result.put("size", s);
        return R.ok(result);
    }

    /** 费用明细钻取: 期内已计费明细按(可选)科室过滤分页(与 getFeeAnalysis 同口径: status=1 + charge_date 区间)。 */
    private List<Map<String, Object>> drillChargeDetails(Long deptId, LocalDate start, LocalDate end,
                                                         Long orgId, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT c.charge_date, IFNULL(v.inp_no, '') inp_no, IFNULL(p.name, '') patient_name,"
                        + " IFNULL(d.dept_name, '未分科') dept_name, c.fee_type, IFNULL(c.item_code, '') item_code,"
                        + " IFNULL(c.item_name, '') item_name, c.quantity, c.unit_price, c.amount"
                        + " FROM his_inp_charge_detail c"
                        + " JOIN his_inp_visit v ON v.id = c.inp_visit_id AND v.deleted = 0"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " LEFT JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0"
                        + " WHERE c.deleted = 0 AND c.tenant_id = ? AND c.status = 1"
                        + " AND c.charge_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(start);
        args.add(end);
        if (orgId != null) {
            sql.append(" AND c.org_id = ?");
            args.add(orgId);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        sql.append(" ORDER BY c.charge_date DESC, c.id DESC LIMIT ?, ?");
        args.add((page - 1) * (long) size);
        args.add(size);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            int feeType = row.get("fee_type") == null ? 0 : ((Number) row.get("fee_type")).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chargeDate", str(row.get("charge_date")));
            m.put("inpNo", str(row.get("inp_no")));
            m.put("patientName", str(row.get("patient_name")));
            m.put("deptName", str(row.get("dept_name")));
            m.put("feeType", feeType);
            m.put("feeTypeName", FEE_TYPE_NAMES.getOrDefault(feeType, "其他"));
            m.put("itemCode", str(row.get("item_code")));
            m.put("itemName", str(row.get("item_name")));
            m.put("quantity", toBd(row.get("quantity")));
            m.put("unitPrice", toBd(row.get("unit_price")));
            m.put("amount", toBd(row.get("amount")));
            out.add(m);
        }
        return out;
    }

    /** 费用明细钻取总数(与 drillChargeDetails 同条件)。 */
    private long drillChargeCount(Long deptId, LocalDate start, LocalDate end, Long orgId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM his_inp_charge_detail c"
                        + " JOIN his_inp_visit v ON v.id = c.inp_visit_id AND v.deleted = 0"
                        + " WHERE c.deleted = 0 AND c.tenant_id = ? AND c.status = 1"
                        + " AND c.charge_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(start);
        args.add(end);
        if (orgId != null) {
            sql.append(" AND c.org_id = ?");
            args.add(orgId);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        return toLong(jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray()));
    }

    /** 在院患者钻取: 当前在院(visit_status IN (2,3))患者分页, 主治医师姓名批量补齐。 */
    private List<Map<String, Object>> drillVisitPatients(Long deptId, Long orgId, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT IFNULL(v.inp_no, '') inp_no, IFNULL(p.name, '') patient_name,"
                        + " IFNULL(NULLIF(p.gender_name, ''), p.gender) gender, p.age,"
                        + " IFNULL(d.dept_name, '未分科') dept_name, IFNULL(w.ward_name, '') ward_name,"
                        + " IFNULL(b.bed_no, '') bed_no, v.admit_date, v.visit_status, v.doctor_id"
                        + " FROM his_inp_visit v"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " LEFT JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0"
                        + " LEFT JOIN his_ward w ON w.id = v.ward_id AND w.deleted = 0"
                        + " LEFT JOIN his_bed b ON b.id = v.bed_id AND b.deleted = 0"
                        + " WHERE v.deleted = 0 AND v.tenant_id = ? AND v.visit_status IN (2, 3)");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (orgId != null) {
            sql.append(" AND v.org_id = ?");
            args.add(orgId);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        sql.append(" ORDER BY v.admit_date DESC, v.id DESC LIMIT ?, ?");
        args.add((page - 1) * (long) size);
        args.add(size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Long> doctorIds = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long docId = toLong(r.get("doctor_id"));
            if (docId > 0 && !doctorIds.contains(docId)) {
                doctorIds.add(docId);
            }
        }
        Map<Long, String> doctors = staffNames(doctorIds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String gender = str(r.get("gender"));
            int status = r.get("visit_status") == null ? 0 : ((Number) r.get("visit_status")).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inpNo", str(r.get("inp_no")));
            m.put("patientName", str(r.get("patient_name")));
            m.put("gender", "1".equals(gender) ? "男" : "2".equals(gender) ? "女" : gender);
            m.put("age", r.get("age"));
            m.put("deptName", str(r.get("dept_name")));
            m.put("wardName", str(r.get("ward_name")));
            m.put("bedNo", str(r.get("bed_no")));
            m.put("admitDate", str(r.get("admit_date")));
            m.put("visitStatus", status);
            m.put("visitStatusText", VISIT_STATUS_NAMES.getOrDefault(status, "未知"));
            m.put("doctorName", doctors.getOrDefault(toLong(r.get("doctor_id")), ""));
            out.add(m);
        }
        return out;
    }

    /** 在院患者钻取总数(与 drillVisitPatients 同条件)。 */
    private long drillVisitCount(Long deptId, Long orgId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM his_inp_visit v WHERE v.deleted = 0 AND v.tenant_id = ?"
                        + " AND v.visit_status IN (2, 3)");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (orgId != null) {
            sql.append(" AND v.org_id = ?");
            args.add(orgId);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        return toLong(jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray()));
    }

    /* ==================== 内部实现 ==================== */

    /** 结算分组分布(按指定分组编码列; 已撤销结算除外)。 */
    private List<Map<String, Object>> groupDistribution(String codeColumn, LocalDate start, LocalDate end,
                                                        Long orgId) {
        StringBuilder sql = new StringBuilder(
                "SELECT IFNULL(s.").append(codeColumn).append(", '') group_code, COUNT(*) cnt,"
                        + " IFNULL(AVG(s.total_amount), 0) avg_cost FROM his_inp_settle s"
                        + " WHERE s.deleted = 0 AND s.tenant_id = ? AND s.").append(codeColumn)
                .append(" IS NOT NULL AND s.").append(codeColumn).append(" <> ''")
                .append(" AND IFNULL(s.yb_status, 0) <> 4 AND DATE(s.settle_time) BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(start);
        args.add(end);
        if (orgId != null) {
            sql.append(" AND s.org_id = ?");
            args.add(orgId);
        }
        sql.append(" GROUP BY s.").append(codeColumn).append(" ORDER BY cnt DESC");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("groupCode", str(row.get("group_code")));
            g.put("count", toLong(row.get("cnt")));
            g.put("avgCost", toBd(row.get("avg_cost")).setScale(2, RoundingMode.HALF_UP));
            out.add(g);
        }
        return out;
    }

    /** 单日出入院/在院聚合(在院=入院<=当日且未出院或出院晚于当日, 剔除待入院/已取消)。 */
    private Map<String, Object> dayAgg(LocalDate day, Long orgId, Long deptId) {
        StringBuilder sql = new StringBuilder(
                "SELECT IFNULL(SUM(CASE WHEN DATE(v.admit_date) = ? AND v.visit_status <> 5 THEN 1 ELSE 0 END), 0) new_admit,"
                        + " IFNULL(SUM(CASE WHEN v.visit_status = 4 AND DATE(v.discharge_date) = ? THEN 1 ELSE 0 END), 0) discharge,"
                        + " IFNULL(SUM(CASE WHEN DATE(v.admit_date) <= ? AND v.visit_status NOT IN (1, 5)"
                        + " AND (v.discharge_date IS NULL OR DATE(v.discharge_date) > ?) THEN 1 ELSE 0 END), 0) in_hospital"
                        + " FROM his_inp_visit v WHERE v.deleted = 0 AND v.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(day);
        args.add(day);
        args.add(day);
        args.add(day);
        args.add(tenantId());
        if (orgId != null) {
            sql.append(" AND v.org_id = ?");
            args.add(orgId);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        Map<String, Object> row = jdbcTemplate.queryForMap(sql.toString(), args.toArray());
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("date", day.toString());
        agg.put("newAdmit", toLong(row.get("new_admit")));
        agg.put("discharge", toLong(row.get("discharge")));
        agg.put("inHospital", toLong(row.get("in_hospital")));
        return agg;
    }

    /** 费用分析空结构(科室过滤无就诊时短路返回)。 */
    private Map<String, Object> emptyFeeAnalysis(LocalDate start, LocalDate end) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("totalAmount", BigDecimal.ZERO);
        result.put("patientCount", 0L);
        result.put("avgCostPerPatient", BigDecimal.ZERO);
        result.put("byType", new ArrayList<>());
        result.put("dailyTrend", new ArrayList<>());
        result.put("topDepts", new ArrayList<>());
        return result;
    }

    /** 日期区间: 缺省最近30天; 结束早于开始抛400。 */
    private static LocalDate[] dateRange(ReportQueryDTO q) {
        LocalDate start = q.getStartDate() != null ? q.getStartDate() : LocalDate.now().minusDays(29);
        LocalDate end = q.getEndDate() != null ? q.getEndDate() : LocalDate.now();
        if (end.isBefore(start)) {
            throw new BizException(400, "结束日期不能早于开始日期");
        }
        return new LocalDate[]{start, end};
    }

    /** 科室ID -> 名称(批量, 查不到不进映射)。 */
    private Map<Long, String> deptNames(Collection<Long> deptIds) {
        Map<Long, String> names = new HashMap<>();
        if (deptIds == null || deptIds.isEmpty()) {
            return names;
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, dept_name FROM his_dept WHERE deleted = 0 AND tenant_id = ? AND id IN (");
        args.add(tenantId());
        boolean first = true;
        for (Long id : deptIds) {
            if (!first) {
                sql.append(",");
            }
            sql.append("?");
            args.add(id);
            first = false;
        }
        sql.append(")");
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            names.put(toLong(row.get("id")), str(row.get("dept_name")));
        }
        return names;
    }

    /** 员工ID -> 姓名(批量, 查不到不进映射)。 */
    private Map<Long, String> staffNames(Collection<Long> staffIds) {
        Map<Long, String> names = new HashMap<>();
        if (staffIds == null || staffIds.isEmpty()) {
            return names;
        }
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, staff_name FROM his_staff WHERE deleted = 0 AND tenant_id = ? AND id IN (");
        args.add(tenantId());
        boolean first = true;
        for (Long id : staffIds) {
            if (!first) {
                sql.append(",");
            }
            sql.append("?");
            args.add(id);
            first = false;
        }
        sql.append(")");
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            names.put(toLong(row.get("id")), str(row.get("staff_name")));
        }
        return names;
    }

    /** 参数尾部追加可选值(deptId 过滤拼参)。 */
    private static List<Object> withTail(List<Object> args, Object tail) {
        List<Object> out = new ArrayList<>(args);
        if (tail != null) {
            out.add(tail);
        }
        return out;
    }

    private static Long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
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

    /** 使用率: occupied/total(0-1, 4位小数)。 */
    private static BigDecimal rate(long occupied, long total) {
        return div(BigDecimal.valueOf(occupied), BigDecimal.valueOf(total), 4);
    }

    /** 安全除法(除零返回0)。 */
    private static BigDecimal div(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return (numerator == null ? BigDecimal.ZERO : numerator)
                .divide(denominator, scale, RoundingMode.HALF_UP);
    }

    /** 当前登录机构ID(未登录回落0, 供快照兜底)。 */
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
