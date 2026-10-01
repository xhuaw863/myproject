package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.mr.HisMrCatalog;
import com.yb.hi.entity.mr.HisMrRecall;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.mr.HisMrRecallMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病案收回/回收服务(P1-A): 出院病案从临床科室回收至病案室并上架的流转追踪。
 * 铁律: 不回写临床首页, 仅在编目侧登记; 归属机构取自就诊, 写操作 requireSelfOrgWrite。
 * recall_status: 1待收回 2已收回 3逾期。
 */
@Slf4j
@Service
public class MrRecallService {

    /** 出院后应回收时限(天), 用于推算 due_date。 */
    private static final int DUE_DAYS = 2;

    private final HisMrRecallMapper recallMapper;
    private final MrCatalogService catalogService;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrRecallService(HisMrRecallMapper recallMapper, MrCatalogService catalogService,
                           JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.recallMapper = recallMapper;
        this.catalogService = catalogService;
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /** 收回作业列表: 关联就诊/患者/科室, 支持状态/科室/关键字过滤, 分页。 */
    public IPage<Map<String, Object>> listPage(long page, long size, Integer recallStatus, String keyword, Long deptId) {
        Long tid = TenantContext.require();
        StringBuilder where = new StringBuilder(" WHERE r.deleted = 0 AND r.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND r.org_id = ?");
            args.add(scopeOrg);
        }
        if (recallStatus != null) {
            where.append(" AND r.recall_status = ?");
            args.add(recallStatus);
        }
        if (deptId != null) {
            where.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            where.append(" AND (p.name LIKE ? OR r.barcode LIKE ? OR v.inp_no LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String base = " FROM his_mr_recall r"
                + " JOIN his_inp_visit v ON v.id = r.visit_id AND v.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = r.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_dept d ON d.id = v.dept_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + base + where, Long.class, args.toArray());
        long p = Math.max(1, page);
        long s = size <= 0 ? 20 : Math.min(size, 200);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.id, r.visit_id AS visitId, r.catalog_id AS catalogId, r.barcode,"
                        + " r.recall_status AS recallStatus, r.due_date AS dueDate, r.recall_time AS recallTime,"
                        + " r.recall_user_name AS recallUserName, r.shelf_flag AS shelfFlag, r.shelf_location AS shelfLocation,"
                        + " r.remark, p.name AS patientName, v.inp_no AS inpNo, d.dept_name AS deptName,"
                        + " v.discharge_date AS dischargeDate"
                        + base + where + " ORDER BY r.recall_status ASC, r.due_date ASC, r.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setRecords(rows);
        result.setTotal(total == null ? 0 : total);
        return result;
    }

    /**
     * 批量建单(收回登记): 为已出院就诊生成待收回记录(已存在则跳过); due_date = 出院日期 + {@value #DUE_DAYS} 天。
     * 条码默认取住院号(inp_no)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> enroll(List<Long> visitIds) {
        guard.requireSelfOrgWrite();
        if (visitIds == null || visitIds.isEmpty()) {
            throw new BizException(400, "请选择需登记的病案");
        }
        Long tid = TenantContext.require();
        int created = 0;
        int skipped = 0;
        for (Long visitId : visitIds) {
            if (visitId == null) {
                continue;
            }
            HisMrRecall exist = recallMapper.selectOne(new QueryWrapper<HisMrRecall>()
                    .eq("visit_id", visitId).orderByDesc("id").last("LIMIT 1"));
            if (exist != null) {
                skipped++;
                continue;
            }
            List<Map<String, Object>> vs = jdbcTemplate.queryForList(
                    "SELECT patient_id AS patientId, org_id AS orgId, inp_no AS inpNo, discharge_date AS dischargeDate"
                            + " FROM his_inp_visit WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    visitId, tid);
            if (vs.isEmpty()) {
                throw new BizException(404, "就诊不存在: " + visitId);
            }
            Map<String, Object> v = vs.get(0);
            HisMrCatalog c = catalogService.findByVisit(visitId);
            HisMrRecall r = new HisMrRecall();
            r.setVisitId(visitId);
            r.setCatalogId(c == null ? null : c.getId());
            r.setPatientId(MrCatalogService.longOf(v.get("patientId")));
            r.setOrgId(MrCatalogService.longOf(v.get("orgId")));
            r.setBarcode(MrCatalogService.strOf(v.get("inpNo")));
            r.setRecallStatus(1);
            LocalDateTime discharge = toLdt(v.get("dischargeDate"));
            r.setDueDate(discharge == null ? null : discharge.plusDays(DUE_DAYS));
            r.setShelfFlag(0);
            recallMapper.insert(r);
            created++;
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("created", created);
        res.put("skipped", skipped);
        return res;
    }

    /**
     * 扫描/登记回收: body {barcode?|visitId?, shelfLocation?, remark?}。
     * 命中待收回记录 -> 置为已收回(2)+回收时间+回收人; 无记录则按就诊自动补建并直接回收。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisMrRecall scanRecall(Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        if (body == null) {
            throw new BizException(400, "请求体为空");
        }
        String barcode = trim(MrCatalogService.strOf(body.get("barcode")));
        Long visitId = MrCatalogService.longOf(body.get("visitId"));
        if (barcode == null && visitId == null) {
            throw new BizException(400, "请提供病案条码或就诊ID");
        }
        LoginUser lu = UserContext.get();
        HisMrRecall r;
        if (visitId != null) {
            r = recallMapper.selectOne(new QueryWrapper<HisMrRecall>()
                    .eq("visit_id", visitId).orderByDesc("id").last("LIMIT 1"));
        } else {
            r = recallMapper.selectOne(new QueryWrapper<HisMrRecall>()
                    .eq("barcode", barcode).orderByDesc("id").last("LIMIT 1"));
        }
        if (r == null) {
            Long resolvedVisit = visitId;
            if (resolvedVisit == null) {
                Long tid = TenantContext.require();
                List<Map<String, Object>> vs = jdbcTemplate.queryForList(
                        "SELECT id FROM his_inp_visit WHERE inp_no = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                        barcode, tid);
                if (!vs.isEmpty()) {
                    resolvedVisit = MrCatalogService.longOf(vs.get(0).get("id"));
                }
            }
            if (resolvedVisit == null) {
                throw new BizException(404, "未找到对应病案(条码未登记或就诊不存在)");
            }
            r = buildBase(resolvedVisit, barcode);
        }
        r.setRecallStatus(2);
        r.setRecallTime(LocalDateTime.now());
        r.setRecallUserId(lu == null ? null : lu.getStaffId());
        r.setRecallUserName(lu == null ? null : lu.getRealName());
        String shelfLocation = trim(MrCatalogService.strOf(body.get("shelfLocation")));
        if (shelfLocation != null) {
            r.setShelfLocation(shelfLocation);
            r.setShelfFlag(1);
        }
        String remark = MrCatalogService.strOf(body.get("remark"));
        if (StringUtils.hasText(remark)) {
            r.setRemark(remark);
        }
        if (r.getId() == null) {
            recallMapper.insert(r);
        } else {
            recallMapper.updateById(r);
        }
        return r;
    }

    /** 上架: 置 shelf_flag=1 + 库位(要求已收回)。 */
    @Transactional(rollbackFor = Exception.class)
    public void shelf(Long id, String location) {
        guard.requireSelfOrgWrite();
        HisMrRecall r = recallMapper.selectById(id);
        if (r == null) {
            throw new BizException(404, "收回记录不存在");
        }
        if (MrCatalogService.intOf(r.getRecallStatus(), 1) != 2) {
            throw new BizException(409, "请先回收再上架");
        }
        String loc = trim(location);
        if (loc == null) {
            throw new BizException(400, "请填写上架库位/架号");
        }
        r.setShelfLocation(loc);
        r.setShelfFlag(1);
        recallMapper.updateById(r);
    }

    /** 逾期刷新: 待收回(1)且 due_date 已过的记录置为逾期(3); 返回处理数。 */
    @Transactional(rollbackFor = Exception.class)
    public int refreshOverdue() {
        guard.requireSelfOrgWrite();
        Long tid = TenantContext.require();
        StringBuilder sql = new StringBuilder("UPDATE his_mr_recall SET recall_status = 3, update_time = NOW()"
                + " WHERE deleted = 0 AND tenant_id = ? AND recall_status = 1"
                + " AND due_date IS NOT NULL AND due_date < NOW()");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            sql.append(" AND org_id = ?");
            args.add(scopeOrg);
        }
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /** 回收率报表: 按科室汇总(总数/已收回/待收回/逾期)+全院合计, 可选应回收日期区间。 */
    public Map<String, Object> rate(String from, String to) {
        Long tid = TenantContext.require();
        StringBuilder where = new StringBuilder(" WHERE r.deleted = 0 AND r.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND r.org_id = ?");
            args.add(scopeOrg);
        }
        LocalDateTime f = parseDate(from);
        LocalDateTime t = parseDate(to);
        if (f != null) {
            where.append(" AND r.due_date >= ?");
            args.add(f);
        }
        if (t != null) {
            where.append(" AND r.due_date <= ?");
            args.add(t);
        }
        String base = " FROM his_mr_recall r"
                + " JOIN his_inp_visit v ON v.id = r.visit_id AND v.deleted = 0"
                + " LEFT JOIN his_dept d ON d.id = v.dept_id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT IFNULL(d.dept_name,'未知科室') AS deptName, COUNT(*) AS total,"
                        + " SUM(CASE WHEN r.recall_status = 2 THEN 1 ELSE 0 END) AS recalled,"
                        + " SUM(CASE WHEN r.recall_status = 1 THEN 1 ELSE 0 END) AS pending,"
                        + " SUM(CASE WHEN r.recall_status = 3 THEN 1 ELSE 0 END) AS overdue"
                        + base + where + " GROUP BY d.dept_name ORDER BY total DESC",
                args.toArray());
        long total = 0;
        long recalled = 0;
        long pending = 0;
        long overdue = 0;
        for (Map<String, Object> row : rows) {
            total += asLong(row.get("total"));
            recalled += asLong(row.get("recalled"));
            pending += asLong(row.get("pending"));
            overdue += asLong(row.get("overdue"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("recalled", recalled);
        summary.put("pending", pending);
        summary.put("overdue", overdue);
        summary.put("rate", total == 0 ? 0 : Math.round(recalled * 10000.0 / total) / 100.0);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("rows", rows);
        res.put("summary", summary);
        return res;
    }

    // ---- 内部辅助 ----

    /** 按就诊补建一条收回记录(未 insert, 由调用方决定插入时机)。 */
    private HisMrRecall buildBase(Long visitId, String barcode) {
        Long tid = TenantContext.require();
        List<Map<String, Object>> vs = jdbcTemplate.queryForList(
                "SELECT patient_id AS patientId, org_id AS orgId, inp_no AS inpNo, discharge_date AS dischargeDate"
                        + " FROM his_inp_visit WHERE id = ? AND tenant_id = ? AND deleted = 0",
                visitId, tid);
        if (vs.isEmpty()) {
            throw new BizException(404, "就诊不存在: " + visitId);
        }
        Map<String, Object> v = vs.get(0);
        HisMrCatalog c = catalogService.findByVisit(visitId);
        HisMrRecall r = new HisMrRecall();
        r.setVisitId(visitId);
        r.setCatalogId(c == null ? null : c.getId());
        r.setPatientId(MrCatalogService.longOf(v.get("patientId")));
        r.setOrgId(MrCatalogService.longOf(v.get("orgId")));
        r.setBarcode(barcode != null ? barcode : MrCatalogService.strOf(v.get("inpNo")));
        r.setRecallStatus(1);
        LocalDateTime discharge = toLdt(v.get("dischargeDate"));
        r.setDueDate(discharge == null ? null : discharge.plusDays(DUE_DAYS));
        r.setShelfFlag(0);
        return r;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static long asLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private LocalDateTime toLdt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDateTime) {
            return (LocalDateTime) o;
        }
        if (o instanceof Timestamp) {
            return ((Timestamp) o).toLocalDateTime();
        }
        if (o instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) o).atStartOfDay();
        }
        return parseDate(String.valueOf(o));
    }

    private LocalDateTime parseDate(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String v = s.trim().replace('T', ' ');
        try {
            if (v.length() <= 10) {
                return java.time.LocalDate.parse(v).atStartOfDay();
            }
            if (v.length() == 16) {
                v = v + ":00";
            }
            return LocalDateTime.parse(v.replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }
}
