package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.mr.HisMrBorrow;
import com.yb.hi.entity.mr.HisMrCatalog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.mr.HisMrBorrowMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病案借阅服务(P1-B): 病案实体借出/归还登记与借阅单打印数据源。
 * 铁律: 不回写临床首页, 仅在编目侧登记; 归属机构取自就诊, 写操作 requireSelfOrgWrite。
 * borrow_status: 1借出 2已归还 3逾期。
 */
@Slf4j
@Service
public class MrBorrowService {

    /** 默认借用期限(天)。 */
    private static final int DEFAULT_LOAN_DAYS = 7;
    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HisMrBorrowMapper borrowMapper;
    private final MrCatalogService catalogService;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrBorrowService(HisMrBorrowMapper borrowMapper, MrCatalogService catalogService,
                           JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.borrowMapper = borrowMapper;
        this.catalogService = catalogService;
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /** 借阅作业列表: 关联就诊/患者, 支持状态/科室/关键字过滤, 分页。 */
    public IPage<Map<String, Object>> listPage(long page, long size, Integer borrowStatus, String keyword, Long borrowerDeptId) {
        Long tid = TenantContext.require();
        StringBuilder where = new StringBuilder(" WHERE b.deleted = 0 AND b.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND b.org_id = ?");
            args.add(scopeOrg);
        }
        if (borrowStatus != null) {
            where.append(" AND b.borrow_status = ?");
            args.add(borrowStatus);
        }
        if (borrowerDeptId != null) {
            where.append(" AND b.borrower_dept_id = ?");
            args.add(borrowerDeptId);
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            where.append(" AND (p.name LIKE ? OR b.borrow_no LIKE ? OR b.borrower_name LIKE ? OR v.inp_no LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String base = " FROM his_mr_borrow b"
                + " JOIN his_inp_visit v ON v.id = b.visit_id AND v.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = b.patient_id AND p.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + base + where, Long.class, args.toArray());
        long p = Math.max(1, page);
        long s = size <= 0 ? 20 : Math.min(size, 200);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT b.id, b.visit_id AS visitId, b.catalog_id AS catalogId, b.borrow_no AS borrowNo,"
                        + " b.borrower_id AS borrowerId, b.borrower_name AS borrowerName,"
                        + " b.borrower_dept_id AS borrowerDeptId, b.borrower_dept_name AS borrowerDeptName,"
                        + " b.purpose, b.borrow_time AS borrowTime, b.expect_return_date AS expectReturnDate,"
                        + " b.actual_return_time AS actualReturnTime, b.borrow_status AS borrowStatus,"
                        + " b.operator_name AS operatorName, p.name AS patientName, v.inp_no AS inpNo,"
                        + " v.discharge_date AS dischargeDate"
                        + base + where + " ORDER BY b.borrow_status ASC, b.expect_return_date ASC, b.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setRecords(rows);
        result.setTotal(total == null ? 0 : total);
        return result;
    }

    /**
     * 借出登记: body {visitId, borrowerName, borrowerId?, borrowerDeptId?, borrowerDeptName?, purpose, expectReturnDate?}。
     * 自动生成借阅单号, 默认借用期限 {@value #DEFAULT_LOAN_DAYS} 天; 借阅人科室名缺省时按 borrowerId 反查。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisMrBorrow lend(Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        if (body == null) {
            throw new BizException(400, "请求体为空");
        }
        Long visitId = MrCatalogService.longOf(body.get("visitId"));
        if (visitId == null) {
            throw new BizException(400, "缺少就诊ID");
        }
        String borrowerName = trim(MrCatalogService.strOf(body.get("borrowerName")));
        if (borrowerName == null) {
            throw new BizException(400, "请填写借阅人姓名");
        }
        Long tid = TenantContext.require();
        List<Map<String, Object>> vs = jdbcTemplate.queryForList(
                "SELECT patient_id AS patientId, org_id AS orgId FROM his_inp_visit"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                visitId, tid);
        if (vs.isEmpty()) {
            throw new BizException(404, "就诊不存在");
        }
        Map<String, Object> v = vs.get(0);
        HisMrCatalog c = catalogService.findByVisit(visitId);

        Long borrowerId = MrCatalogService.longOf(body.get("borrowerId"));
        Long deptId = MrCatalogService.longOf(body.get("borrowerDeptId"));
        String deptName = trim(MrCatalogService.strOf(body.get("borrowerDeptName")));
        if (deptName == null && deptId != null) {
            List<Map<String, Object>> ds = jdbcTemplate.queryForList(
                    "SELECT dept_name AS deptName FROM his_dept WHERE id = ? AND deleted = 0 LIMIT 1", deptId);
            if (!ds.isEmpty()) {
                deptName = MrCatalogService.strOf(ds.get(0).get("deptName"));
            }
        }

        LocalDateTime borrowTime = LocalDateTime.now();
        LocalDateTime expect = toLdt(body.get("expectReturnDate"));
        if (expect == null) {
            expect = borrowTime.plusDays(DEFAULT_LOAN_DAYS);
        }
        LoginUser lu = UserContext.get();

        HisMrBorrow b = new HisMrBorrow();
        b.setVisitId(visitId);
        b.setCatalogId(c == null ? null : c.getId());
        b.setPatientId(MrCatalogService.longOf(v.get("patientId")));
        b.setOrgId(MrCatalogService.longOf(v.get("orgId")));
        b.setBorrowNo(generateBorrowNo(tid));
        b.setBorrowerId(borrowerId);
        b.setBorrowerName(borrowerName);
        b.setBorrowerDeptId(deptId);
        b.setBorrowerDeptName(deptName);
        b.setPurpose(MrCatalogService.strOf(body.get("purpose")));
        b.setBorrowTime(borrowTime);
        b.setExpectReturnDate(expect);
        b.setBorrowStatus(1);
        b.setOperatorName(lu == null ? null : lu.getRealName());
        borrowMapper.insert(b);
        return b;
    }

    /** 归还登记: 置为已归还(2)+实际归还时间; 借出(1)或逾期(3)均可归还。 */
    @Transactional(rollbackFor = Exception.class)
    public HisMrBorrow giveBack(Long id) {
        guard.requireSelfOrgWrite();
        HisMrBorrow b = borrowMapper.selectById(id);
        if (b == null) {
            throw new BizException(404, "借阅记录不存在");
        }
        int st = MrCatalogService.intOf(b.getBorrowStatus(), 1);
        if (st == 2) {
            throw new BizException(409, "该病案已归还");
        }
        b.setBorrowStatus(2);
        b.setActualReturnTime(LocalDateTime.now());
        borrowMapper.updateById(b);
        return b;
    }

    /** 逾期刷新: 借出(1)且应归还日期已过的记录置为逾期(3); 返回处理数。 */
    @Transactional(rollbackFor = Exception.class)
    public int refreshOverdue() {
        guard.requireSelfOrgWrite();
        Long tid = TenantContext.require();
        StringBuilder sql = new StringBuilder("UPDATE his_mr_borrow SET borrow_status = 3, update_time = NOW()"
                + " WHERE deleted = 0 AND tenant_id = ? AND borrow_status = 1"
                + " AND expect_return_date IS NOT NULL AND expect_return_date < NOW()");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            sql.append(" AND org_id = ?");
            args.add(scopeOrg);
        }
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /** 借阅单打印数据源: 返回单条借阅记录的患者/就诊/借阅信息与状态文本。 */
    public Map<String, Object> slip(Long id) {
        Long tid = TenantContext.require();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT b.id, b.borrow_no AS borrowNo, b.borrow_time AS borrowTime,"
                        + " b.expect_return_date AS expectReturnDate, b.actual_return_time AS actualReturnTime,"
                        + " b.borrow_status AS borrowStatus, b.borrower_name AS borrowerName,"
                        + " b.borrower_dept_name AS borrowerDeptName, b.purpose, b.operator_name AS operatorName,"
                        + " p.name AS patientName, p.gender_name AS genderName, p.age, v.inp_no AS inpNo,"
                        + " v.discharge_date AS dischargeDate, d.dept_name AS deptName"
                        + " FROM his_mr_borrow b"
                        + " JOIN his_inp_visit v ON v.id = b.visit_id AND v.deleted = 0"
                        + " LEFT JOIN his_patient p ON p.id = b.patient_id AND p.deleted = 0"
                        + " LEFT JOIN his_dept d ON d.id = v.dept_id"
                        + " WHERE b.id = ? AND b.tenant_id = ? AND b.deleted = 0 LIMIT 1",
                id, tid);
        if (rows.isEmpty()) {
            throw new BizException(404, "借阅记录不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("statusText", statusText(MrCatalogService.intOf(m.get("borrowStatus"), 1)));
        return m;
    }

    // ---- 内部辅助 ----

    /** 借阅单号: BR + yyyyMMdd + 当日序号(3位)。 */
    private String generateBorrowNo(Long tid) {
        LocalDate today = LocalDate.now();
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_mr_borrow WHERE tenant_id = ? AND deleted = 0 AND DATE(borrow_time) = ?",
                Integer.class, tid, today.toString());
        int seq = (cnt == null ? 0 : cnt) + 1;
        return "BR" + today.format(NO_DAY) + String.format("%03d", seq);
    }

    private static String statusText(int st) {
        switch (st) {
            case 2:
                return "已归还";
            case 3:
                return "逾期";
            case 1:
            default:
                return "借出";
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
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
                return LocalDate.parse(v).atStartOfDay();
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
