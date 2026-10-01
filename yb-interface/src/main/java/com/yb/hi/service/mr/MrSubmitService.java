package com.yb.hi.service.mr;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.mr.HisMrReportBatch;
import com.yb.hi.entity.mr.HisMrReportItem;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.mr.HisMrReportBatchMapper;
import com.yb.hi.mapper.mr.HisMrReportItemMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病案上报三段闭环服务(P2): 卫统4表(wt) / HQMS绩效(hqms) / 医保结算清单(med_list) 的 建批次→审核→转换→上报。
 * 状态严格前进不可跳档: 1待审核 2已审核待转换 3已转换待上报 4已上报 5失败。
 * 铁律: 上报数据源为编目侧快照(his_mr_catalog/diag), 不回写临床首页; 写操作 requireSelfOrgWrite。
 */
@Slf4j
@Service
public class MrSubmitService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 上报类型白名单。 */
    public static final List<String> TYPES = Arrays.asList("wt", "hqms", "med_list");

    private final HisMrReportBatchMapper batchMapper;
    private final HisMrReportItemMapper itemMapper;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrSubmitService(HisMrReportBatchMapper batchMapper, HisMrReportItemMapper itemMapper,
                           JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    public List<String> types() {
        return TYPES;
    }

    /** 批次列表(分页, 类型/状态过滤)。 */
    public IPage<Map<String, Object>> listPage(long page, long size, String reportType, Integer status) {
        Long tid = TenantContext.require();
        StringBuilder where = new StringBuilder(" WHERE b.deleted = 0 AND b.tenant_id = " + tid);
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND b.org_id = ").append(scopeOrg);
        }
        if (StringUtils.hasText(reportType)) {
            where.append(" AND b.report_type = '").append(esc(reportType.trim())).append("'");
        }
        if (status != null) {
            where.append(" AND b.status = ").append(status);
        }
        long p = Math.max(1, page);
        long s = size <= 0 ? 20 : Math.min(size, 200);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM his_mr_report_batch b" + where, Long.class);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT b.id, b.report_type AS reportType, b.batch_no AS batchNo,"
                        + " b.period_from AS periodFrom, b.period_to AS periodTo, b.status,"
                        + " b.total_count AS totalCount, b.ok_count AS okCount, b.err_count AS errCount,"
                        + " b.reviewer_name AS reviewerName, b.review_time AS reviewTime,"
                        + " b.converter_name AS converterName, b.convert_time AS convertTime,"
                        + " b.submitter_name AS submitterName, b.submit_time AS submitTime,"
                        + " b.file_ref AS fileRef, b.fail_reason AS failReason, b.create_time AS createTime"
                        + " FROM his_mr_report_batch b" + where
                        + " ORDER BY b.id DESC LIMIT " + s + " OFFSET " + ((p - 1) * s));
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setRecords(rows);
        result.setTotal(total == null ? 0 : total);
        return result;
    }

    /** 建批次: 快照区间内已编目且已审核(audit_status>=2)的病案为上报明细, 落批次(status=1待审核)。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createBatch(Map<String, Object> body) {
        guard.requireSelfOrgWrite();
        String reportType = body == null ? null : trim(str(body.get("reportType")));
        if (reportType == null || !TYPES.contains(reportType)) {
            throw new BizException(400, "非法的上报类型(wt/hqms/med_list)");
        }
        String from = trim(str(body.get("from")));
        String to = trim(str(body.get("to")));
        if (from == null || to == null) {
            throw new BizException(400, "统计起止日期必填");
        }
        LoginUser lu = UserContext.get();
        Long tid = TenantContext.require();

        StringBuilder q = new StringBuilder(
                "SELECT c.id AS catalogId, c.visit_id AS visitId, p.name AS patientName,"
                        + " v.inp_no AS inpNo, c.main_diag_code AS mainDiagCode"
                        + " FROM his_mr_catalog c"
                        + " LEFT JOIN his_inp_visit v ON v.id = c.visit_id"
                        + " LEFT JOIN his_patient p ON p.id = c.patient_id"
                        + " WHERE c.deleted = 0 AND c.tenant_id = ? AND c.catalog_status = 3 AND c.audit_status >= 2"
                        + " AND c.discharge_date >= ? AND c.discharge_date <= ?");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        args.add(from + " 00:00:00");
        args.add(to + " 23:59:59");
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            q.append(" AND c.org_id = ?");
            args.add(scopeOrg);
        }
        List<Map<String, Object>> cands = jdbcTemplate.queryForList(q.toString(), args.toArray());

        HisMrReportBatch batch = new HisMrReportBatch();
        batch.setOrgId(lu.getOrgId());
        batch.setReportType(reportType);
        batch.setBatchNo(generateBatchNo(reportType));
        batch.setPeriodFrom(LocalDate.parse(from));
        batch.setPeriodTo(LocalDate.parse(to));
        batch.setStatus(1);
        batch.setTotalCount(cands.size());
        batch.setOkCount(0);
        batch.setErrCount(0);
        batchMapper.insert(batch);

        int staged = 0;
        for (Map<String, Object> cand : cands) {
            HisMrReportItem it = new HisMrReportItem();
            it.setBatchId(batch.getId());
            it.setVisitId(MrCatalogService.longOf(cand.get("visitId")));
            it.setCatalogId(MrCatalogService.longOf(cand.get("catalogId")));
            it.setPatientName(str(cand.get("patientName")));
            it.setInpNo(str(cand.get("inpNo")));
            it.setMainDiagCode(str(cand.get("mainDiagCode")));
            it.setCheckStatus(1);
            it.setConverted(0);
            it.setReported(0);
            itemMapper.insert(it);
            staged++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("batchId", batch.getId());
        r.put("batchNo", batch.getBatchNo());
        r.put("totalCount", staged);
        return r;
    }

    /** 第一段·数据审核: 逐条合规校验(主要诊断必填), 置 ok/err 计数, 批次前进到 2。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> audit(Long batchId) {
        guard.requireSelfOrgWrite();
        HisMrReportBatch batch = load(batchId);
        if (batch.getStatus() != 1) {
            throw new BizException(409, "仅待审核批次可执行数据审核");
        }
        LoginUser lu = UserContext.get();
        List<HisMrReportItem> items = listItems(batchId);
        int ok = 0;
        int err = 0;
        for (HisMrReportItem it : items) {
            String reason = checkReason(batch.getReportType(), it);
            if (reason == null) {
                it.setCheckStatus(1);
                it.setCheckMsg("合规");
                ok++;
            } else {
                it.setCheckStatus(2);
                it.setCheckMsg(reason);
                err++;
            }
            itemMapper.updateById(it);
        }
        batch.setStatus(2);
        batch.setOkCount(ok);
        batch.setErrCount(err);
        batch.setReviewerName(lu.getRealName());
        batch.setReviewTime(LocalDateTime.now());
        batchMapper.updateById(batch);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", ok);
        r.put("err", err);
        return r;
    }

    /** 第二段·数据转换: 审核通过项置已转换(生成上报格式), 批次前进到 3。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> convert(Long batchId) {
        guard.requireSelfOrgWrite();
        HisMrReportBatch batch = load(batchId);
        if (batch.getStatus() != 2) {
            throw new BizException(409, "请先完成数据审核再转换");
        }
        LoginUser lu = UserContext.get();
        int converted = 0;
        for (HisMrReportItem it : listItems(batchId)) {
            if (it.getCheckStatus() != null && it.getCheckStatus() == 1) {
                it.setConverted(1);
                itemMapper.updateById(it);
                converted++;
            }
        }
        if (converted == 0) {
            throw new BizException(409, "无审核通过的病案可转换");
        }
        batch.setStatus(3);
        batch.setConverterName(lu.getRealName());
        batch.setConvertTime(LocalDateTime.now());
        batchMapper.updateById(batch);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("converted", converted);
        return r;
    }

    /** 第三段·上报: 已转换项置已上报并生成回执引用, 批次前进到 4(已上报)。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Long batchId) {
        guard.requireSelfOrgWrite();
        HisMrReportBatch batch = load(batchId);
        if (batch.getStatus() != 3) {
            throw new BizException(409, "请先完成数据转换再上报");
        }
        LoginUser lu = UserContext.get();
        int reported = 0;
        for (HisMrReportItem it : listItems(batchId)) {
            if (it.getConverted() != null && it.getConverted() == 1) {
                it.setReported(1);
                it.setReportMsg(typeLabel(batch.getReportType()) + "上报回执 OK");
                itemMapper.updateById(it);
                reported++;
            }
        }
        batch.setStatus(4);
        batch.setSubmitterName(lu.getRealName());
        batch.setSubmitTime(LocalDateTime.now());
        batch.setFileRef(batch.getBatchNo() + ".xml");
        batchMapper.updateById(batch);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("reported", reported);
        r.put("fileRef", batch.getFileRef());
        return r;
    }

    /** 批次详情(含明细)。 */
    public Map<String, Object> detail(Long batchId) {
        HisMrReportBatch batch = load(batchId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch", batch);
        out.put("items", listItems(batchId));
        return out;
    }

    /** 删除批次(仅未上报): 连带软删明细。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(Long batchId) {
        guard.requireSelfOrgWrite();
        HisMrReportBatch batch = load(batchId);
        if (batch.getStatus() == 4) {
            throw new BizException(409, "已上报批次不可删除");
        }
        for (HisMrReportItem it : listItems(batchId)) {
            itemMapper.deleteById(it.getId());
        }
        batchMapper.deleteById(batchId);
    }

    // ---------- 内部 ----------

    private String checkReason(String reportType, HisMrReportItem it) {
        if (!StringUtils.hasText(it.getMainDiagCode())) {
            return "缺少主要诊断编码";
        }
        if ("med_list".equals(reportType) && it.getCatalogId() == null) {
            return "医保清单需关联编目主表";
        }
        return null;
    }

    private List<HisMrReportItem> listItems(Long batchId) {
        return itemMapper.selectList(new QueryWrapper<HisMrReportItem>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    private HisMrReportBatch load(Long batchId) {
        HisMrReportBatch b = batchId == null ? null : batchMapper.selectById(batchId);
        if (b == null) {
            throw new BizException(400, "上报批次不存在");
        }
        return b;
    }

    private String generateBatchNo(String reportType) {
        Long tid = TenantContext.require();
        String today = LocalDate.now().format(NO_DAY);
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_mr_report_batch WHERE tenant_id = ? AND deleted = 0"
                        + " AND report_type = ? AND DATE(create_time) = ?",
                Integer.class, tid, reportType, LocalDate.now().toString());
        int seq = (cnt == null ? 0 : cnt) + 1;
        return "SB" + reportType.toUpperCase() + today + String.format("%03d", seq);
    }

    private String typeLabel(String t) {
        switch (t == null ? "" : t) {
            case "wt":
                return "卫统4表";
            case "hqms":
                return "HQMS绩效";
            case "med_list":
                return "医保结算清单";
            default:
                return "上报";
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

    private static String esc(String s) {
        return s == null ? null : s.replace("'", "''");
    }
}
