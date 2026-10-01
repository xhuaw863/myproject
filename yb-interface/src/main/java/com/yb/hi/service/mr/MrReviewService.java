package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.mr.HisMrCatalog;
import com.yb.hi.entity.mr.HisMrQualityErr;
import com.yb.hi.entity.mr.HisMrReview;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.mr.HisMrCatalogMapper;
import com.yb.hi.mapper.mr.HisMrQualityErrMapper;
import com.yb.hi.mapper.mr.HisMrReviewMapper;
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
import java.util.UUID;

/**
 * 首页质量审核与确认锁定服务(病案室质控侧):
 * 批量审核→落 his_mr_quality_err + 错误归类汇总; 二级审核确认→锁定; 解锁需录原因并退回已审核态。
 */
@Slf4j
@Service
public class MrReviewService {

    private final HisMrCatalogMapper catalogMapper;
    private final HisMrQualityErrMapper errMapper;
    private final HisMrReviewMapper reviewMapper;
    private final MrQualityService qualityService;
    private final MrCatalogService catalogService;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrReviewService(HisMrCatalogMapper catalogMapper, HisMrQualityErrMapper errMapper,
                           HisMrReviewMapper reviewMapper, MrQualityService qualityService,
                           MrCatalogService catalogService, JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.catalogMapper = catalogMapper;
        this.errMapper = errMapper;
        this.reviewMapper = reviewMapper;
        this.qualityService = qualityService;
        this.catalogService = catalogService;
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /** 审核队列分页(已编目/已审核), 附未解决错误数。 */
    public IPage<Map<String, Object>> queuePage(long page, long size, Integer auditStatus, Integer catalogStatus) {
        Long tid = TenantContext.require();
        StringBuilder where = new StringBuilder(" WHERE c.deleted = 0 AND c.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND c.org_id = ?");
            args.add(scopeOrg);
        }
        if (auditStatus != null) {
            where.append(" AND c.audit_status = ?");
            args.add(auditStatus);
        }
        if (catalogStatus != null) {
            where.append(" AND c.catalog_status = ?");
            args.add(catalogStatus);
        }
        String base = " FROM his_mr_catalog c"
                + " LEFT JOIN his_patient p ON p.id = c.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_dept dd ON dd.id = c.discharge_dept_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + base + where, Long.class, args.toArray());
        long p = Math.max(1, page);
        long s = size <= 0 ? 20 : Math.min(size, 200);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.id, c.visit_id AS visitId, c.catalog_no AS catalogNo, p.name AS patientName,"
                        + " dd.dept_name AS dischargeDeptName, c.main_diag_name AS mainDiagName,"
                        + " c.catalog_status AS catalogStatus, c.audit_status AS auditStatus, c.lock_status AS lockStatus,"
                        + " c.cataloger_name AS catalogerName, c.quality_score AS qualityScore,"
                        + " (SELECT COUNT(*) FROM his_mr_quality_err e WHERE e.catalog_id = c.id AND e.resolved = 0 AND e.deleted = 0) AS errCount"
                        + base + where + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setRecords(rows);
        result.setTotal(total == null ? 0 : total);
        return result;
    }

    /** 批量审核: 对选中医案逐份跑质控规则, 重落错误项, 返回汇总(患者数/错误数/按类别/按科室/按规则)。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchAudit(List<Long> visitIds) {
        guard.requireSelfOrgWrite();
        if (visitIds == null || visitIds.isEmpty()) {
            throw new BizException(400, "请选择需审核的病案");
        }
        String batchNo = "MRB" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        int forceCnt = 0;
        int softCnt = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (Long visitId : visitIds) {
            HisMrCatalog c = catalogService.findByVisit(visitId);
            if (c == null) {
                continue;
            }
            // 重跑: 先清旧错误项(物理删除本 catalog 的行, 逻辑删)
            errMapper.delete(new QueryWrapper<HisMrQualityErr>().eq("catalog_id", c.getId()));
            List<Map<String, Object>> errs = qualityService.validate(c);
            int catForce = 0;
            for (Map<String, Object> e : errs) {
                HisMrQualityErr row = new HisMrQualityErr();
                row.setCatalogId(c.getId());
                row.setVisitId(visitId);
                row.setBatchNo(batchNo);
                row.setRuleCategory(MrCatalogService.strOf(e.get("ruleCategory")));
                row.setRuleCode(MrCatalogService.strOf(e.get("ruleCode")));
                row.setFieldKey(MrCatalogService.strOf(e.get("fieldKey")));
                row.setErrorMsg(MrCatalogService.strOf(e.get("errorMsg")));
                row.setResolved(0);
                errMapper.insert(row);
                if ("强制".equals(e.get("ruleCategory"))) {
                    forceCnt++;
                    catForce++;
                } else {
                    softCnt++;
                }
            }
            // 无强制错误 且 已定稿 且 当前未审核→置已审核(2); 有强制错误→保持未审核(1)
            if (catForce == 0 && Integer.valueOf(1).equals(c.getAuditStatus())
                    && MrCatalogService.intOf(c.getCatalogStatus(), 1) >= 3) {
                c.setAuditStatus(2);
                catalogMapper.updateById(c);
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("visitId", visitId);
            d.put("catalogId", c.getId());
            d.put("patientName", patientName(c.getPatientId()));
            d.put("errorCount", errs.size());
            details.add(d);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("batchNo", batchNo);
        r.put("patientCount", details.size());
        r.put("totalErrors", forceCnt + softCnt);
        r.put("forceErrors", forceCnt);
        r.put("softErrors", softCnt);
        r.put("byCategory", groupCount("SELECT rule_category AS k, COUNT(*) AS c FROM his_mr_quality_err WHERE batch_no = ? AND deleted = 0 GROUP BY rule_category", batchNo));
        r.put("byRule", groupCount("SELECT rule_code AS k, COUNT(*) AS c FROM his_mr_quality_err WHERE batch_no = ? AND deleted = 0 GROUP BY rule_code ORDER BY c DESC", batchNo));
        r.put("details", details);
        return r;
    }

    /** 单份错误项(审核页右栏/定位)。 */
    public List<HisMrQualityErr> errorsOf(Long visitId) {
        HisMrCatalog c = catalogService.findByVisit(visitId);
        if (c == null) {
            return new ArrayList<>();
        }
        return errMapper.selectList(new QueryWrapper<HisMrQualityErr>()
                .eq("catalog_id", c.getId()).orderByAsc("rule_category").orderByAsc("id"));
    }

    /** 审核确认: 无未解决强制错误方可确认(audit_status=3)。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long visitId, String opinion) {
        guard.requireSelfOrgWrite();
        HisMrCatalog c = requireCatalog(visitId);
        Long forceOpen = errMapper.selectCount(new QueryWrapper<HisMrQualityErr>()
                .eq("catalog_id", c.getId()).eq("rule_category", "强制").eq("resolved", 0));
        if (forceOpen != null && forceOpen > 0) {
            throw new BizException(409, "存在未修复的强制类错误, 不可确认");
        }
        c.setAuditStatus(3);
        catalogMapper.updateById(c);
        HisMrReview rv = upsertReview(c);
        rv.setConfirmTime(LocalDateTime.now());
        rv.setAuditOpinion(opinion);
        LoginUser lu = UserContext.get();
        if (lu != null) {
            rv.setReviewerId(lu.getUserId());
            rv.setReviewerName(lu.getRealName());
        }
        reviewMapper.updateById(rv);
        catalogService.logChangePub(c.getId(), visitId, "confirm", "auditStatus", "2", "3");
    }

    /** 锁定: 已确认病案锁定, 锁定后禁改。 */
    @Transactional(rollbackFor = Exception.class)
    public void lock(Long visitId) {
        guard.requireSelfOrgWrite();
        HisMrCatalog c = requireCatalog(visitId);
        if (MrCatalogService.intOf(c.getAuditStatus(), 1) < 3) {
            throw new BizException(409, "仅已确认病案可锁定");
        }
        c.setLockStatus(1);
        catalogMapper.updateById(c);
        HisMrReview rv = upsertReview(c);
        rv.setLockStatus(1);
        rv.setLockTime(LocalDateTime.now());
        reviewMapper.updateById(rv);
        catalogService.logChangePub(c.getId(), visitId, "lock", "lockStatus", "0", "1");
    }

    /** 解锁: 必须录原因, 解锁后 audit_status 退回已审核(2)允许修改。 */
    @Transactional(rollbackFor = Exception.class)
    public void unlock(Long visitId, String reason) {
        guard.requireSelfOrgWrite();
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "解锁必须填写原因");
        }
        HisMrCatalog c = requireCatalog(visitId);
        if (MrCatalogService.intOf(c.getLockStatus(), 0) != 1) {
            throw new BizException(409, "病案未锁定");
        }
        c.setLockStatus(0);
        c.setAuditStatus(2);
        catalogMapper.updateById(c);
        HisMrReview rv = upsertReview(c);
        rv.setLockStatus(0);
        rv.setUnlockReason(reason);
        reviewMapper.updateById(rv);
        catalogService.logChangePub(c.getId(), visitId, "unlock", "lockStatus", "1", reason);
    }

    private HisMrReview upsertReview(HisMrCatalog c) {
        HisMrReview rv = reviewMapper.selectOne(new QueryWrapper<HisMrReview>()
                .eq("catalog_id", c.getId()).orderByDesc("id").last("LIMIT 1"));
        if (rv == null) {
            rv = new HisMrReview();
            rv.setCatalogId(c.getId());
            rv.setVisitId(c.getVisitId());
            rv.setLockStatus(0);
            reviewMapper.insert(rv);
        }
        return rv;
    }

    private HisMrCatalog requireCatalog(Long visitId) {
        HisMrCatalog c = catalogService.findByVisit(visitId);
        if (c == null) {
            throw new BizException(404, "病案编目不存在");
        }
        return c;
    }

    private String patientName(Long patientId) {
        if (patientId == null) {
            return null;
        }
        List<Map<String, Object>> r = jdbcTemplate.queryForList(
                "SELECT name FROM his_patient WHERE id = ? AND deleted = 0", patientId);
        return r.isEmpty() ? null : MrCatalogService.strOf(r.get(0).get("name"));
    }

    private List<Map<String, Object>> groupCount(String sql, Object arg) {
        List<Map<String, Object>> raw = jdbcTemplate.queryForList(sql, arg);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("key", m.get("k"));
            o.put("count", m.get("c"));
            out.add(o);
        }
        return out;
    }
}
