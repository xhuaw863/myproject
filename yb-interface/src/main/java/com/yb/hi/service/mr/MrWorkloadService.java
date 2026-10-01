package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.mr.HisMrWorkload;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.mr.HisMrWorkloadMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病案工作量统计服务(P2, 收尾 P1 遗留 E): 按统计期/科室/责任人登记门诊·住院病区·医技·其他项工作量, 并做逻辑审核(合理性复核)。
 * 铁律: 独立于编目/临床首页, 仅落 his_mr_workload; 写操作 requireSelfOrgWrite, 归属机构取当前登录机构。
 * audit_status: 1待审 2通过 3驳回。
 */
@Slf4j
@Service
public class MrWorkloadService {

    private final HisMrWorkloadMapper workloadMapper;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrWorkloadService(HisMrWorkloadMapper workloadMapper, JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.workloadMapper = workloadMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /** 工作量列表(分页, 统计期/科室/类别/审核状态/关键字过滤)。 */
    public IPage<HisMrWorkload> listPage(long page, long size, String period, Long deptId,
                                         String category, Integer auditStatus, String keyword) {
        QueryWrapper<HisMrWorkload> qw = new QueryWrapper<>();
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            qw.eq("org_id", scopeOrg);
        }
        if (StringUtils.hasText(period)) {
            qw.eq("period", period.trim());
        }
        if (deptId != null) {
            qw.eq("dept_id", deptId);
        }
        if (StringUtils.hasText(category)) {
            qw.eq("category", category.trim());
        }
        if (auditStatus != null) {
            qw.eq("audit_status", auditStatus);
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            qw.and(w -> w.like("item_name", like).or().like("staff_name", like).or().like("dept_name", like));
        }
        qw.orderByDesc("period").orderByAsc("dept_id", "category", "id");
        long p = Math.max(1, page);
        long s = size <= 0 ? 20 : Math.min(size, 200);
        return workloadMapper.selectPage(new Page<>(p, s), qw);
    }

    /** 新增工作量登记(逻辑审核初始为待审)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisMrWorkload create(Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        LoginUser lu = UserContext.get();
        HisMrWorkload w = new HisMrWorkload();
        applyBody(w, body);
        w.setOrgId(lu.getOrgId());
        w.setAuditStatus(1);
        if (w.getQty() == null) {
            w.setQty(BigDecimal.ZERO);
        }
        workloadMapper.insert(w);
        return w;
    }

    /** 编辑工作量(驳回后重录; 已通过需先由审核流程处理)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisMrWorkload update(Long id, Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        HisMrWorkload w = workloadMapper.selectById(id);
        if (w == null) {
            throw new BizException(400, "工作量记录不存在");
        }
        applyBody(w, body);
        workloadMapper.updateById(w);
        return w;
    }

    /** 删除(软删)。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        guard.requireSelfOrgWrite();
        HisMrWorkload w = workloadMapper.selectById(id);
        if (w == null) {
            throw new BizException(400, "工作量记录不存在");
        }
        workloadMapper.deleteById(id);
    }

    /**
     * 逻辑审核(单条): approved=true 通过(2), false 驳回(3)并记录意见; 仅对待审/已驳回记录可审(避免重复审核)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisMrWorkload audit(Long id, boolean approved, String opinion) {
        guard.requireSelfOrgWrite();
        LoginUser lu = UserContext.get();
        HisMrWorkload w = workloadMapper.selectById(id);
        if (w == null) {
            throw new BizException(400, "工作量记录不存在");
        }
        if (w.getAuditStatus() != null && w.getAuditStatus() == 2) {
            throw new BizException(409, "该记录已审核通过, 无需重复审核");
        }
        w.setAuditStatus(approved ? 2 : 3);
        w.setAuditUserName(lu.getRealName());
        w.setAuditTime(LocalDateTime.now());
        w.setAuditOpinion(trim(opinion));
        workloadMapper.updateById(w);
        return w;
    }

    /** 批量逻辑审核: body {ids:[...], approved, opinion} → 返回处理数。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchAudit(List<Long> ids, boolean approved, String opinion) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "请选择需审核的工作量记录");
        }
        int done = 0;
        int skipped = 0;
        for (Long id : ids) {
            HisMrWorkload w = id == null ? null : workloadMapper.selectById(id);
            if (w == null || (w.getAuditStatus() != null && w.getAuditStatus() == 2)) {
                skipped++;
                continue;
            }
            audit(id, approved, opinion);
            done++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("done", done);
        r.put("skipped", skipped);
        return r;
    }

    /** 逻辑审核概览: 指定统计期按科室+类别汇总数量/金额与待审数, 供合理性判断。 */
    public List<Map<String, Object>> summary(String period) {
        Long tid = com.yb.hi.framework.tenant.TenantContext.require();
        StringBuilder where = new StringBuilder(" WHERE deleted = 0 AND tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND org_id = ?");
            args.add(scopeOrg);
        }
        if (StringUtils.hasText(period)) {
            where.append(" AND period = ?");
            args.add(period.trim());
        }
        return jdbcTemplate.queryForList(
                "SELECT period, dept_id AS deptId, dept_name AS deptName, category,"
                        + " SUM(qty) AS totalQty, SUM(amount) AS totalAmount,"
                        + " SUM(CASE WHEN audit_status = 1 THEN 1 ELSE 0 END) AS pendingCount,"
                        + " SUM(CASE WHEN audit_status = 2 THEN 1 ELSE 0 END) AS passedCount,"
                        + " SUM(CASE WHEN audit_status = 3 THEN 1 ELSE 0 END) AS rejectedCount"
                        + " FROM his_mr_workload" + where
                        + " GROUP BY period, dept_id, dept_name, category"
                        + " ORDER BY period DESC, dept_id, category",
                args.toArray());
    }

    private void applyBody(HisMrWorkload w, Map<String, Object> body) {
        if (body == null) {
            return;
        }
        if (body.containsKey("period")) {
            w.setPeriod(trim(str(body.get("period"))));
        }
        if (body.containsKey("deptId")) {
            Long deptId = MrCatalogService.longOf(body.get("deptId"));
            w.setDeptId(deptId);
            if (deptId != null) {
                String dn = resolveDeptName(deptId);
                if (dn != null) {
                    w.setDeptName(dn);
                }
            }
        }
        if (body.containsKey("deptName") && StringUtils.hasText(str(body.get("deptName")))) {
            w.setDeptName(trim(str(body.get("deptName"))));
        }
        if (body.containsKey("staffId")) {
            w.setStaffId(MrCatalogService.longOf(body.get("staffId")));
        }
        if (body.containsKey("staffName")) {
            w.setStaffName(trim(str(body.get("staffName"))));
        }
        if (body.containsKey("category")) {
            w.setCategory(trim(str(body.get("category"))));
        }
        if (body.containsKey("itemCode")) {
            w.setItemCode(trim(str(body.get("itemCode"))));
        }
        if (body.containsKey("itemName")) {
            w.setItemName(trim(str(body.get("itemName"))));
        }
        if (body.containsKey("qty")) {
            w.setQty(toBig(body.get("qty")));
        }
        if (body.containsKey("amount")) {
            w.setAmount(toBig(body.get("amount")));
        }
        if (body.containsKey("remark")) {
            w.setRemark(trim(str(body.get("remark"))));
        }
        if (StringUtils.hasText(w.getPeriod()) == false) {
            throw new BizException(400, "统计期(yyyy-MM)必填");
        }
        if (!StringUtils.hasText(w.getCategory())) {
            throw new BizException(400, "工作量类别必填");
        }
    }

    private String resolveDeptName(Long deptId) {
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT dept_name FROM his_dept WHERE id = ? AND deleted = 0", String.class, deptId);
            return names.isEmpty() ? null : names.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static BigDecimal toBig(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return new BigDecimal(String.valueOf(o));
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
