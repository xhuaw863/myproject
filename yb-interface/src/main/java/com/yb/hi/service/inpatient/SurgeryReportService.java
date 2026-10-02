package com.yb.hi.service.inpatient;

import com.yb.hi.dto.inpatient.SurgeryReportQueryDTO;
import com.yb.hi.entity.inpatient.HisInpReportSnapshot;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpReportSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手麻统计报表服务(P2a): 手术量/麻醉分布/手术时长/费用/质量/工作台KPI 六类只读统计 + 报表快照落盘。
 * 口径:
 * - JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0;
 * - 日期区间按 his_surgery.schedule_date(排期日)过滤, 缺省最近30天; 比率输出 0-1 小数(4位);
 * - 机构隔离走 orgId(scopeOrgId 结果, null=本租户全部); 快照复用 his_inp_report_snapshot,
 *   report_type 扩用整数码 6手术量 7麻醉 8时长 9手术费用 10质量 11手术KPI(与住院报表 1-5 不冲突)。
 */
@Slf4j
@Service
public class SurgeryReportService {

    /** 快照报表类型(手麻): 与 his_inp_report_snapshot.report_type 注释共存(1-5住院, 6-11手麻)。 */
    public static final int RT_VOLUME = 6;
    public static final int RT_ANESTHESIA = 7;
    public static final int RT_DURATION = 8;
    public static final int RT_FEE = 9;
    public static final int RT_QUALITY = 10;
    public static final int RT_DASHBOARD = 11;

    private static final Map<Integer, String> SURG_LEVEL_NAMES = new LinkedHashMap<>();
    private static final Map<Integer, String> ANESTH_TYPE_NAMES = new LinkedHashMap<>();
    private static final Map<Integer, String> FEE_CAT_NAMES = new LinkedHashMap<>();

    static {
        SURG_LEVEL_NAMES.put(1, "一级");
        SURG_LEVEL_NAMES.put(2, "二级");
        SURG_LEVEL_NAMES.put(3, "三级");
        SURG_LEVEL_NAMES.put(4, "四级");
        ANESTH_TYPE_NAMES.put(1, "全麻");
        ANESTH_TYPE_NAMES.put(2, "局麻");
        ANESTH_TYPE_NAMES.put(3, "椎管内");
        ANESTH_TYPE_NAMES.put(4, "神经阻滞");
        ANESTH_TYPE_NAMES.put(5, "复合");
        ANESTH_TYPE_NAMES.put(6, "其他");
        FEE_CAT_NAMES.put(1, "手术费");
        FEE_CAT_NAMES.put(2, "麻醉费");
        FEE_CAT_NAMES.put(3, "监测费");
        FEE_CAT_NAMES.put(4, "耗材费");
        FEE_CAT_NAMES.put(5, "药品费");
        FEE_CAT_NAMES.put(6, "其他");
    }

    private final HisInpReportSnapshotMapper snapshotMapper;
    private final JdbcTemplate jdbcTemplate;

    public SurgeryReportService(HisInpReportSnapshotMapper snapshotMapper, JdbcTemplate jdbcTemplate) {
        this.snapshotMapper = snapshotMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 手术量 ==================== */

    /** 手术量: 总量 + 按级别/科室/术者/日期分组 + 申请漏斗。 */
    public R<Map<String, Object>> volume(SurgeryReportQueryDTO q, Long orgId) {
        LocalDate[] range = resolveRange(q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startDate", range[0]);
        out.put("endDate", range[1]);
        // 总量(排除未申请前的纯登记)
        out.put("total", scalarLong("SELECT COUNT(*) FROM his_surgery s WHERE " + surgWhere(q, orgId, range), args(q, orgId, range)));
        out.put("byLevel", groupNamed(
                "SELECT s.surgery_level grp, COUNT(*) cnt FROM his_surgery s WHERE " + surgWhere(q, orgId, range)
                        + " GROUP BY s.surgery_level ORDER BY grp", q, orgId, range, SURG_LEVEL_NAMES));
        out.put("byDept", jdbcTemplate.queryForList(
                "SELECT IFNULL(d.dept_name,'未分科') name, COUNT(*) cnt FROM his_surgery s"
                        + " LEFT JOIN his_dept d ON d.id = s.dept_id AND d.deleted = 0 WHERE "
                        + surgWhere(q, orgId, range) + " GROUP BY s.dept_id, d.dept_name ORDER BY cnt DESC",
                args(q, orgId, range).toArray()));
        out.put("bySurgeon", jdbcTemplate.queryForList(
                "SELECT IFNULL(st.staff_name,'未知') name, COUNT(*) cnt FROM his_surgery s"
                        + " LEFT JOIN his_staff st ON st.id = s.surgeon_id AND st.deleted = 0 WHERE "
                        + surgWhere(q, orgId, range) + " AND s.surgeon_id IS NOT NULL"
                        + " GROUP BY s.surgeon_id, st.staff_name ORDER BY cnt DESC LIMIT 20",
                args(q, orgId, range).toArray()));
        out.put("byDate", jdbcTemplate.queryForList(
                "SELECT s.schedule_date d, COUNT(*) cnt FROM his_surgery s WHERE " + surgWhere(q, orgId, range)
                        + " AND s.schedule_date IS NOT NULL GROUP BY s.schedule_date ORDER BY s.schedule_date",
                args(q, orgId, range).toArray()));
        // 申请漏斗: his_surgery_apply 按 status 计数(1待复核 2待安排 4已安排 5已完成 3退回 6作废)
        out.put("applyFunnel", jdbcTemplate.queryForList(
                "SELECT a.status st, COUNT(*) cnt FROM his_surgery_apply a"
                        + " WHERE a.deleted = 0 AND a.tenant_id = ?" + (orgId != null ? " AND a.org_id = ?" : "")
                        + " GROUP BY a.status ORDER BY a.status",
                orgArgs(orgId)));
        return R.ok(out);
    }

    /* ==================== 麻醉分布 ==================== */

    /** 麻醉分布: 按麻醉类型/ASA分级计数 + 平均麻醉时长(诱导→拔管, 分钟)。 */
    public R<Map<String, Object>> anesthesia(SurgeryReportQueryDTO q, Long orgId) {
        LocalDate[] range = resolveRange(q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byType", groupNamed(
                "SELECT a.anesthesia_type grp, COUNT(*) cnt FROM his_anesthesia a"
                        + " JOIN his_surgery s ON s.id = a.surgery_id AND s.deleted = 0 WHERE a.deleted = 0 AND "
                        + surgWhere(q, orgId, range, "s") + " GROUP BY a.anesthesia_type ORDER BY grp",
                q, orgId, range, ANESTH_TYPE_NAMES, "s"));
        out.put("byAsa", groupNamed(
                "SELECT s.asa_grade grp, COUNT(*) cnt FROM his_surgery s WHERE " + surgWhere(q, orgId, range)
                        + " AND s.asa_grade IS NOT NULL GROUP BY s.asa_grade ORDER BY grp",
                q, orgId, range, null));
        List<Map<String, Object>> dur = jdbcTemplate.queryForList(
                "SELECT AVG(TIMESTAMPDIFF(MINUTE, a.induction_time, a.extubation_time)) avg_min, COUNT(*) c"
                        + " FROM his_anesthesia a JOIN his_surgery s ON s.id = a.surgery_id AND s.deleted = 0"
                        + " WHERE a.deleted = 0 AND a.induction_time IS NOT NULL AND a.extubation_time IS NOT NULL AND "
                        + surgWhere(q, orgId, range, "s"),
                args(q, orgId, range).toArray());
        out.put("avgAnesthesiaMin", dur.isEmpty() ? BigDecimal.ZERO : toBd(dur.get(0).get("avg_min")).setScale(1, RoundingMode.HALF_UP));
        return R.ok(out);
    }

    /* ==================== 手术时长 ==================== */

    /** 手术时长: 平均台内时长(start→end)与切口时长(切皮→缝合)分钟, 按级别/科室分档。 */
    public R<Map<String, Object>> duration(SurgeryReportQueryDTO q, Long orgId) {
        LocalDate[] range = resolveRange(q);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> avg = jdbcTemplate.queryForList(
                "SELECT AVG(TIMESTAMPDIFF(MINUTE, s.start_time, s.end_time)) op_min,"
                        + " AVG(TIMESTAMPDIFF(MINUTE, s.incision_time, s.suture_time)) inc_min, COUNT(*) c"
                        + " FROM his_surgery s WHERE " + surgWhere(q, orgId, range)
                        + " AND s.start_time IS NOT NULL AND s.end_time IS NOT NULL",
                args(q, orgId, range).toArray());
        out.put("avgOperateMin", avg.isEmpty() ? BigDecimal.ZERO : toBd(avg.get(0).get("op_min")).setScale(1, RoundingMode.HALF_UP));
        out.put("avgIncisionMin", avg.isEmpty() ? BigDecimal.ZERO : toBd(avg.get(0).get("inc_min")).setScale(1, RoundingMode.HALF_UP));
        out.put("timedCount", avg.isEmpty() ? 0L : toLong(avg.get(0).get("c")));
        List<Map<String, Object>> byLevel = jdbcTemplate.queryForList(
                "SELECT s.surgery_level grp, AVG(TIMESTAMPDIFF(MINUTE, s.start_time, s.end_time)) avg_min, COUNT(*) cnt"
                        + " FROM his_surgery s WHERE " + surgWhere(q, orgId, range)
                        + " AND s.start_time IS NOT NULL AND s.end_time IS NOT NULL GROUP BY s.surgery_level ORDER BY grp",
                args(q, orgId, range).toArray());
        for (Map<String, Object> row : byLevel) {
            row.put("name", SURG_LEVEL_NAMES.get(toLong(row.get("grp")).intValue()));
        }
        out.put("byLevel", byLevel);
        return R.ok(out);
    }

    /* ==================== 费用统计 ==================== */

    /** 手术费用: 按 fee_category 汇总金额 + 总费/例均费 + 术式 TOP。 */
    public R<Map<String, Object>> fee(SurgeryReportQueryDTO q, Long orgId) {
        LocalDate[] range = resolveRange(q);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> byCat = jdbcTemplate.queryForList(
                "SELECT f.fee_category grp, IFNULL(SUM(f.amount),0) amt, COUNT(*) cnt FROM his_surgery_fee f"
                        + " JOIN his_surgery s ON s.id = f.surgery_id AND s.deleted = 0 WHERE f.deleted = 0 AND "
                        + surgWhere(q, orgId, range, "s") + " GROUP BY f.fee_category ORDER BY grp",
                args(q, orgId, range).toArray());
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : byCat) {
            row.put("name", FEE_CAT_NAMES.get(toLong(row.get("grp")).intValue()));
            BigDecimal amt = toBd(row.get("amt"));
            row.put("amt", amt);
            total = total.add(amt);
        }
        out.put("byCategory", byCat);
        out.put("totalAmount", total);
        long surgCount = scalarLong("SELECT COUNT(*) FROM his_surgery s WHERE " + surgWhere(q, orgId, range), args(q, orgId, range));
        out.put("avgPerSurgery", div(total, BigDecimal.valueOf(surgCount), 2));
        out.put("topByName", jdbcTemplate.queryForList(
                "SELECT s.surgery_name name, COUNT(*) cnt, IFNULL(SUM(f.amount),0) amt FROM his_surgery s"
                        + " LEFT JOIN his_surgery_fee f ON f.surgery_id = s.id AND f.deleted = 0 WHERE "
                        + surgWhere(q, orgId, range) + " GROUP BY s.surgery_name ORDER BY cnt DESC LIMIT 10",
                args(q, orgId, range).toArray()));
        return R.ok(out);
    }

    /* ==================== 质量指标 ==================== */

    /** 质量: 切口类型分布 + 取消率 + 非计划二次手术(同就诊同术式重复)。 */
    public R<Map<String, Object>> quality(SurgeryReportQueryDTO q, Long orgId) {
        LocalDate[] range = resolveRange(q);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<Integer, String> incision = new LinkedHashMap<>();
        incision.put(1, "清洁");
        incision.put(2, "清洁-污染");
        incision.put(3, "污染");
        incision.put(4, "感染");
        out.put("byIncision", groupNamed(
                "SELECT s.incision_type grp, COUNT(*) cnt FROM his_surgery s WHERE " + surgWhere(q, orgId, range)
                        + " AND s.incision_type IS NOT NULL GROUP BY s.incision_type ORDER BY grp",
                q, orgId, range, incision));
        long total = scalarLong("SELECT COUNT(*) FROM his_surgery s WHERE " + surgWhere(q, orgId, range), args(q, orgId, range));
        long cancelled = scalarLong("SELECT COUNT(*) FROM his_surgery s WHERE " + surgWhere(q, orgId, range) + " AND s.status = 6", args(q, orgId, range));
        out.put("total", total);
        out.put("cancelled", cancelled);
        out.put("cancelRate", rate(cancelled, total));
        out.put("reopList", jdbcTemplate.queryForList(
                "SELECT s.inp_visit_id visit, s.surgery_name name, COUNT(*) cnt FROM his_surgery s WHERE "
                        + surgWhere(q, orgId, range) + " AND s.status = 5 AND s.inp_visit_id IS NOT NULL"
                        + " GROUP BY s.inp_visit_id, s.surgery_code, s.surgery_name HAVING COUNT(*) > 1"
                        + " ORDER BY cnt DESC LIMIT 20",
                args(q, orgId, range).toArray()));
        return R.ok(out);
    }

    /* ==================== 工作台 KPI ==================== */

    /** 工作台 KPI: 今日台次/在术/待安排/完成率/取消率/三四级占比(当日不受日期区间约束, 取当天快照)。 */
    public R<Map<String, Object>> dashboard(SurgeryReportQueryDTO q, Long orgId) {
        LocalDate today = LocalDate.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", today);
        out.put("todayCount", scalarLong(
                "SELECT COUNT(*) FROM his_surgery s WHERE s.deleted = 0 AND s.tenant_id = ?"
                        + (orgId != null ? " AND s.org_id = ?" : "") + " AND s.schedule_date = ?",
                withTail(orgArgs(orgId), today)));
        out.put("inOperation", scalarLong(
                "SELECT COUNT(*) FROM his_surgery s WHERE s.deleted = 0 AND s.tenant_id = ?"
                        + (orgId != null ? " AND s.org_id = ?" : "") + " AND s.status = 3",
                orgArgs(orgId)));
        out.put("pendingSchedule", scalarLong(
                "SELECT COUNT(*) FROM his_surgery s WHERE s.deleted = 0 AND s.tenant_id = ?"
                        + (orgId != null ? " AND s.org_id = ?" : "") + " AND s.status IN (1, 2, 7)",
                orgArgs(orgId)));
        // 近30天完成/取消/三四级占比
        LocalDate start = today.minusDays(29);
        List<Object> a30 = new ArrayList<>();
        a30.add(tenantId());
        if (orgId != null) {
            a30.add(orgId);
        }
        a30.add(start);
        a30.add(today);
        String w30 = "s.deleted = 0 AND s.tenant_id = ?" + (orgId != null ? " AND s.org_id = ?" : "")
                + " AND s.schedule_date BETWEEN ? AND ?";
        long cnt30 = scalarLong("SELECT COUNT(*) FROM his_surgery s WHERE " + w30, a30);
        long done30 = scalarLong("SELECT COUNT(*) FROM his_surgery s WHERE " + w30 + " AND s.status = 5", a30);
        long cancel30 = scalarLong("SELECT COUNT(*) FROM his_surgery s WHERE " + w30 + " AND s.status = 6", a30);
        long high30 = scalarLong("SELECT COUNT(*) FROM his_surgery s WHERE " + w30 + " AND s.surgery_level IN (3, 4)", a30);
        out.put("completeRate", rate(done30, cnt30));
        out.put("cancelRate", rate(cancel30, cnt30));
        out.put("level34Ratio", rate(high30, cnt30));
        out.put("range30Count", cnt30);
        return R.ok(out);
    }

    /* ==================== 报表快照 ==================== */

    /** 保存手麻报表快照(复用 his_inp_report_snapshot, report_type 6-11)。 */
    public R<Void> saveSnapshot(Long orgId, Integer reportType, LocalDate date, Long deptId, String dataJson) {
        if (reportType == null) {
            throw new BizException(400, "报表类型不能为空");
        }
        if (date == null) {
            throw new BizException(400, "报表日期不能为空");
        }
        HisInpReportSnapshot snap = new HisInpReportSnapshot();
        snap.setOrgId(orgId != null ? orgId : currentOrgId());
        snap.setReportType(reportType);
        snap.setReportDate(date);
        snap.setDeptId(deptId);
        snap.setData(dataJson);
        snap.setGeneratedTime(LocalDateTime.now());
        snapshotMapper.insert(snap);
        log.info("保存手麻报表快照: reportType={}, date={}, orgId={}", reportType, date, snap.getOrgId());
        return R.ok();
    }

    /* ==================== 内部辅助 ==================== */

    /** WHERE 片段(his_surgery 主表别名 s): 租户 + deleted + 日期区间(schedule_date) + 可选科室/术者/模块/机构。 */
    private String surgWhere(SurgeryReportQueryDTO q, Long orgId, LocalDate[] range) {
        return surgWhere(q, orgId, range, "s");
    }

    private String surgWhere(SurgeryReportQueryDTO q, Long orgId, LocalDate[] range, String alias) {
        StringBuilder sb = new StringBuilder(alias + ".deleted = 0 AND " + alias + ".tenant_id = ?");
        if (orgId != null) {
            sb.append(" AND ").append(alias).append(".org_id = ?");
        }
        sb.append(" AND ").append(alias).append(".schedule_date BETWEEN ? AND ?");
        if (q != null) {
            if (q.getDeptId() != null) {
                sb.append(" AND ").append(alias).append(".dept_id = ?");
            }
            if (q.getSurgeonId() != null) {
                sb.append(" AND ").append(alias).append(".surgeon_id = ?");
            }
            if (q.getModuleType() != null) {
                sb.append(" AND ").append(alias).append(".module_type = ?");
            }
        }
        return sb.toString();
    }

    /** 参数数组: tenant + [org] + [date range] + [dept/surgeon/module]。 */
    private List<Object> args(SurgeryReportQueryDTO q, Long orgId, LocalDate[] range) {
        List<Object> a = new ArrayList<>();
        a.add(tenantId());
        if (orgId != null) {
            a.add(orgId);
        }
        a.add(range[0]);
        a.add(range[1]);
        if (q != null) {
            if (q.getDeptId() != null) {
                a.add(q.getDeptId());
            }
            if (q.getSurgeonId() != null) {
                a.add(q.getSurgeonId());
            }
            if (q.getModuleType() != null) {
                a.add(q.getModuleType());
            }
        }
        return a;
    }

    /** 分组计数并回填名称(nameMap 为 null 时保留原始 grp 码)。 */
    private List<Map<String, Object>> groupNamed(String sql, SurgeryReportQueryDTO q, Long orgId,
                                                 LocalDate[] range, Map<Integer, String> nameMap) {
        return groupNamed(sql, q, orgId, range, nameMap, "s");
    }

    private List<Map<String, Object>> groupNamed(String sql, SurgeryReportQueryDTO q, Long orgId,
                                                 LocalDate[] range, Map<Integer, String> nameMap, String alias) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args(q, orgId, range).toArray());
        for (Map<String, Object> row : rows) {
            if (nameMap != null) {
                row.put("name", nameMap.get(toLong(row.get("grp")).intValue()));
            } else {
                row.put("name", row.get("grp"));
            }
        }
        return rows;
    }

    private LocalDate[] resolveRange(SurgeryReportQueryDTO q) {
        LocalDate end = q != null && q.getEndDate() != null ? q.getEndDate() : LocalDate.now();
        LocalDate start = q != null && q.getStartDate() != null ? q.getStartDate() : end.minusDays(29);
        if (start.isAfter(end)) {
            throw new BizException(400, "开始日期不能晚于结束日期");
        }
        return new LocalDate[]{start, end};
    }

    private List<Object> orgArgs(Long orgId) {
        List<Object> a = new ArrayList<>();
        a.add(tenantId());
        if (orgId != null) {
            a.add(orgId);
        }
        return a;
    }

    private long scalarLong(String sql, List<Object> args) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return v == null ? 0L : v;
    }

    private static List<Object> withTail(List<Object> args, Object tail) {
        List<Object> out = new ArrayList<>(args);
        out.add(tail);
        return out;
    }

    private static Long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
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

    private static BigDecimal rate(long num, long den) {
        return div(BigDecimal.valueOf(num), BigDecimal.valueOf(den), 4);
    }

    private static BigDecimal div(BigDecimal n, BigDecimal d, int scale) {
        if (d == null || d.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return (n == null ? BigDecimal.ZERO : n).divide(d, scale, RoundingMode.HALF_UP);
    }

    private static Long currentOrgId() {
        LoginUser lu = UserContext.get();
        return lu == null || lu.getOrgId() == null ? 0L : lu.getOrgId();
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
