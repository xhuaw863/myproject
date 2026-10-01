package com.yb.hi.service.mr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.mr.HisMrCatalog;
import com.yb.hi.entity.mr.HisMrChangeLog;
import com.yb.hi.entity.mr.HisMrDiag;
import com.yb.hi.entity.mr.HisMrOper;
import com.yb.hi.entity.mr.HisMrOther;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.mr.HisMrCatalogMapper;
import com.yb.hi.mapper.mr.HisMrChangeLogMapper;
import com.yb.hi.mapper.mr.HisMrDiagMapper;
import com.yb.hi.mapper.mr.HisMrOperMapper;
import com.yb.hi.mapper.mr.HisMrOtherMapper;
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
 * 病案编目服务(病案统计科侧): 读取临床端首页/住院诊断/手术生成"编目态快照"(不回写 his_case_front_page),
 * 编目员在快照上修订诊断/手术多条明细并对照医保版, 经质控校验后定稿。
 * 状态机: catalog_status 1待编目→2编目中→3已编目; audit_status 1未审核→2已审核→3已确认; lock_status 锁定禁改。
 * JdbcTemplate 手写 SQL 显式带 tenant_id AND deleted=0; 明细/主表/留痕经 MP Mapper(租户插件自动注入)。
 */
@Slf4j
@Service
public class MrCatalogService {

    private final HisMrCatalogMapper catalogMapper;
    private final HisMrDiagMapper diagMapper;
    private final HisMrOperMapper operMapper;
    private final HisMrOtherMapper otherMapper;
    private final HisMrChangeLogMapper logMapper;
    private final MrQualityService qualityService;
    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public MrCatalogService(HisMrCatalogMapper catalogMapper, HisMrDiagMapper diagMapper,
                            HisMrOperMapper operMapper, HisMrOtherMapper otherMapper,
                            HisMrChangeLogMapper logMapper, MrQualityService qualityService,
                            JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.catalogMapper = catalogMapper;
        this.diagMapper = diagMapper;
        this.operMapper = operMapper;
        this.otherMapper = otherMapper;
        this.logMapper = logMapper;
        this.qualityService = qualityService;
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /* ==================== 列表查询 ==================== */

    /** 编目分页列表: status 编目状态过滤(可空), catalogerId 我的待办(可空), keyword 患者姓名/住院号模糊。 */
    public IPage<Map<String, Object>> listPage(long page, long size, Integer catalogStatus,
                                               Long catalogerId, String keyword, Long deptId) {
        Long tid = TenantContext.require();
        StringBuilder where = new StringBuilder(" WHERE c.deleted = 0 AND c.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tid);
        if (catalogStatus != null) {
            where.append(" AND c.catalog_status = ?");
            args.add(catalogStatus);
        }
        if (catalogerId != null) {
            where.append(" AND c.cataloger_id = ?");
            args.add(catalogerId);
        }
        if (deptId != null) {
            where.append(" AND c.discharge_dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (p.name LIKE ? OR v.inp_no LIKE ? OR c.catalog_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long scopeOrg = guard.scopeOrgId(null);
        if (scopeOrg != null) {
            where.append(" AND c.org_id = ?");
            args.add(scopeOrg);
        }
        String base = " FROM his_mr_catalog c"
                + " LEFT JOIN his_patient p ON p.id = c.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_inp_visit v ON v.id = c.visit_id"
                + " LEFT JOIN his_dept dd ON dd.id = c.discharge_dept_id";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + base + where, Long.class, args.toArray());
        long p = Math.max(1, page);
        long s = size <= 0 ? 20 : Math.min(size, 200);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.id, c.visit_id AS visitId, c.patient_id AS patientId, c.catalog_no AS catalogNo,"
                        + " p.name AS patientName, p.gender_name AS genderName, p.age,"
                        + " v.inp_no AS inpNo, dd.dept_name AS dischargeDeptName,"
                        + " c.admission_date AS admissionDate, c.discharge_date AS dischargeDate, c.los_days AS losDays,"
                        + " c.main_diag_code AS mainDiagCode, c.main_diag_name AS mainDiagName,"
                        + " c.catalog_status AS catalogStatus, c.audit_status AS auditStatus, c.lock_status AS lockStatus,"
                        + " c.cataloger_name AS catalogerName, c.quality_score AS qualityScore"
                        + base + where + " ORDER BY c.discharge_date DESC, c.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setRecords(rows);
        result.setTotal(total == null ? 0 : total);
        return result;
    }

    /* ==================== 生成快照 ==================== */

    /** 从临床端首页/诊断/手术生成或重建编目快照(幂等)。已定稿(3)/已锁定的病案不覆盖。 */
    @Transactional(rollbackFor = Exception.class)
    public HisMrCatalog generate(Long visitId) {
        Long tid = TenantContext.require();
        HisMrCatalog exist = catalogMapper.selectOne(new QueryWrapper<HisMrCatalog>()
                .eq("visit_id", visitId).last("LIMIT 1"));
        if (exist != null && (intOf(exist.getCatalogStatus(), 1) >= 3 || intOf(exist.getLockStatus(), 0) == 1)) {
            return exist;
        }
        Map<String, Object> ctx = jdbcTemplate.queryForList(
                "SELECT v.org_id AS orgId, v.patient_id AS patientId, v.inp_no AS inpNo,"
                        + " v.admit_date AS admissionDate, v.discharge_date AS dischargeDate,"
                        + " v.visit_status AS visitStatus, v.dept_id AS deptId"
                        + " FROM his_inp_visit v WHERE v.id = ? AND v.tenant_id = ? AND v.deleted = 0",
                visitId, tid).stream().findFirst().orElse(null);
        if (ctx == null) {
            throw new BizException(404, "就诊记录不存在");
        }
        int vs = intOf(ctx.get("visitStatus"), 0);
        if (vs != 3 && vs != 4) {
            throw new BizException(400, "仅出院办理中或已出院的患者可生成病案编目");
        }

        HisMrCatalog c = exist != null ? exist : new HisMrCatalog();
        c.setVisitId(visitId);
        c.setPatientId(longOf(ctx.get("patientId")));
        c.setOrgId(longOf(ctx.get("orgId")));
        c.setCatalogNo(strOf(ctx.get("inpNo")));
        c.setAdmissionDate(toLdt(ctx.get("admissionDate")));
        c.setDischargeDate(toLdt(ctx.get("dischargeDate")));
        if (c.getAdmissionDate() != null && c.getDischargeDate() != null) {
            c.setLosDays((int) Math.max(0, java.time.Duration.between(c.getAdmissionDate(), c.getDischargeDate()).toDays()));
        }
        c.setDischargeDeptId(longOf(ctx.get("deptId")));
        // 主诊断: 出院诊断(diag_type=4) is_main 优先
        Map<String, Object> md = jdbcTemplate.queryForList(
                "SELECT diag_code AS code, diag_name AS name FROM his_inp_diagnosis"
                        + " WHERE inp_visit_id = ? AND diag_type = 4 AND tenant_id = ? AND deleted = 0"
                        + " ORDER BY is_main DESC, sort_no ASC, id ASC LIMIT 1", visitId, tid)
                .stream().findFirst().orElse(null);
        if (md != null) {
            c.setMainDiagCode(strOf(md.get("code")));
            c.setMainDiagName(strOf(md.get("name")));
        }
        // 首页快照(基础/费用字段)从临床首页复制, 便于编目页右栏展示
        c.setSummary(buildSummary(visitId, tid));
        if (exist == null) {
            c.setCatalogStatus(1);
            c.setAuditStatus(1);
            c.setLockStatus(0);
            catalogMapper.insert(c);
            // 首次生成: 落诊断/手术明细
            rebuildDiagOper(visitId, tid, c.getId(), ctx);
            logChange(c.getId(), visitId, "catalog", "generate", null, "从临床首页生成编目快照");
        } else {
            catalogMapper.updateById(c);
            rebuildDiagOper(visitId, tid, c.getId(), ctx);
        }
        return c;
    }

    private void rebuildDiagOper(Long visitId, Long tid, Long catalogId, Map<String, Object> ctx) {
        diagMapper.delete(new QueryWrapper<HisMrDiag>().eq("catalog_id", catalogId));
        operMapper.delete(new QueryWrapper<HisMrOper>().eq("catalog_id", catalogId));
        // 诊断: 入院(1)->adm; 出院(4) 主->dmain, 其余->dother
        int sort = 0;
        for (Map<String, Object> d : jdbcTemplate.queryForList(
                "SELECT diag_code AS code, diag_name AS name, diag_type AS dtype, is_main AS isMain"
                        + " FROM his_inp_diagnosis WHERE inp_visit_id = ? AND tenant_id = ? AND deleted = 0"
                        + " ORDER BY diag_type ASC, is_main DESC, sort_no ASC, id ASC", visitId, tid)) {
            int dtype = intOf(d.get("dtype"), 0);
            String type;
            int mainFlag = 0;
            if (dtype == 1) {
                type = "adm";
            } else if (dtype == 4) {
                boolean isMain = intOf(d.get("isMain"), 0) == 1;
                type = isMain ? "dmain" : "dother";
                mainFlag = isMain ? 1 : 0;
            } else {
                type = "dother";
            }
            HisMrDiag dg = new HisMrDiag();
            dg.setCatalogId(catalogId);
            dg.setVisitId(visitId);
            dg.setDiagType(type);
            dg.setClinicalCode(strOf(d.get("code")));
            dg.setClinicalName(strOf(d.get("name")));
            dg.setMainFlag(mainFlag);
            dg.setReportFlag(0);
            dg.setGrayFlag(0);
            dg.setSortNo(sort++);
            diagMapper.insert(dg);
        }
        // 手术: his_surgery
        int osort = 0;
        for (Map<String, Object> s : jdbcTemplate.queryForList(
                "SELECT s.surgery_code AS code, s.surgery_name AS name, s.schedule_date AS opdate,"
                        + " st.staff_name AS surgeon FROM his_surgery s"
                        + " LEFT JOIN his_staff st ON st.id = s.surgeon_id AND st.deleted = 0"
                        + " WHERE s.inp_visit_id = ? AND s.tenant_id = ? AND s.deleted = 0"
                        + " ORDER BY s.schedule_date ASC, s.id ASC", visitId, tid)) {
            HisMrOper op = new HisMrOper();
            op.setCatalogId(catalogId);
            op.setVisitId(visitId);
            op.setClinicalCode(strOf(s.get("code")));
            op.setClinicalName(strOf(s.get("name")));
            op.setOperDate(toLdt(s.get("opdate")));
            op.setSurgeonName(strOf(s.get("surgeon")));
            op.setMainFlag(osort == 0 ? 1 : 0);
            op.setReportFlag(0);
            op.setGrayFlag(0);
            op.setSortNo(osort++);
            operMapper.insert(op);
        }
    }

    private String buildSummary(Long visitId, Long tid) {
        List<Map<String, Object>> fps = jdbcTemplate.queryForList(
                "SELECT admission_dept_id AS admDept, pathology_diag AS pathology, injury_poison_code AS injury,"
                        + " allergy_drugs AS allergy, total_cost AS totalCost, drug_cost AS drugCost,"
                        + " exam_cost AS examCost, treatment_cost AS treatmentCost, bed_cost AS bedCost,"
                        + " nursing_cost AS nursingCost, material_cost AS materialCost, self_pay AS selfPay,"
                        + " insurance_pay AS insurancePay FROM his_case_front_page"
                        + " WHERE visit_id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1", visitId, tid);
        JSONObject o = new JSONObject();
        if (!fps.isEmpty()) {
            o.putAll(fps.get(0));
        }
        return o.toJSONString();
    }

    /* ==================== 明细查询 ==================== */

    /** 病案首页编目详情: 主表 + 诊断/手术/扩展明细 + 实时校验错误。visitId 或 catalogId 二者其一。 */
    public Map<String, Object> detail(Long visitId) {
        Long tid = TenantContext.require();
        HisMrCatalog c = catalogMapper.selectOne(new QueryWrapper<HisMrCatalog>()
                .eq("visit_id", visitId).last("LIMIT 1"));
        if (c == null) {
            throw new BizException(404, "病案编目尚未生成, 请先分配或生成");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("catalog", c);
        r.put("diags", diagMapper.selectList(new QueryWrapper<HisMrDiag>()
                .eq("catalog_id", c.getId()).orderByAsc("diag_type").orderByAsc("sort_no").orderByAsc("id")));
        r.put("opers", operMapper.selectList(new QueryWrapper<HisMrOper>()
                .eq("catalog_id", c.getId()).orderByAsc("sort_no").orderByAsc("id")));
        r.put("others", otherMapper.selectList(new QueryWrapper<HisMrOther>()
                .eq("catalog_id", c.getId()).orderByAsc("rec_type").orderByAsc("sort_no")));
        r.put("errors", qualityService.validate(c));
        r.put("logs", logMapper.selectList(new QueryWrapper<HisMrChangeLog>()
                .eq("catalog_id", c.getId()).orderByDesc("op_time").orderByDesc("id").last("LIMIT 50")));
        return r;
    }

    /* ==================== 保存 ==================== */

    /** 保存首页头信息(主诊断/中医标志/日期/科室/编目员等白名单字段), 记变更留痕。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveHeader(Long visitId, Map<String, Object> data) {
        HisMrCatalog c = requireEditable(visitId);
        HisMrCatalog before = JSON.parseObject(JSON.toJSONString(c), HisMrCatalog.class);
        if (data.containsKey("mainDiagCode")) {
            c.setMainDiagCode(strOf(data.get("mainDiagCode")));
        }
        if (data.containsKey("mainDiagName")) {
            c.setMainDiagName(strOf(data.get("mainDiagName")));
        }
        if (data.containsKey("isTcm")) {
            c.setIsTcm(intOf(data.get("isTcm"), 0));
        }
        if (data.containsKey("admissionDate")) {
            c.setAdmissionDate(parseDate(strOf(data.get("admissionDate"))));
        }
        if (data.containsKey("dischargeDate")) {
            c.setDischargeDate(parseDate(strOf(data.get("dischargeDate"))));
        }
        if (data.containsKey("dischargeDeptId")) {
            c.setDischargeDeptId(longOf(data.get("dischargeDeptId")));
        }
        if (data.containsKey("admissionDeptId")) {
            c.setAdmissionDeptId(longOf(data.get("admissionDeptId")));
        }
        if (data.containsKey("catalogerId")) {
            c.setCatalogerId(longOf(data.get("catalogerId")));
            c.setCatalogerName(strOf(data.get("catalogerName")));
        }
        if (intOf(c.getCatalogStatus(), 1) == 1) {
            c.setCatalogStatus(2);
        }
        catalogMapper.updateById(c);
        diffLog(c, before, "catalog");
    }

    /** 全量替换诊断明细(前端提交整个诊断列表)。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveDiags(Long visitId, List<Map<String, Object>> diags) {
        HisMrCatalog c = requireEditable(visitId);
        List<HisMrDiag> oldDiags = diagMapper.selectList(new QueryWrapper<HisMrDiag>().eq("catalog_id", c.getId()));
        diagMapper.delete(new QueryWrapper<HisMrDiag>().eq("catalog_id", c.getId()));
        int sort = 0;
        String mainCode = null, mainName = null;
        List<HisMrDiag> newDiags = new ArrayList<>();
        for (Map<String, Object> d : safe(diags)) {
            HisMrDiag dg = new HisMrDiag();
            dg.setCatalogId(c.getId());
            dg.setVisitId(visitId);
            String diagType = strOf(d.getOrDefault("diagType", "dother"));
            dg.setDiagType(diagType);
            dg.setClinicalCode(strOf(d.get("clinicalCode")));
            dg.setClinicalName(strOf(d.get("clinicalName")));
            dg.setYbCode(strOf(d.get("ybCode")));
            dg.setYbName(strOf(d.get("ybName")));
            dg.setYbSortNo(intOrNull(d.get("ybSortNo")));
            dg.setReportFlag(intOf(d.get("reportFlag"), 0));
            dg.setGrayFlag(intOf(d.get("grayFlag"), 0));
            dg.setMainFlag(intOf(d.get("mainFlag"), 0));
            dg.setDoctorDesc(strOf(d.get("doctorDesc")));
            dg.setSortNo(sort++);
            diagMapper.insert(dg);
            newDiags.add(dg);
            if (mainCode == null && ("dmain".equals(diagType) || intOf(d.get("mainFlag"), 0) == 1)
                    && StringUtils.hasText(strOf(d.get("clinicalCode")))) {
                mainCode = strOf(d.get("clinicalCode"));
                mainName = strOf(d.get("clinicalName"));
            }
        }
        // 回填首页头主要诊断, 保持与诊断明细一致(供编目/审核列表"主要诊断"列展示)
        c.setMainDiagCode(mainCode);
        c.setMainDiagName(mainName);
        if (intOf(c.getCatalogStatus(), 1) == 1) {
            c.setCatalogStatus(2);
        }
        catalogMapper.updateById(c);
        diffDiagLog(c.getId(), visitId, oldDiags, newDiags);
    }

    /** 全量替换手术明细。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveOpers(Long visitId, List<Map<String, Object>> opers) {
        HisMrCatalog c = requireEditable(visitId);
        List<HisMrOper> oldOpers = operMapper.selectList(new QueryWrapper<HisMrOper>().eq("catalog_id", c.getId()));
        operMapper.delete(new QueryWrapper<HisMrOper>().eq("catalog_id", c.getId()));
        int sort = 0;
        List<HisMrOper> newOpers = new ArrayList<>();
        for (Map<String, Object> o : safe(opers)) {
            HisMrOper op = new HisMrOper();
            op.setCatalogId(c.getId());
            op.setVisitId(visitId);
            op.setClinicalCode(strOf(o.get("clinicalCode")));
            op.setClinicalName(strOf(o.get("clinicalName")));
            op.setYbCode(strOf(o.get("ybCode")));
            op.setYbName(strOf(o.get("ybName")));
            op.setYbSortNo(intOrNull(o.get("ybSortNo")));
            op.setReportFlag(intOf(o.get("reportFlag"), 0));
            op.setMainFlag(intOf(o.get("mainFlag"), 0));
            op.setGrayFlag(intOf(o.get("grayFlag"), 0));
            op.setOperDate(parseDate(strOf(o.get("operDate"))));
            op.setSurgeonName(strOf(o.get("surgeonName")));
            op.setAnesthesia(strOf(o.get("anesthesia")));
            op.setIncisionType(strOf(o.get("incisionType")));
            op.setHealLevel(strOf(o.get("healLevel")));
            op.setSortNo(sort++);
            operMapper.insert(op);
            newOpers.add(op);
        }
        diffOperLog(c.getId(), visitId, oldOpers, newOpers);
    }

    /** 全量替换扩展记录(转科/过敏/重症, recType 分组提交)。 */
    @Transactional(rollbackFor = Exception.class)
    public void saveOthers(Long visitId, String recType, List<Map<String, Object>> rows) {
        HisMrCatalog c = requireEditable(visitId);
        otherMapper.delete(new QueryWrapper<HisMrOther>().eq("catalog_id", c.getId()).eq("rec_type", recType));
        int sort = 0;
        for (Map<String, Object> o : safe(rows)) {
            HisMrOther ot = new HisMrOther();
            ot.setCatalogId(c.getId());
            ot.setVisitId(visitId);
            ot.setRecType(recType);
            ot.setCode(strOf(o.get("code")));
            ot.setName(strOf(o.get("name")));
            ot.setDetail(strOf(o.get("detail")));
            ot.setBeginTime(parseDate(strOf(o.get("beginTime"))));
            ot.setEndTime(parseDate(strOf(o.get("endTime"))));
            ot.setSortNo(sort++);
            otherMapper.insert(ot);
        }
    }

    /** 编目定稿: 强制类校验通过后 catalog_status=3。 */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> finalizeCatalog(Long visitId) {
        HisMrCatalog c = requireEditable(visitId);
        List<Map<String, Object>> errs = qualityService.validate(c);
        for (Map<String, Object> e : errs) {
            if ("强制".equals(e.get("ruleCategory"))) {
                throw new BizException(400, "存在强制类质控错误, 无法定稿: " + e.get("errorMsg"));
            }
        }
        c.setCatalogStatus(3);
        catalogMapper.updateById(c);
        logChange(c.getId(), visitId, "catalog", "finalize", "2", "3");
        return errs;
    }

    /* ==================== 校验与留痕 ==================== */

    /** 实时校验(不落库), 供编目页右侧错误栏定位。 */
    public List<Map<String, Object>> validate(Long visitId) {
        HisMrCatalog c = catalogMapper.selectOne(new QueryWrapper<HisMrCatalog>()
                .eq("visit_id", visitId).last("LIMIT 1"));
        if (c == null) {
            throw new BizException(404, "病案编目尚未生成");
        }
        return qualityService.validate(c);
    }

    private HisMrCatalog requireEditable(Long visitId) {
        HisMrCatalog c = catalogMapper.selectOne(new QueryWrapper<HisMrCatalog>()
                .eq("visit_id", visitId).last("LIMIT 1"));
        if (c == null) {
            throw new BizException(404, "病案编目尚未生成, 请先分配或生成");
        }
        if (intOf(c.getLockStatus(), 0) == 1) {
            throw new BizException(409, "病案已锁定, 需质控人员解锁后方可修改");
        }
        return c;
    }

    private void diffLog(HisMrCatalog after, HisMrCatalog before, String opType) {
        cmp(after.getId(), after.getVisitId(), opType, "mainDiagCode", before.getMainDiagCode(), after.getMainDiagCode());
        cmp(after.getId(), after.getVisitId(), opType, "mainDiagName", before.getMainDiagName(), after.getMainDiagName());
        cmp(after.getId(), after.getVisitId(), opType, "isTcm", strOf(before.getIsTcm()), strOf(after.getIsTcm()));
        cmp(after.getId(), after.getVisitId(), opType, "dischargeDeptId", strOf(before.getDischargeDeptId()), strOf(after.getDischargeDeptId()));
        cmp(after.getId(), after.getVisitId(), opType, "catalogerName", before.getCatalogerName(), after.getCatalogerName());
    }

    private void cmp(Long cid, Long visitId, String opType, String field, String oldV, String newV) {
        String a = oldV == null ? "" : oldV;
        String b = newV == null ? "" : newV;
        if (!a.equals(b)) {
            logChange(cid, visitId, opType, field, a, b);
        }
    }

    private void logChange(Long cid, Long visitId, String opType, String field, String oldV, String newV) {
        HisMrChangeLog l = new HisMrChangeLog();
        l.setCatalogId(cid);
        l.setVisitId(visitId);
        l.setOpType(opType);
        l.setFieldKey(field);
        l.setOldVal(oldV);
        l.setNewVal(newV);
        LoginUser lu = UserContext.get();
        l.setOpUser(lu == null ? null : lu.getRealName());
        l.setOpTime(LocalDateTime.now());
        logMapper.insert(l);
    }

    /** 供分配/审核服务复用: 记录一条留痕。 */
    public void logChangePub(Long cid, Long visitId, String opType, String field, String oldV, String newV) {
        logChange(cid, visitId, opType, field, oldV, newV);
    }

    /* ==================== 明细保存留痕 ==================== */
    /* 全量替换语义下按身份键比对: 未命中键记 新增/删除, 命中键逐字段记 修改; 逐条落 his_mr_change_log。 */

    private static String diagKey(HisMrDiag d) {
        String code = StringUtils.hasText(d.getClinicalCode()) ? d.getClinicalCode() : strOf(d.getClinicalName());
        return d.getDiagType() + "|" + (code == null ? "" : code);
    }

    private static String diagDesc(HisMrDiag d) {
        return (d.getClinicalCode() == null ? "" : d.getClinicalCode()) + " " + (d.getClinicalName() == null ? "" : d.getClinicalName());
    }

    private void diffDiagLog(Long cid, Long visitId, List<HisMrDiag> olds, List<HisMrDiag> news) {
        Map<String, HisMrDiag> oldMap = new LinkedHashMap<>();
        for (HisMrDiag o : olds) { oldMap.put(diagKey(o), o); }
        Map<String, HisMrDiag> newMap = new LinkedHashMap<>();
        for (HisMrDiag n : news) { newMap.put(diagKey(n), n); }
        for (Map.Entry<String, HisMrDiag> en : oldMap.entrySet()) {
            if (!newMap.containsKey(en.getKey())) {
                logChange(cid, visitId, "诊断删除", en.getKey(), diagDesc(en.getValue()), "");
            }
        }
        for (Map.Entry<String, HisMrDiag> en : newMap.entrySet()) {
            HisMrDiag o = oldMap.get(en.getKey());
            if (o == null) {
                logChange(cid, visitId, "诊断新增", en.getKey(), "", diagDesc(en.getValue()));
            } else {
                HisMrDiag n = en.getValue();
                String fk = "diag." + en.getKey();
                cmp(cid, visitId, "诊断修改", fk + ".clinicalName", o.getClinicalName(), n.getClinicalName());
                cmp(cid, visitId, "诊断修改", fk + ".ybCode", o.getYbCode(), n.getYbCode());
                cmp(cid, visitId, "诊断修改", fk + ".ybName", o.getYbName(), n.getYbName());
                cmp(cid, visitId, "诊断修改", fk + ".mainFlag", strOf(o.getMainFlag()), strOf(n.getMainFlag()));
                cmp(cid, visitId, "诊断修改", fk + ".reportFlag", strOf(o.getReportFlag()), strOf(n.getReportFlag()));
            }
        }
    }

    private static String operKey(HisMrOper o) {
        return StringUtils.hasText(o.getClinicalCode()) ? o.getClinicalCode() : strOf(o.getClinicalName());
    }

    private static String operDesc(HisMrOper o) {
        return (o.getClinicalCode() == null ? "" : o.getClinicalCode()) + " " + (o.getClinicalName() == null ? "" : o.getClinicalName());
    }

    private void diffOperLog(Long cid, Long visitId, List<HisMrOper> olds, List<HisMrOper> news) {
        Map<String, HisMrOper> oldMap = new LinkedHashMap<>();
        for (HisMrOper o : olds) { oldMap.put(operKey(o), o); }
        Map<String, HisMrOper> newMap = new LinkedHashMap<>();
        for (HisMrOper n : news) { newMap.put(operKey(n), n); }
        for (Map.Entry<String, HisMrOper> en : oldMap.entrySet()) {
            if (!newMap.containsKey(en.getKey())) {
                logChange(cid, visitId, "手术删除", en.getKey(), operDesc(en.getValue()), "");
            }
        }
        for (Map.Entry<String, HisMrOper> en : newMap.entrySet()) {
            HisMrOper o = oldMap.get(en.getKey());
            if (o == null) {
                logChange(cid, visitId, "手术新增", en.getKey(), "", operDesc(en.getValue()));
            } else {
                HisMrOper n = en.getValue();
                String fk = "oper." + en.getKey();
                cmp(cid, visitId, "手术修改", fk + ".clinicalName", o.getClinicalName(), n.getClinicalName());
                cmp(cid, visitId, "手术修改", fk + ".ybCode", o.getYbCode(), n.getYbCode());
                cmp(cid, visitId, "手术修改", fk + ".ybName", o.getYbName(), n.getYbName());
                cmp(cid, visitId, "手术修改", fk + ".mainFlag", strOf(o.getMainFlag()), strOf(n.getMainFlag()));
                cmp(cid, visitId, "手术修改", fk + ".surgeonName", o.getSurgeonName(), n.getSurgeonName());
            }
        }
    }

    public HisMrCatalog findByVisit(Long visitId) {
        return catalogMapper.selectOne(new QueryWrapper<HisMrCatalog>().eq("visit_id", visitId).last("LIMIT 1"));
    }

    /* ==================== 工具 ==================== */

    private List<Map<String, Object>> safe(List<Map<String, Object>> l) {
        return l == null ? new ArrayList<>() : l;
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

    static String strOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static int intOf(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    static Integer intOrNull(Object o) {
        if (o == null || !StringUtils.hasText(String.valueOf(o))) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    static Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
