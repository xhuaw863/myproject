package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.emr.HisEmrQcDefect;
import com.yb.hi.entity.emr.HisEmrQcNode;
import com.yb.hi.entity.emr.HisEmrQcNotice;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.framework.util.SafeJsonTool;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.emr.HisEmrQcDefectMapper;
import com.yb.hi.mapper.emr.HisEmrQcNodeMapper;
import com.yb.hi.mapper.emr.HisEmrQcNoticeMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.mapper.SysUserMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.EmrQualityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工质控服务(病历P5b-2): 质控员对归档病历实施"抽检 → 分配 → 审核(自动预评分+人工缺陷) → 甲乙丙评定
 * → 整改通知单下发 → 临床整改 → 复核 → 申诉仲裁"全链路人工质控闭环, 全过程节点留痕 his_emr_qc_node。
 *
 * 核心语义:
 * - 抽检口径: 该科室该月"已签名/已归档"(his_inp_medical_record.status IN (2,3))的病历, 归档月份按
 *   COALESCE(就诊出院时间, 病历记录时间, 创建时间) 判定; 按比例(1-100%)随机抽取(Collections.shuffle)。
 * - 自动预评分: 开始审核时若尚无自动缺陷, 调 EmrQualityService.evaluateContent(recordId, 2) 执行归档级内涵质控
 *   (有自动缺陷则跳过, 避免重复调用累加重复缺陷); 甲乙丙预评级复用 EmrQualityService.gradeRecord。
 * - 缺陷来源: auto_generated=0 人工登记 / 1 自动检出; activateAutoDefect 将质控员确认保留的自动缺陷固定为
 *   未整改(status=0)并纳入归档质控扣分。
 * - 完成审核评分: 100 分起扣, 剔除已整改(1)/豁免(4)缺陷, severity=2 按 deduct_score 扣分,
 *   severity=3 存在未解决缺陷时一票否决 0 分; 最终分数与评定明细回写 his_inp_medical_record.quality_score/quality_detail。
 * - 整改通知单状态机(与 his_emr_qc_notice DDL 一致): 0下发→1已读→2整改中→3已整改→4已复核→5已关闭,
 *   6申诉中; 复核通过一次完成"已复核→已关闭"(4→5), 复核驳回打回 2整改中; 申诉通过关闭(5), 申诉驳回重新下发(0)。
 * - SSE 推送: 通知单下行走 EMR_QC_NOTICE、申诉结果走 EMR_QC_APPEAL_RESULT, 经 EmrEventPublisher →
 *   EmrEventListener 路由至 SseEmitterService.sendToDept(deptId, eventType, envelope) 推送责任科室全部在线用户。
 * - 权限: 写操作 requireSameOrg(记录归属机构须与登录机构一致, 平台超管放行); 读操作按 guard.scopeOrgId 机构范围隔离
 *   (牵头机构/超管全院, 成员机构仅本机构)。
 */
@Slf4j
@Service
public class MrManualQcService {

    private static final DateTimeFormatter NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final BigDecimal FULL_SCORE = new BigDecimal("100");

    /** 记录类型标签(1-15): 与 InpMedRecordService.RECORD_TYPE_LABELS 同口径 */
    private static final Map<Integer, String> RECORD_TYPE_LABELS;

    /** 病历记录状态文案 */
    private static final Map<Integer, String> RECORD_STATUS_TEXT;

    /** 缺陷状态文案 */
    private static final Map<Integer, String> DEFECT_STATUS_TEXT;

    /** 通知单状态文案 */
    private static final Map<Integer, String> NOTICE_STATUS_TEXT;

    static {
        Map<Integer, String> types = new LinkedHashMap<>();
        types.put(1, "入院记录");
        types.put(2, "首次病程记录");
        types.put(3, "日常病程记录");
        types.put(4, "查房记录");
        types.put(5, "术前小结");
        types.put(6, "手术记录");
        types.put(7, "术后病程记录");
        types.put(8, "出院小结");
        types.put(9, "死亡记录");
        types.put(10, "病案首页");
        types.put(11, "交接班记录");
        types.put(12, "转科记录");
        types.put(13, "知情同意书");
        types.put(14, "讨论记录");
        types.put(15, "会诊记录");
        RECORD_TYPE_LABELS = Collections.unmodifiableMap(types);
        RECORD_STATUS_TEXT = statusText(1, "草稿", 2, "已提交", 3, "已审核");
        DEFECT_STATUS_TEXT = statusText(0, "未整改", 1, "已整改", 2, "已申诉", 3, "申诉驳回", 4, "豁免");
        NOTICE_STATUS_TEXT = statusText(0, "已下发", 1, "已读", 2, "整改中", 3, "已整改", 4, "已复核", 5, "已关闭", 6, "申诉中");
    }

    /** 抽检候选 SQL 头(在院病历不计入归档抽检, 由归档月 COALESCE 判定) */
    private static final String SAMPLE_SQL_HEAD =
            "SELECT r.id AS recordId, r.inp_visit_id AS visitId, r.record_type AS recordType, r.title, r.status, "
                    + "r.record_time AS recordTime, r.doctor_id AS doctorId, r.quality_score AS qualityScore, "
                    + "v.patient_id AS patientId, v.inp_no AS inpNo, v.dept_id AS deptId, v.visit_status AS visitStatus, "
                    + "p.name AS patientName, s.staff_name AS doctorName, d.dept_name AS deptName "
                    + "FROM his_inp_medical_record r "
                    + "JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                    + "LEFT JOIN his_patient p ON v.patient_id = p.id "
                    + "LEFT JOIN his_staff s ON r.doctor_id = s.id "
                    + "LEFT JOIN his_dept d ON v.dept_id = d.id "
                    + "WHERE r.deleted = 0 AND r.status IN (2, 3) "
                    + "AND COALESCE(v.discharge_date, r.record_time, r.create_time) >= ? "
                    + "AND COALESCE(v.discharge_date, r.record_time, r.create_time) < ? ";

    private final HisInpMedicalRecordMapper recordMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisEmrQcDefectMapper defectMapper;
    private final HisEmrQcNoticeMapper noticeMapper;
    private final HisEmrQcNodeMapper nodeMapper;
    private final HisDeptMapper deptMapper;
    private final HisStaffMapper staffMapper;
    private final HisPatientMapper patientMapper;
    private final SysUserMapper userMapper;
    private final EmrQualityService qualityService;
    private final OrgAccessGuard guard;
    private final EmrEventPublisher emrEventPublisher;
    private final SafeJsonTool safeJsonTool;
    private final JdbcTemplate jdbcTemplate;

    public MrManualQcService(HisInpMedicalRecordMapper recordMapper, HisInpVisitMapper visitMapper,
                             HisEmrQcDefectMapper defectMapper, HisEmrQcNoticeMapper noticeMapper,
                             HisEmrQcNodeMapper nodeMapper, HisDeptMapper deptMapper,
                             HisStaffMapper staffMapper, HisPatientMapper patientMapper,
                             SysUserMapper userMapper, EmrQualityService qualityService,
                             OrgAccessGuard guard, EmrEventPublisher emrEventPublisher,
                             SafeJsonTool safeJsonTool, JdbcTemplate jdbcTemplate) {
        this.recordMapper = recordMapper;
        this.visitMapper = visitMapper;
        this.defectMapper = defectMapper;
        this.noticeMapper = noticeMapper;
        this.nodeMapper = nodeMapper;
        this.deptMapper = deptMapper;
        this.staffMapper = staffMapper;
        this.patientMapper = patientMapper;
        this.userMapper = userMapper;
        this.qualityService = qualityService;
        this.guard = guard;
        this.emrEventPublisher = emrEventPublisher;
        this.safeJsonTool = safeJsonTool;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ===================== 抽检 ===================== */

    /**
     * 按科室/月份/比例随机抽取归档病历: 候选=该科室该月已签名/已归档(status 2/3)病历,
     * 归档月份按 COALESCE(出院时间, 记录时间, 创建时间) 判定; shuffle 后取 ceil(候选数×比例/100) 份。
     *
     * @param deptId     科室ID(空=机构范围内全部科室)
     * @param month      yyyy-MM(空=当月)
     * @param sampleRate 抽检比例(1-100, 百分比)
     * @return 抽取列表(recordId/visitId/patientName/inpNo/deptName/recordType/typeLabel/doctorName/status...)
     */
    public List<Map<String, Object>> randomSample(Long deptId, String month, int sampleRate) {
        if (sampleRate < 1 || sampleRate > 100) {
            throw new BizException(400, "抽检比例须为1-100的整数(百分比)");
        }
        LocalDate firstDay = parseMonth(month);
        Long scopeOrg = readScopeOrg();
        if (deptId != null) {
            HisDept dept = deptMapper.selectById(deptId);
            if (dept == null) {
                throw new BizException(404, "科室不存在");
            }
            if (scopeOrg != null && dept.getOrgId() != null && !scopeOrg.equals(dept.getOrgId())) {
                throw new BizException(403, "该科室不属于当前登录机构, 无权抽检");
            }
        }
        StringBuilder sql = new StringBuilder(SAMPLE_SQL_HEAD);
        List<Object> params = new ArrayList<>();
        params.add(firstDay.atStartOfDay());
        params.add(firstDay.plusMonths(1).atStartOfDay());
        if (TenantContext.get() != null) {
            sql.append("AND r.tenant_id = ? ");
            params.add(TenantContext.get());
        }
        if (deptId != null) {
            sql.append("AND v.dept_id = ? ");
            params.add(deptId);
        }
        if (scopeOrg != null) {
            sql.append("AND r.org_id = ? ");
            params.add(scopeOrg);
        }
        sql.append("ORDER BY r.id");
        List<Map<String, Object>> candidates = jdbcTemplate.query(sql.toString(), params.toArray(),
                (rs, i) -> toSampleRow(rs));
        if (candidates.isEmpty()) {
            return candidates;
        }
        Collections.shuffle(candidates);
        int picked = (int) Math.ceil(candidates.size() * sampleRate / 100.0);
        picked = Math.max(1, Math.min(picked, candidates.size()));
        List<Map<String, Object>> result = new ArrayList<>(candidates.subList(0, picked));
        log.info("人工质控抽检: deptId={}, month={}, 比例{}%, 候选{}份, 抽取{}份",
                deptId, firstDay.toString().substring(0, 7), sampleRate, candidates.size(), result.size());
        return result;
    }

    /** 抽检候选行映射(患者/医生/科室名称由 SQL 联查冗余) */
    private Map<String, Object> toSampleRow(java.sql.ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordId", rs.getObject("recordId"));
        m.put("visitId", rs.getObject("visitId"));
        m.put("patientId", rs.getObject("patientId"));
        m.put("patientName", rs.getString("patientName"));
        m.put("inpNo", rs.getString("inpNo"));
        m.put("deptId", rs.getObject("deptId"));
        m.put("deptName", rs.getString("deptName"));
        Integer recordType = rs.getObject("recordType") == null ? null : rs.getInt("recordType");
        m.put("recordType", recordType);
        m.put("typeLabel", RECORD_TYPE_LABELS.getOrDefault(recordType, "病历"));
        m.put("title", rs.getString("title"));
        Integer status = rs.getObject("status") == null ? null : rs.getInt("status");
        m.put("status", status);
        m.put("statusLabel", RECORD_STATUS_TEXT.getOrDefault(status, "-"));
        java.sql.Timestamp recordTime = rs.getTimestamp("recordTime");
        m.put("recordTime", recordTime == null ? null : recordTime.toLocalDateTime().format(DT_FMT));
        m.put("doctorId", rs.getObject("doctorId"));
        m.put("doctorName", rs.getString("doctorName"));
        m.put("qualityScore", rs.getBigDecimal("qualityScore"));
        m.put("visitStatus", rs.getObject("visitStatus"));
        return m;
    }

    /* ===================== 分配与审核 ===================== */

    /** 分配质控任务: 逐份病历在 his_emr_qc_node 记录"人工质控任务分配"节点(操作人=当前登录)。 */
    @Transactional(rollbackFor = Exception.class)
    public void assignToQc(List<Long> recordIds, Long qcUserId) {
        if (recordIds == null || recordIds.isEmpty()) {
            throw new BizException(400, "请至少选择一份病历进行分配");
        }
        if (qcUserId == null) {
            throw new BizException(400, "质控员不能为空");
        }
        String qcName = resolveUserName(qcUserId);
        for (Long recordId : recordIds) {
            HisInpMedicalRecord record = requireRecordAccess(recordId);
            logNode(record.getId(), record.getInpVisitId(), "质控",
                    "人工质控任务分配: 质控员" + qcName + "(ID=" + qcUserId + ")"
                            + ", 病历[" + typeLabel(record.getRecordType()) + "]" + text(record.getTitle()),
                    record.getQualityScore(), record.getQualityScore());
        }
        log.info("人工质控任务分配完成: 质控员{}({}), 病历{}份", qcName, qcUserId, recordIds.size());
    }

    /**
     * 开始审核: 自动预评分(归档级内涵质控, 仅首次)+ 缺陷列表 + 甲乙丙预评级, 记录审核开始节点。
     *
     * @param qcUserId 质控员用户ID(空=当前登录人)
     * @return {record: 病历摘要, defects: 缺陷列表, autoScore: 预评分, autoGrade: 预评级}
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> startReview(Long recordId, Long qcUserId) {
        HisInpMedicalRecord record = requireRecordAccess(recordId);
        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        /* 1. 自动预评分: 尚无自动缺陷时执行归档级内涵质控(stage=2), 已有则跳过避免重复累加 */
        Long autoCount = defectMapper.selectCount(Wrappers.<HisEmrQcDefect>lambdaQuery()
                .eq(HisEmrQcDefect::getRecordId, recordId)
                .eq(HisEmrQcDefect::getAutoGenerated, 1));
        if (autoCount == null || autoCount == 0) {
            try {
                qualityService.evaluateContent(recordId, 2);
            } catch (Exception e) {
                log.warn("人工质控自动预评分失败(不阻断人工审核): recordId={}, {}", recordId, e.getMessage());
            }
        }
        /* 2. 缺陷列表(人工+自动, 未整改在前) */
        List<HisEmrQcDefect> defects = qualityService.listDefects(recordId);
        /* 3. 甲乙丙预评级(基于当前评分与未解决一票否决缺陷) */
        Map<String, Object> gradeInfo = qualityService.gradeRecord(recordId);
        String autoGrade = String.valueOf(gradeInfo.get("grade"));
        Object autoScore = gradeInfo.get("score");
        /* 4. 审核开始节点留痕 */
        String reviewer = qcUserId != null ? resolveUserName(qcUserId) + "(ID=" + qcUserId + ")" : displayName(requireLogin());
        logNode(recordId, record.getInpVisitId(), "质控",
                "人工质控开始审核: 质控员" + reviewer + ", 预评分" + (autoScore == null ? "未评" : autoScore)
                        + ", 预评级" + autoGrade + ", 已有缺陷" + defects.size() + "条",
                record.getQualityScore(), record.getQualityScore());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("record", loadRecordBrief(record, visit));
        out.put("defects", defects);
        out.put("autoScore", autoScore);
        out.put("autoGrade", autoGrade);
        return out;
    }

    /* ===================== 缺陷管理 ===================== */

    /** 新增人工缺陷: auto_generated=0, qc_stage=2归档质控, status=0未整改, 记录质控节点。 */
    @Transactional(rollbackFor = Exception.class)
    public HisEmrQcDefect addDefect(Long recordId, HisEmrQcDefect defect) {
        HisInpMedicalRecord record = requireRecordAccess(recordId);
        if (defect == null) {
            throw new BizException(400, "缺陷内容不能为空");
        }
        if (!StringUtils.hasText(defect.getDefectDesc()) && !StringUtils.hasText(defect.getRuleName())) {
            throw new BizException(400, "缺陷描述不能为空");
        }
        validateSeverity(defect.getSeverity());
        if (defect.getDeductScore() != null && defect.getDeductScore().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(400, "扣分值不能为负数");
        }
        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        defect.setId(null);
        defect.setRecordId(recordId);
        if (defect.getVisitId() == null) {
            defect.setVisitId(record.getInpVisitId());
        }
        if (defect.getPatientId() == null && visit != null) {
            defect.setPatientId(visit.getPatientId());
        }
        defect.setAutoGenerated(0);
        defect.setQcStage(2);
        defect.setStatus(0);
        if (defect.getSeverity() == null) {
            defect.setSeverity(2);
        }
        if (defect.getDeductScore() == null) {
            defect.setDeductScore(BigDecimal.ZERO);
        }
        if (!StringUtils.hasText(defect.getDefectType())) {
            defect.setDefectType("内涵");
        }
        defectMapper.insert(defect);
        logNode(recordId, defect.getVisitId(), "质控",
                "人工质控登记缺陷(ID=" + defect.getId() + "): " + brief(defect),
                record.getQualityScore(), record.getQualityScore());
        log.info("人工质控新增缺陷: recordId={}, defectId={}, type={}, severity={}, deduct={}",
                recordId, defect.getId(), defect.getDefectType(), defect.getSeverity(), defect.getDeductScore());
        return defect;
    }

    /**
     * 编辑缺陷(白名单字段: 类型/描述/扣分/严重度/规则名):
     * 仅人工缺陷(auto_generated=0)或未整改状态的自动缺陷可编辑。
     */
    @Transactional(rollbackFor = Exception.class)
    public void editDefect(Long defectId, HisEmrQcDefect dto) {
        HisEmrQcDefect exist = requireDefectAccess(defectId);
        if (exist.getAutoGenerated() != null && exist.getAutoGenerated() == 1
                && exist.getStatus() != null && exist.getStatus() != 0) {
            throw new BizException(400, "自动检出缺陷仅未整改状态可编辑(当前状态: "
                    + defectStatusText(exist.getStatus()) + ")");
        }
        if (dto == null) {
            throw new BizException(400, "缺陷内容不能为空");
        }
        validateSeverity(dto.getSeverity());
        if (dto.getDeductScore() != null && dto.getDeductScore().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(400, "扣分值不能为负数");
        }
        HisEmrQcDefect upd = new HisEmrQcDefect();
        upd.setId(defectId);
        if (StringUtils.hasText(dto.getDefectType())) {
            upd.setDefectType(dto.getDefectType());
        }
        if (dto.getDefectDesc() != null) {
            upd.setDefectDesc(dto.getDefectDesc());
        }
        if (dto.getDeductScore() != null) {
            upd.setDeductScore(dto.getDeductScore());
        }
        if (dto.getSeverity() != null) {
            upd.setSeverity(dto.getSeverity());
        }
        if (dto.getRuleName() != null) {
            upd.setRuleName(dto.getRuleName());
        }
        defectMapper.updateById(upd);
        logNode(exist.getRecordId(), exist.getVisitId(), "质控",
                "人工质控编辑缺陷(ID=" + defectId + "): " + brief(exist)
                        + (StringUtils.hasText(dto.getDefectDesc()) ? " → " + truncate(dto.getDefectDesc(), 100) : ""),
                null, null);
    }

    /** 删除缺陷(逻辑删除, 质控节点留痕)。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDefect(Long defectId) {
        HisEmrQcDefect exist = requireDefectAccess(defectId);
        defectMapper.deleteById(defectId);
        logNode(exist.getRecordId(), exist.getVisitId(), "质控",
                "人工质控删除缺陷(ID=" + defectId + "): " + brief(exist), null, null);
    }

    /**
     * 激活自动缺陷: 质控员复核后确认保留的机器缺陷, 固定为未整改(status=0)并纳入归档质控扣分
     * (适用于曾豁免/被推翻后重新确认的场景; 人工缺陷默认即生效, 无需激活)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void activateAutoDefect(Long defectId) {
        HisEmrQcDefect exist = requireDefectAccess(defectId);
        if (exist.getAutoGenerated() == null || exist.getAutoGenerated() != 1) {
            throw new BizException(400, "仅自动检出缺陷可执行激活(人工缺陷默认生效)");
        }
        HisEmrQcDefect upd = new HisEmrQcDefect();
        upd.setId(defectId);
        upd.setStatus(0);
        upd.setQcStage(2);
        defectMapper.updateById(upd);
        logNode(exist.getRecordId(), exist.getVisitId(), "质控",
                "人工质控激活自动缺陷(ID=" + defectId + "): " + brief(exist) + " [确认保留, 纳入归档质控扣分]",
                null, null);
    }

    /* ===================== 完成审核(甲乙丙评定) ===================== */

    /**
     * 完成审核: 汇总未解决缺陷扣分(已整改/豁免剔除, severity=2 计扣, severity=3 一票否决),
     * 最终分数与评定明细回写 his_inp_medical_record.quality_score/quality_detail, 节点留痕前后评分。
     *
     * @param grade 人工评定等级(甲/乙/丙, 质控员最终裁定; 系统建议 autoGrade 随响应返回)
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> finishReview(Long recordId, String grade) {
        HisInpMedicalRecord record = requireRecordAccess(recordId);
        String g = StringUtils.hasText(grade) ? grade.trim() : null;
        if (!"甲".equals(g) && !"乙".equals(g) && !"丙".equals(g)) {
            throw new BizException(400, "评级须为甲/乙/丙");
        }
        List<HisEmrQcDefect> defects = defectMapper.selectList(Wrappers.<HisEmrQcDefect>lambdaQuery()
                .eq(HisEmrQcDefect::getRecordId, recordId)
                .orderByAsc(HisEmrQcDefect::getStatus)
                .orderByDesc(HisEmrQcDefect::getId));
        BigDecimal deduct = BigDecimal.ZERO;
        boolean veto = false;
        int deductedCount = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (HisEmrQcDefect d : defects) {
            int status = d.getStatus() == null ? 0 : d.getStatus();
            boolean resolved = status == 1 || status == 4; // 已整改/豁免不参与扣分
            int severity = d.getSeverity() == null ? 2 : d.getSeverity();
            BigDecimal ds = d.getDeductScore() == null ? BigDecimal.ZERO : d.getDeductScore();
            if (!resolved) {
                if (severity == 3) {
                    veto = true;
                } else if (severity == 2 && ds.compareTo(BigDecimal.ZERO) > 0) {
                    deduct = deduct.add(ds);
                    deductedCount++;
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("defectId", d.getId());
            item.put("ruleId", d.getRuleId());
            item.put("ruleCode", d.getRuleCode());
            item.put("ruleName", d.getRuleName());
            item.put("defectType", d.getDefectType());
            item.put("severity", severity);
            item.put("passed", false);
            item.put("deductScore", resolved || severity != 2 ? BigDecimal.ZERO : ds);
            item.put("message", "【" + (isAuto(d) ? "自动" : "人工") + "】"
                    + (StringUtils.hasText(d.getDefectDesc()) ? d.getDefectDesc() : d.getRuleName()));
            item.put("source", isAuto(d) ? "auto" : "manual");
            item.put("status", status);
            details.add(item);
        }
        BigDecimal finalScore = veto ? BigDecimal.ZERO
                : FULL_SCORE.subtract(deduct).max(BigDecimal.ZERO);
        finalScore = finalScore.setScale(1, RoundingMode.HALF_UP);
        String autoGrade = veto ? "丙" : (finalScore.compareTo(new BigDecimal("90")) >= 0 ? "甲"
                : (finalScore.compareTo(new BigDecimal("70")) >= 0 ? "乙" : "丙"));
        BigDecimal scoreBefore = record.getQualityScore();
        record.setQualityScore(finalScore);
        /* 嵌套 JSON 走 SafeJsonTool: defectId 为雪花 Long, 防前端二次 parse 丢精度 */
        record.setQualityDetail(safeJsonTool.toJson(details));
        recordMapper.updateById(record);
        logNode(recordId, record.getInpVisitId(), "质控",
                "人工质控完成: 评级" + g + "(系统建议" + autoGrade + "), 得分" + finalScore
                        + ", 缺陷" + defects.size() + "条/扣分" + deductedCount + "条" + (veto ? "/一票否决" : ""),
                scoreBefore, finalScore);
        log.info("人工质控完成审核: recordId={}, 评级={}, 得分={}, 预评分={}, 缺陷{}条",
                recordId, g, finalScore, scoreBefore, defects.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordId", recordId);
        out.put("scoreBefore", scoreBefore);
        out.put("finalScore", finalScore);
        out.put("grade", g);
        out.put("autoGrade", autoGrade);
        out.put("defectCount", defects.size());
        out.put("deductedCount", deductedCount);
        out.put("totalDeduct", deduct.setScale(2, RoundingMode.HALF_UP));
        out.put("vetoApplied", veto);
        return out;
    }

    /* ===================== 整改通知单 ===================== */

    /**
     * 下发整改通知单: 编号 QC-yyyyMMdd-序号(当天count+1), status=0下发, issueTime=now;
     * 缺陷打包(defectIds 入参优先并校验归属, 缺省打包全部未解决缺陷), 累计扣分与整改前等级自动补齐;
     * SSE EMR_QC_NOTICE 定向推送责任科室, 记录质控节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisEmrQcNotice issueNotice(HisEmrQcNotice notice) {
        if (notice == null) {
            throw new BizException(400, "通知单内容不能为空");
        }
        if (notice.getRecordId() == null) {
            throw new BizException(400, "关联病历记录不能为空");
        }
        HisInpMedicalRecord record = requireRecordAccess(notice.getRecordId());
        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        if (notice.getVisitId() == null) {
            notice.setVisitId(record.getInpVisitId());
        }
        if (notice.getDeptId() == null && visit != null) {
            notice.setDeptId(visit.getDeptId());
        }
        if (notice.getDeptId() == null) {
            throw new BizException(400, "责任科室不能为空(病历未关联就诊科室时须显式指定)");
        }
        HisDept dept = deptMapper.selectById(notice.getDeptId());
        if (dept == null) {
            throw new BizException(404, "责任科室不存在");
        }
        if (!StringUtils.hasText(notice.getDeptName())) {
            notice.setDeptName(dept.getDeptName());
        }
        /* 缺陷打包: 入参优先(校验归属), 缺省自动打包全部未解决(未整改/已申诉/申诉驳回)缺陷 */
        List<Long> defectIds = parseDefectIds(notice.getDefectIds());
        List<HisEmrQcDefect> packed;
        if (defectIds.isEmpty()) {
            packed = defectMapper.selectList(Wrappers.<HisEmrQcDefect>lambdaQuery()
                    .eq(HisEmrQcDefect::getRecordId, record.getId())
                    .notIn(HisEmrQcDefect::getStatus, 1, 4)
                    .orderByDesc(HisEmrQcDefect::getId));
            if (packed.isEmpty()) {
                throw new BizException(400, "该病历无未解决缺陷, 无需下发整改通知");
            }
            for (HisEmrQcDefect d : packed) {
                defectIds.add(d.getId());
            }
        } else {
            packed = defectMapper.selectList(Wrappers.<HisEmrQcDefect>lambdaQuery()
                    .eq(HisEmrQcDefect::getRecordId, record.getId())
                    .in(HisEmrQcDefect::getId, defectIds));
            if (packed.size() != defectIds.size()) {
                throw new BizException(400, "部分缺陷不属于该病历或已被删除, 请刷新后重试");
            }
        }
        notice.setDefectIds(safeJsonTool.toJson(defectIds));
        BigDecimal total = BigDecimal.ZERO;
        for (HisEmrQcDefect d : packed) {
            int status = d.getStatus() == null ? 0 : d.getStatus();
            boolean resolved = status == 1 || status == 4;
            if (!resolved && d.getSeverity() != null && d.getSeverity() == 2 && d.getDeductScore() != null) {
                total = total.add(d.getDeductScore());
            }
        }
        notice.setTotalDeduct(total.setScale(2, RoundingMode.HALF_UP));
        if (!StringUtils.hasText(notice.getGradeBefore())) {
            try {
                notice.setGradeBefore(String.valueOf(qualityService.gradeRecord(record.getId()).get("grade")));
            } catch (Exception e) {
                log.warn("整改前等级自动获取失败(不影响下发): recordId={}, {}", record.getId(), e.getMessage());
            }
        }
        LoginUser login = requireLogin();
        notice.setIssuerId(login.getStaffId() != null ? login.getStaffId() : login.getUserId());
        notice.setIssuerName(displayName(login));
        notice.setStatus(0);
        notice.setIssueTime(LocalDateTime.now());
        notice.setNoticeNo(nextNoticeNo());
        noticeMapper.insert(notice);
        /* SSE: EMR_QC_NOTICE → 责任科室(经 EmrEventPublisher → EmrEventListener → SseEmitterService.sendToDept) */
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("noticeId", notice.getId());
        payload.put("noticeNo", notice.getNoticeNo());
        payload.put("recordId", record.getId());
        payload.put("deptId", notice.getDeptId());
        payload.put("deptName", notice.getDeptName());
        payload.put("totalDeduct", notice.getTotalDeduct());
        payload.put("requireRectifyDate", notice.getRequireRectifyDate() == null
                ? null : notice.getRequireRectifyDate().toString());
        payload.put("message", "病历质控整改通知单 " + notice.getNoticeNo() + " 已下发(累计扣分"
                + notice.getTotalDeduct() + ", 缺陷" + defectIds.size() + "条)");
        emrEventPublisher.publishToDept(notice.getDeptId(), EmrEventType.EMR_QC_NOTICE, payload);
        logNode(record.getId(), notice.getVisitId(), "整改",
                "质控整改通知单下发: " + notice.getNoticeNo() + " → " + notice.getDeptName()
                        + "(缺陷" + defectIds.size() + "条, 累计扣分" + notice.getTotalDeduct() + ", 要求"
                        + (notice.getRequireRectifyDate() == null ? "未指定" : notice.getRequireRectifyDate().toString())
                        + "前整改)",
                record.getQualityScore(), record.getQualityScore());
        log.info("整改通知单下发: noticeNo={}, recordId={}, dept={}, 缺陷{}条",
                notice.getNoticeNo(), record.getId(), notice.getDeptName(), defectIds.size());
        return notice;
    }

    /** 整改通知单列表(按机构范围隔离: 牵头/超管全院, 成员机构仅本机构科室)。 */
    public List<HisEmrQcNotice> listNotices(Long deptId, Integer status, String startDate, String endDate) {
        Long scopeOrg = readScopeOrg();
        List<Long> scopeDeptIds = null;
        if (scopeOrg != null) {
            List<HisDept> depts = deptMapper.selectList(Wrappers.<HisDept>lambdaQuery()
                    .select(HisDept::getId)
                    .eq(HisDept::getOrgId, scopeOrg));
            scopeDeptIds = new ArrayList<>();
            for (HisDept d : depts) {
                scopeDeptIds.add(d.getId());
            }
            if (scopeDeptIds.isEmpty()) {
                return new ArrayList<>();
            }
        }
        LocalDate start = parseDate(startDate, "开始日期");
        LocalDate end = parseDate(endDate, "结束日期");
        return noticeMapper.selectList(Wrappers.<HisEmrQcNotice>lambdaQuery()
                .eq(deptId != null, HisEmrQcNotice::getDeptId, deptId)
                .in(scopeDeptIds != null, HisEmrQcNotice::getDeptId, scopeDeptIds)
                .eq(status != null, HisEmrQcNotice::getStatus, status)
                .ge(start != null, HisEmrQcNotice::getIssueTime, start == null ? null : start.atStartOfDay())
                .lt(end != null, HisEmrQcNotice::getIssueTime, end == null ? null : end.plusDays(1).atStartOfDay())
                .orderByDesc(HisEmrQcNotice::getIssueTime)
                .orderByDesc(HisEmrQcNotice::getId));
    }

    /* ===================== 整改与申诉闭环 ===================== */

    /** 临床医生整改缺陷: 未整改(0)/申诉驳回(3) → 已整改(1), 记录整改时间与说明。 */
    @Transactional(rollbackFor = Exception.class)
    public void rectifyDefect(Long defectId, String rectifyNote) {
        HisEmrQcDefect exist = requireDefectAccess(defectId);
        int status = exist.getStatus() == null ? 0 : exist.getStatus();
        if (status != 0 && status != 3) {
            throw new BizException(400, "缺陷当前状态为'" + defectStatusText(status)
                    + "', 仅未整改/申诉驳回状态可整改");
        }
        HisEmrQcDefect upd = new HisEmrQcDefect();
        upd.setId(defectId);
        upd.setStatus(1);
        upd.setRectifyTime(LocalDateTime.now());
        if (rectifyNote != null) {
            upd.setRectifyNote(rectifyNote);
        }
        defectMapper.updateById(upd);
        logNode(exist.getRecordId(), exist.getVisitId(), "整改",
                "质控缺陷整改: " + brief(exist)
                        + (StringUtils.hasText(rectifyNote) ? " | 整改说明: " + truncate(rectifyNote, 120) : ""),
                null, null);
    }

    /** 提交整改(通知单级别): 0下发/1已读/2整改中 → 3已整改, rectifyTime=now。 */
    @Transactional(rollbackFor = Exception.class)
    public void submitRectify(Long noticeId) {
        HisEmrQcNotice notice = requireNoticeAccess(noticeId);
        int status = notice.getStatus() == null ? 0 : notice.getStatus();
        if (status != 0 && status != 1 && status != 2) {
            throw new BizException(400, "通知单当前状态为'" + noticeStatusText(status)
                    + "', 仅下发/已读/整改中状态可提交整改");
        }
        HisEmrQcNotice upd = new HisEmrQcNotice();
        upd.setId(noticeId);
        upd.setStatus(3);
        upd.setRectifyTime(LocalDateTime.now());
        noticeMapper.updateById(upd);
        logNode(notice.getRecordId(), notice.getVisitId(), "整改",
                "整改通知单提交整改: " + notice.getNoticeNo(), null, null);
    }

    /**
     * 质控员复核整改: 仅"3已整改"可复核。通过→一次完成"已复核→已关闭"(status=5, reviewResult留痕);
     * 驳回→打回"2整改中"。复核人/复核时间/复核结论回写 review_* 四项。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reviewRectify(Long noticeId, boolean passed, String comment) {
        HisEmrQcNotice notice = requireNoticeAccess(noticeId);
        int status = notice.getStatus() == null ? 0 : notice.getStatus();
        if (status != 3) {
            throw new BizException(400, "通知单当前状态为'" + noticeStatusText(status)
                    + "', 仅已整改状态可复核");
        }
        LoginUser login = requireLogin();
        HisEmrQcNotice upd = new HisEmrQcNotice();
        upd.setId(noticeId);
        upd.setStatus(passed ? 5 : 2);
        upd.setReviewerId(login.getStaffId() != null ? login.getStaffId() : login.getUserId());
        upd.setReviewerName(displayName(login));
        upd.setReviewTime(LocalDateTime.now());
        upd.setReviewResult((passed ? "复核通过, 已复核→已关闭" : "复核驳回, 退回整改中")
                + (StringUtils.hasText(comment) ? ": " + comment : ""));
        noticeMapper.updateById(upd);
        logNode(notice.getRecordId(), notice.getVisitId(), "整改",
                "整改通知单复核" + (passed ? "通过(4已复核→5已关闭)" : "驳回(打回2整改中)") + ": "
                        + notice.getNoticeNo() + (StringUtils.hasText(comment) ? " | " + truncate(comment, 120) : ""),
                null, null);
        log.info("整改通知单复核: noticeNo={}, 结论={}", notice.getNoticeNo(), passed ? "通过" : "驳回");
    }

    /** 申诉: 未关闭通知单(0-4)→6申诉中, 落 appealReason/appealTime(已关闭5/申诉中6 不可再申诉)。 */
    @Transactional(rollbackFor = Exception.class)
    public void appeal(Long noticeId, String reason) {
        HisEmrQcNotice notice = requireNoticeAccess(noticeId);
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "申诉理由不能为空");
        }
        int status = notice.getStatus() == null ? 0 : notice.getStatus();
        if (status == 5) {
            throw new BizException(400, "通知单已关闭, 不可再申诉");
        }
        if (status == 6) {
            throw new BizException(400, "通知单已处于申诉中, 请勿重复提交");
        }
        HisEmrQcNotice upd = new HisEmrQcNotice();
        upd.setId(noticeId);
        upd.setStatus(6);
        upd.setAppealReason(reason);
        upd.setAppealTime(LocalDateTime.now());
        noticeMapper.updateById(upd);
        logNode(notice.getRecordId(), notice.getVisitId(), "申诉",
                "整改通知单申诉: " + notice.getNoticeNo() + " | 理由: " + truncate(reason, 200), null, null);
    }

    /**
     * 处理申诉(仅6申诉中可处理): 通过→5已关闭; 驳回→0重新下发(恢复整改流程)。
     * SSE EMR_QC_APPEAL_RESULT 定向推送责任科室。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleAppeal(Long noticeId, boolean accepted, String comment) {
        HisEmrQcNotice notice = requireNoticeAccess(noticeId);
        int status = notice.getStatus() == null ? 0 : notice.getStatus();
        if (status != 6) {
            throw new BizException(400, "通知单当前状态为'" + noticeStatusText(status)
                    + "', 仅申诉中状态可处理申诉");
        }
        String result = (accepted ? "申诉通过, 通知单关闭" : "申诉驳回, 恢复下发重新整改")
                + (StringUtils.hasText(comment) ? ": " + comment : "");
        HisEmrQcNotice upd = new HisEmrQcNotice();
        upd.setId(noticeId);
        upd.setStatus(accepted ? 5 : 0);
        upd.setAppealResult(result);
        noticeMapper.updateById(upd);
        /* SSE: EMR_QC_APPEAL_RESULT → 责任科室 */
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("noticeId", noticeId);
        payload.put("noticeNo", notice.getNoticeNo());
        payload.put("accepted", accepted);
        payload.put("status", accepted ? 5 : 0);
        payload.put("appealResult", result);
        payload.put("message", "整改通知单 " + notice.getNoticeNo()
                + (accepted ? " 申诉已通过, 通知关闭" : " 申诉被驳回, 请继续整改"));
        emrEventPublisher.publishToDept(notice.getDeptId(), EmrEventType.EMR_QC_APPEAL_RESULT, payload);
        logNode(notice.getRecordId(), notice.getVisitId(), "申诉",
                "整改通知单申诉处理" + (accepted ? "通过(关闭)" : "驳回(重新下发)") + ": " + notice.getNoticeNo()
                        + (StringUtils.hasText(comment) ? " | " + truncate(comment, 120) : ""),
                null, null);
    }

    /* ===================== 进度统计 ===================== */

    /**
     * 质控进度(该月归档口径): 应检数(该月归档病历)/已抽数(人工质控节点去重)/已审数(人工质控完成节点)/
     * 待审数/整改中(通知单0,1,2,6未闭环)/已完成(通知单3,4,5已闭环) + 抽检率/审结率;
     * 全部计数按病历归档月份 COALESCE(出院时间, 记录时间, 创建时间) 过滤, 口径统一。
     */
    public Map<String, Object> qcProgress(String month) {
        LocalDate firstDay = parseMonth(month);
        LocalDateTime start = firstDay.atStartOfDay();
        LocalDateTime end = firstDay.plusMonths(1).atStartOfDay();
        Long tenantId = TenantContext.get();
        Long scopeOrg = readScopeOrg();
        /* 应检数: 该月归档(已签名/已审核)病历数 */
        StringBuilder eligibleSql = new StringBuilder(
                "SELECT COUNT(*) FROM his_inp_medical_record r "
                        + "JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                        + "WHERE r.deleted = 0 AND r.status IN (2, 3) "
                        + "AND COALESCE(v.discharge_date, r.record_time, r.create_time) >= ? "
                        + "AND COALESCE(v.discharge_date, r.record_time, r.create_time) < ? ");
        List<Object> eligibleArgs = new ArrayList<>();
        eligibleArgs.add(start);
        eligibleArgs.add(end);
        if (tenantId != null) {
            eligibleSql.append("AND r.tenant_id = ? ");
            eligibleArgs.add(tenantId);
        }
        if (scopeOrg != null) {
            eligibleSql.append("AND r.org_id = ? ");
            eligibleArgs.add(scopeOrg);
        }
        long eligible = queryCount(eligibleSql.toString(), eligibleArgs);
        long sampled = countManualNodes("人工质控%", start, end, tenantId, scopeOrg);
        long reviewed = countManualNodes("人工质控完成%", start, end, tenantId, scopeOrg);
        long rectifying = countNotices(start, end, tenantId, scopeOrg, true);
        long completed = countNotices(start, end, tenantId, scopeOrg, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", firstDay.toString().substring(0, 7));
        out.put("eligibleCount", eligible);
        out.put("sampledCount", sampled);
        out.put("reviewedCount", reviewed);
        out.put("pendingCount", Math.max(0L, sampled - reviewed));
        out.put("rectifyingCount", rectifying);
        out.put("completedCount", completed);
        out.put("sampleRate", rate(sampled, eligible));
        out.put("reviewRate", rate(reviewed, sampled));
        return out;
    }

    /**
     * 人工质控节点去重计数(按 record_id DISTINCT): 与"应检数"同口径, 按病历归档月份
     * COALESCE(出院时间, 记录时间, 创建时间) 过滤, 保证"抽检率=已抽/应检"语义一致。
     */
    private long countManualNodes(String descLike, LocalDateTime start, LocalDateTime end,
                                  Long tenantId, Long scopeOrg) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(DISTINCT n.record_id) FROM his_emr_qc_node n "
                        + "JOIN his_inp_medical_record r ON n.record_id = r.id AND r.deleted = 0 "
                        + "LEFT JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                        + "WHERE n.node_desc LIKE ? "
                        + "AND COALESCE(v.discharge_date, r.record_time, r.create_time) >= ? "
                        + "AND COALESCE(v.discharge_date, r.record_time, r.create_time) < ? ");
        List<Object> args = new ArrayList<>();
        args.add(descLike);
        args.add(start);
        args.add(end);
        if (tenantId != null) {
            sql.append("AND n.tenant_id = ? ");
            args.add(tenantId);
        }
        if (scopeOrg != null) {
            sql.append("AND r.org_id = ? ");
            args.add(scopeOrg);
        }
        return queryCount(sql.toString(), args);
    }

    /**
     * 通知单计数: rectifying=true 未闭环(0,1,2,6), false 已闭环(3,4,5);
     * 与"应检数"同口径按病历归档月份 COALESCE(出院时间, 记录时间, 创建时间) 过滤。
     * 说明: 通知单 record_id 由下发流程强制必填(见 issueNotice)。
     */
    private long countNotices(LocalDateTime start, LocalDateTime end, Long tenantId, Long scopeOrg, boolean rectifying) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM his_emr_qc_notice n "
                        + "JOIN his_inp_medical_record r ON n.record_id = r.id AND r.deleted = 0 "
                        + "LEFT JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                        + "WHERE n.deleted = 0 "
                        + "AND COALESCE(v.discharge_date, r.record_time, r.create_time) >= ? "
                        + "AND COALESCE(v.discharge_date, r.record_time, r.create_time) < ? ");
        List<Object> args = new ArrayList<>();
        args.add(start);
        args.add(end);
        sql.append(rectifying ? "AND n.status IN (0, 1, 2, 6) " : "AND n.status IN (3, 4, 5) ");
        if (tenantId != null) {
            sql.append("AND n.tenant_id = ? ");
            args.add(tenantId);
        }
        if (scopeOrg != null) {
            sql.append("AND r.org_id = ? ");
            args.add(scopeOrg);
        }
        return queryCount(sql.toString(), args);
    }

    private long queryCount(String sql, List<Object> args) {
        Long v = jdbcTemplate.queryForObject(sql, args.toArray(), Long.class);
        return v == null ? 0L : v;
    }

    /** 百分比(1位小数); 分母为0返回0 */
    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(1, RoundingMode.HALF_UP);
    }

    /* ===================== 权限与校验 ===================== */

    /** 病历必读: 存在 + 归属机构与登录机构一致(平台超管放行)。 */
    private HisInpMedicalRecord requireRecordAccess(Long recordId) {
        if (recordId == null) {
            throw new BizException(400, "病历记录ID不能为空");
        }
        HisInpMedicalRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(404, "病历记录不存在");
        }
        requireSameOrg(record.getOrgId(), "该病历不属于当前登录机构, 无权操作");
        return record;
    }

    /** 缺陷必读: 存在 + 关联病历归属机构一致。 */
    private HisEmrQcDefect requireDefectAccess(Long defectId) {
        if (defectId == null) {
            throw new BizException(400, "缺陷ID不能为空");
        }
        HisEmrQcDefect defect = defectMapper.selectById(defectId);
        if (defect == null) {
            throw new BizException(404, "缺陷不存在");
        }
        HisInpMedicalRecord record = defect.getRecordId() == null ? null : recordMapper.selectById(defect.getRecordId());
        requireSameOrg(record == null ? null : record.getOrgId(), "该缺陷不属于当前登录机构, 无权操作");
        return defect;
    }

    /** 通知单必读: 存在 + 按归属病历(缺省按责任科室)的机构校验。 */
    private HisEmrQcNotice requireNoticeAccess(Long noticeId) {
        if (noticeId == null) {
            throw new BizException(400, "通知单ID不能为空");
        }
        HisEmrQcNotice notice = noticeMapper.selectById(noticeId);
        if (notice == null) {
            throw new BizException(404, "整改通知单不存在");
        }
        Long orgId = null;
        if (notice.getRecordId() != null) {
            HisInpMedicalRecord record = recordMapper.selectById(notice.getRecordId());
            orgId = record == null ? null : record.getOrgId();
        }
        if (orgId == null && notice.getDeptId() != null) {
            HisDept dept = deptMapper.selectById(notice.getDeptId());
            orgId = dept == null ? null : dept.getOrgId();
        }
        requireSameOrg(orgId, "该通知单不属于当前登录机构, 无权操作");
        return notice;
    }

    /** 写守卫: 记录归属机构须与当前登录机构一致; 平台超管放行(沿袭护士站 requireSameOrg 模式)。 */
    private void requireSameOrg(Long orgId, String msg) {
        LoginUser u = requireLogin();
        if (u.hasRole(Roles.SUPER_ADMIN)) {
            return;
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法进行人工质控操作");
        }
        if (orgId != null && !orgId.equals(u.getOrgId())) {
            throw new BizException(403, msg);
        }
    }

    /**
     * 读隔离范围: 牵头机构/平台超管返回 null(全院可见), 成员机构锁定本机构;
     * 未归属机构且非牵头/非超管的账号直接 403, 防"scope=null 视为全院"口径漏洞。
     */
    private Long readScopeOrg() {
        LoginUser u = UserContext.get();
        Long scope = guard.scopeOrgId(null);
        if (scope == null && u != null && !u.hasRole(Roles.SUPER_ADMIN)
                && !guard.isLead(u) && u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法进行质控查询");
        }
        return scope;
    }

    private LoginUser requireLogin() {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        return u;
    }

    /* ===================== 节点留痕与工具 ===================== */

    /** 质控节点留痕(由元对象处理器填充 create_time; operator=当前登录, 职工ID优先) */
    private void logNode(Long recordId, Long visitId, String nodeType, String nodeDesc,
                         BigDecimal before, BigDecimal after) {
        HisEmrQcNode node = new HisEmrQcNode();
        node.setRecordId(recordId);
        node.setVisitId(visitId);
        node.setNodeType(nodeType);
        node.setNodeDesc(truncate(nodeDesc, 500));
        node.setScoreBefore(before);
        node.setScoreAfter(after);
        LoginUser u = UserContext.get();
        node.setOperatorId(u == null ? null : (u.getStaffId() != null ? u.getStaffId() : u.getUserId()));
        node.setOperatorName(u == null ? "system" : displayName(u));
        nodeMapper.insert(node);
    }

    /** 病历摘要(审核工作台抬头) */
    private Map<String, Object> loadRecordBrief(HisInpMedicalRecord record, HisInpVisit visit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordId", record.getId());
        m.put("visitId", record.getInpVisitId());
        m.put("recordType", record.getRecordType());
        m.put("typeLabel", typeLabel(record.getRecordType()));
        m.put("title", record.getTitle());
        m.put("status", record.getStatus());
        m.put("statusLabel", RECORD_STATUS_TEXT.getOrDefault(record.getStatus(), "-"));
        m.put("recordTime", record.getRecordTime() == null ? null : record.getRecordTime().format(DT_FMT));
        m.put("deadlineTime", record.getDeadlineTime() == null ? null : record.getDeadlineTime().format(DT_FMT));
        m.put("qualityScore", record.getQualityScore());
        m.put("doctorId", record.getDoctorId());
        if (record.getDoctorId() != null) {
            HisStaff doc = staffMapper.selectById(record.getDoctorId());
            m.put("doctorName", doc == null ? null : doc.getStaffName());
        }
        if (visit != null) {
            m.put("patientId", visit.getPatientId());
            m.put("inpNo", visit.getInpNo());
            m.put("deptId", visit.getDeptId());
            m.put("visitStatus", visit.getVisitStatus());
            m.put("admitDate", visit.getAdmitDate() == null ? null : visit.getAdmitDate().format(DT_FMT));
            m.put("dischargeDate", visit.getDischargeDate() == null ? null : visit.getDischargeDate().format(DT_FMT));
            if (visit.getPatientId() != null) {
                HisPatient p = patientMapper.selectById(visit.getPatientId());
                m.put("patientName", p == null ? null : p.getName());
            }
            if (visit.getDeptId() != null) {
                HisDept dept = deptMapper.selectById(visit.getDeptId());
                m.put("deptName", dept == null ? null : dept.getDeptName());
            }
        }
        return m;
    }

    /** 通知单编号: QC-yyyyMMdd-序号(当天count+1, 复用当日/历史编号前缀查重) */
    private String nextNoticeNo() {
        String prefix = "QC-" + LocalDate.now().format(NO_DATE_FMT) + "-";
        Long count = noticeMapper.selectCount(Wrappers.<HisEmrQcNotice>lambdaQuery()
                .likeRight(HisEmrQcNotice::getNoticeNo, prefix));
        return prefix + ((count == null ? 0L : count) + 1);
    }

    /** 解析缺陷ID列表(支持 JSON 数组字符串与逗号串; 雪花ID字符串/数字均可) */
    private List<Long> parseDefectIds(String raw) {
        List<Long> ids = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return ids;
        }
        String s = raw.trim();
        if (s.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(s);
                for (int i = 0; i < arr.size(); i++) {
                    Object v = arr.get(i);
                    if (v != null) {
                        ids.add(Long.valueOf(String.valueOf(v)));
                    }
                }
                return ids;
            } catch (Exception e) {
                ids.clear();
                log.warn("缺陷ID JSON解析失败, 降级按逗号串解析: {}", e.getMessage());
            }
        }
        for (String part : s.replace("[", "").replace("]", "").split(",")) {
            String p = part.trim().replace("\"", "");
            if (StringUtils.hasText(p)) {
                try {
                    ids.add(Long.valueOf(p));
                } catch (NumberFormatException ignore) {
                    // 忽略无效片段
                }
            }
        }
        return ids;
    }

    /** 月份解析(yyyy-MM; 空=当月) */
    private LocalDate parseMonth(String month) {
        if (!StringUtils.hasText(month)) {
            return LocalDate.now().withDayOfMonth(1);
        }
        try {
            return LocalDate.parse(month.trim() + "-01");
        } catch (Exception e) {
            throw new BizException(400, "月份格式须为 yyyy-MM");
        }
    }

    /** 日期解析(yyyy-MM-dd; 空=null) */
    private LocalDate parseDate(String raw, String label) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            throw new BizException(400, label + "格式须为 yyyy-MM-dd");
        }
    }

    private void validateSeverity(Integer severity) {
        if (severity != null && (severity < 1 || severity > 3)) {
            throw new BizException(400, "严重程度须为1提醒/2扣分/3一票否决");
        }
    }

    /** 质控员姓名解析(sys_user 优先, 兜底 ID 展示) */
    private String resolveUserName(Long userId) {
        try {
            SysUser u = userMapper.selectById(userId);
            if (u != null) {
                if (StringUtils.hasText(u.getRealName())) {
                    return u.getRealName();
                }
                if (StringUtils.hasText(u.getUsername())) {
                    return u.getUsername();
                }
            }
        } catch (Exception e) {
            log.warn("质控员姓名解析失败: userId={}, {}", userId, e.getMessage());
        }
        return "用户ID:" + userId;
    }

    private static String displayName(LoginUser u) {
        if (u == null) {
            return "system";
        }
        if (StringUtils.hasText(u.getRealName())) {
            return u.getRealName();
        }
        return StringUtils.hasText(u.getUsername()) ? u.getUsername() : "system";
    }

    private static boolean isAuto(HisEmrQcDefect d) {
        return d.getAutoGenerated() != null && d.getAutoGenerated() == 1;
    }

    private static String brief(HisEmrQcDefect d) {
        String s = StringUtils.hasText(d.getDefectDesc()) ? d.getDefectDesc() : d.getRuleName();
        return truncate(s == null ? "缺陷" : s, 100);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private static String typeLabel(Integer recordType) {
        return RECORD_TYPE_LABELS.getOrDefault(recordType, "病历");
    }

    private static String defectStatusText(Integer status) {
        return DEFECT_STATUS_TEXT.getOrDefault(status, "-");
    }

    private static String noticeStatusText(Integer status) {
        return NOTICE_STATUS_TEXT.getOrDefault(status, "-");
    }

    private static Map<Integer, String> statusText(Object... kv) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((Integer) kv[i], (String) kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }
}
