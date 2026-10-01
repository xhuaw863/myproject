package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.mr.HisMrAssign;
import com.yb.hi.entity.mr.HisMrCatalog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.mr.HisMrAssignMapper;
import com.yb.hi.mapper.mr.HisMrCatalogMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病案分配服务: 将已出院待编目患者按"随机均分"或"偏好(指定编目员)"分配给编目员;
 * 分配时生成编目快照(his_mr_catalog), 记录 his_mr_assign; 支持释放/重分配与编目员剩余工作量查看。
 */
@Slf4j
@Service
public class MrAssignService {

    private final HisMrAssignMapper assignMapper;
    private final HisMrCatalogMapper catalogMapper;
    private final MrCatalogService catalogService;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrAssignService(HisMrAssignMapper assignMapper, HisMrCatalogMapper catalogMapper,
                           MrCatalogService catalogService, JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.assignMapper = assignMapper;
        this.catalogMapper = catalogMapper;
        this.catalogService = catalogService;
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /** 待分配池: 已出院(visit_status 3/4)且尚无编目记录(或待编目且未分配)的患者。 */
    public IPage<Map<String, Object>> pendingPage(long page, long size, String keyword, Long deptId) {
        Long tid = TenantContext.require();
        StringBuilder where = new StringBuilder(" WHERE v.deleted = 0 AND v.tenant_id = ? AND v.visit_status IN (3,4)");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND v.org_id = ?");
            args.add(scopeOrg);
        }
        if (deptId != null) {
            where.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (p.name LIKE ? OR v.inp_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        String base = " FROM his_inp_visit v"
                + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_dept d ON d.id = v.dept_id"
                + " LEFT JOIN his_mr_catalog c ON c.visit_id = v.id AND c.deleted = 0 AND c.tenant_id = v.tenant_id";
        // 仅未编目或待编目未分配
        where.append(" AND (c.id IS NULL OR (c.catalog_status = 1 AND c.cataloger_id IS NULL))");
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + base + where, Long.class, args.toArray());
        long p = Math.max(1, page);
        long s = size <= 0 ? 20 : Math.min(size, 200);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT v.id AS visitId, v.patient_id AS patientId, p.name AS patientName,"
                        + " p.gender_name AS genderName, p.age, v.inp_no AS inpNo, d.dept_name AS deptName,"
                        + " v.admit_date AS admissionDate, v.discharge_date AS dischargeDate,"
                        + " c.id AS catalogId, c.catalog_status AS catalogStatus"
                        + base + where + " ORDER BY v.discharge_date DESC, v.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setRecords(rows);
        result.setTotal(total == null ? 0 : total);
        return result;
    }

    /**
     * 分配: visits 按 mode(2随机均分/1偏好即全部给指定单人)分配给 catalogers。
     * 随机均分: 轮询均摊; 偏好: 全部给 catalogers[0]。每人剩余工作量在返回体回显。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> assign(List<Long> visitIds, int mode, List<Map<String, Object>> catalogers) {
        guard.requireSelfOrgWrite();
        if (visitIds == null || visitIds.isEmpty()) {
            throw new BizException(400, "请选择需分配的病案");
        }
        if (catalogers == null || catalogers.isEmpty()) {
            throw new BizException(400, "请选择可被分配的人员");
        }
        LoginUser lu = UserContext.get();
        String operator = lu == null ? null : lu.getRealName();
        int idx = 0;
        int done = 0;
        for (Long visitId : visitIds) {
            Map<String, Object> person = mode == 2 ? catalogers.get(idx % catalogers.size()) : catalogers.get(0);
            idx++;
            Long cid = prepareCatalog(visitId);
            HisMrCatalog c = catalogMapper.selectById(cid);
            c.setCatalogerId(MrCatalogService.longOf(person.get("catalogerId")));
            c.setCatalogerName(MrCatalogService.strOf(person.get("catalogerName")));
            catalogMapper.updateById(c);
            HisMrAssign a = new HisMrAssign();
            a.setCatalogId(cid);
            a.setVisitId(visitId);
            a.setAssignType(mode == 2 ? 2 : 1);
            a.setCatalogerId(c.getCatalogerId());
            a.setCatalogerName(c.getCatalogerName());
            a.setPriority(MrCatalogService.intOf(person.get("priority"), 0));
            a.setAssignStatus(1);
            a.setAssignBy(operator);
            a.setAssignTime(LocalDateTime.now());
            assignMapper.insert(a);
            catalogService.logChangePub(cid, visitId, "assign", "cataloger", null, c.getCatalogerName());
            done++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("assigned", done);
        r.put("workload", workload());
        return r;
    }

    /** 生成/复用编目快照并返回其 id。 */
    private Long prepareCatalog(Long visitId) {
        HisMrCatalog c = catalogService.generate(visitId);
        return c.getId();
    }

    /** 释放某病案当前有效分配(改状态+清空编目员), 支持重新分配。 */
    @Transactional(rollbackFor = Exception.class)
    public void release(Long visitId, String reason) {
        guard.requireSelfOrgWrite();
        HisMrCatalog c = catalogService.findByVisit(visitId);
        if (c == null) {
            throw new BizException(404, "病案编目不存在");
        }
        if (MrCatalogService.intOf(c.getCatalogStatus(), 1) >= 3) {
            throw new BizException(409, "已定稿病案不可释放, 请先撤销定稿");
        }
        HisMrAssign active = assignMapper.selectOne(new QueryWrapper<HisMrAssign>()
                .eq("visit_id", visitId).eq("assign_status", 1).orderByDesc("id").last("LIMIT 1"));
        if (active != null) {
            active.setAssignStatus(2);
            assignMapper.updateById(active);
        }
        String oldName = c.getCatalogerName();
        c.setCatalogerId(null);
        c.setCatalogerName(null);
        c.setCatalogStatus(1);
        catalogMapper.updateById(c);
        catalogService.logChangePub(c.getId(), visitId, "assign", "release", oldName, "已释放" + (StringUtils.hasText(reason) ? ":" + reason : ""));
    }

    /** 编目员剩余工作量(已分配且未定稿), 供分配页右栏展示。 */
    public List<Map<String, Object>> workload() {
        Long tid = TenantContext.require();
        Long scopeOrg = guard.scopeOrgId(null);
        StringBuilder sql = new StringBuilder(
                "SELECT c.cataloger_id AS catalogerId, c.cataloger_name AS catalogerName,"
                        + " SUM(CASE WHEN c.catalog_status < 3 THEN 1 ELSE 0 END) AS pending,"
                        + " SUM(CASE WHEN c.catalog_status = 3 THEN 1 ELSE 0 END) AS finished"
                        + " FROM his_mr_catalog c WHERE c.deleted = 0 AND c.tenant_id = ? AND c.cataloger_id IS NOT NULL");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        if (scopeOrg != null) {
            sql.append(" AND c.org_id = ?");
            args.add(scopeOrg);
        }
        sql.append(" GROUP BY c.cataloger_id, c.cataloger_name ORDER BY pending DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 编目员候选(his_staff, 机构隔离 + 关键字)。 */
    public List<Map<String, Object>> catalogers(String keyword) {
        Long tid = TenantContext.require();
        Long scopeOrg = guard.strictCurrentOrgId();
        StringBuilder sql = new StringBuilder(
                "SELECT s.id AS staffId, s.staff_name AS staffName, d.dept_name AS deptName"
                        + " FROM his_staff s LEFT JOIN his_dept d ON d.id = s.dept_id"
                        + " WHERE s.deleted = 0 AND s.tenant_id = ? AND s.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        args.add(scopeOrg);
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND s.staff_name LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY s.staff_name LIMIT 50");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("catalogerId", r.get("staffId"));
            m.put("catalogerName", r.get("staffName"));
            m.put("deptName", r.get("deptName"));
            out.add(m);
        }
        return out;
    }
}
