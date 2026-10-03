package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.emr.HisEmrQcNode;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.emr.HisEmrQcNodeMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SysTenantService;
import com.yb.hi.service.inpatient.EmrQualityService;
import com.yb.hi.service.inpatient.EmrVersionService;
import com.yb.hi.service.inpatient.PdfExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 病历归档流程引擎(P7a-2): 住院病历"归档 → 召回 → 审批 → 封存/解封"全生命周期状态机。
 *
 * 状态语义(与 his_inp_medical_record.status 口径一致):
 * 1草稿 / 2已提交 / 3已审核 / 4已归档 / 5召回中 / 6已封存。
 *
 * 状态机流转(全部乐观更新: UPDATE ... WHERE id=? AND status=旧值, 影响行数为 0 即状态已被并发修改):
 * - 归档: submitForArchive 3→4(须已出院), batchArchive 批量(单条失败不阻断);
 *   归档副作用(best-effort, 失败仅告警不影响归档): 版本快照(EmrVersionService, operate_type='归档')、
 *   异步生成 PDF(PdfExportService, 落 {upload.path}/emr-archive/{yyyyMM}/, 回填 pdf_path/pdf_generated_time)、
 *   归档级内涵质控(EmrQualityService.evaluateContent stage=2, 已有自动缺陷则跳过防重复累加)、
 *   审计留痕(his_emr_audit_log)与质控节点(his_emr_qc_node node_type='归档')、SSE 通知记录医生(RECORD_ARCHIVED);
 * - 召回: requestRecall 4→5(原因必填, recall_approved=0), SSE 通知病案科(按科室名匹配"病案"定向, 未命中广播);
 *   approveRecall 5→3(通过返修, recall_approved=1)/5→4(驳回, recall_approved=2), SSE 通知召回申请人(按姓名反查用户);
 * - 封存: seal 4→6(原因必填), 事件 RECORD_SEALED; unseal 6→4(清空封存三列), 事件 RECORD_UNSEALED。
 *
 * 查询与统计: 列表用 MyBatis-Plus 条件分页(机构范围 readScopeOrg 收口); 逾期清单与归档统计用
 * JdbcTemplate 联查(his_inp_visit/his_patient/his_dept/his_staff), 显式携带 tenant_id 与 org 范围,
 * 逾期口径=出院超 3 天且 status<4。定时任务 checkArchiveTimeout 每小时遍历租户(参考 EmrTimelinessService
 * 租户扫描模式: 逐租户设 TenantContext→扫描→finally 恢复)向记录医生推送归档超时提醒。
 *
 * 权限: 写操作 requireSameOrg(记录归属机构须与登录机构一致, 平台超管放行); 读操作按 org 范围隔离。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 全部显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class EmrArchiveService {

    /** 时间展示格式(通知/列表) */
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 月份目录格式(yyyyMM) */
    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    /** 趋势月份键格式(yyyy-MM) */
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 逾期未归档判定: 出院超过该天数仍未归档(出院时间 < NOW() - 3 DAY) */
    private static final int OVERDUE_DAYS = 3;

    /** 列表分页上限(防全量拉取) */
    private static final int MAX_PAGE_SIZE = 200;

    /** 归档完成时限(小时): 出院后 72h 内归档视为及时 */
    private static final int ARCHIVE_SLA_HOURS = 72;

    /**
     * 逾期未归档联查基础 SQL(明细与定时扫描共用):
     * status<4(未归档) 且已出院且出院超过 OVERDUE_DAYS 天; 出院时间升序(逾期最久在前)。
     */
    private static final String OVERDUE_SQL_BASE =
            "SELECT r.id AS recordId, r.record_type AS recordType, r.title, r.doctor_id AS doctorId, "
                    + "r.record_time AS recordTime, r.quality_score AS qualityScore, "
                    + "v.id AS visitId, v.inp_no AS inpNo, v.dept_id AS deptId, v.discharge_date AS dischargeDate, "
                    + "v.doctor_id AS visitDoctorId, p.name AS patientName, d.dept_name AS deptName, "
                    + "s.staff_name AS doctorName "
                    + "FROM his_inp_medical_record r "
                    + "JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                    + "LEFT JOIN his_patient p ON v.patient_id = p.id "
                    + "LEFT JOIN his_dept d ON v.dept_id = d.id "
                    + "LEFT JOIN his_staff s ON r.doctor_id = s.id "
                    + "WHERE r.deleted = 0 AND r.status < 4 AND v.discharge_date IS NOT NULL "
                    + "AND v.discharge_date < DATE_SUB(NOW(), INTERVAL " + OVERDUE_DAYS + " DAY) ";

    private final HisInpMedicalRecordMapper recordMapper;
    private final HisInpVisitMapper visitMapper;
    private final JdbcTemplate jdbcTemplate;
    private final EmrEventPublisher eventPublisher;
    private final OrgAccessGuard guard;
    private final SysTenantService tenantService;
    private final HisEmrQcNodeMapper nodeMapper;
    private final EmrAuditService auditService;
    /** 自注入代理: @Async PDF 生成须经代理调用才异步(同类内直调不走代理, 参考 EmrTemplateService.selfProvider) */
    private final ObjectProvider<EmrArchiveService> selfProvider;

    /** 版本快照服务(P7a-1 已存在; required=false 防御并行会话/未就绪场景) */
    @Autowired(required = false)
    private EmrVersionService versionService;

    /** PDF 导出服务(P7a-3 将扩展; 本任务仅调用现有 generatePdf 入口, best-effort) */
    @Autowired(required = false)
    private PdfExportService pdfExportService;

    /** 内涵质控引擎(归档级 stage=2 触发入口) */
    @Autowired(required = false)
    private EmrQualityService qualityService;

    /** 病历文档服务(Tiptap 密文解密, 归档 PDF 正文还原用) */
    @Autowired(required = false)
    private EmrDocumentService documentService;

    @Value("${his.upload.path:./data/upload/}")
    private String uploadPath;

    @Value("${his.upload.url-prefix:/uploads/}")
    private String urlPrefix;

    public EmrArchiveService(HisInpMedicalRecordMapper recordMapper, HisInpVisitMapper visitMapper,
                             JdbcTemplate jdbcTemplate, EmrEventPublisher eventPublisher,
                             OrgAccessGuard guard, SysTenantService tenantService,
                             HisEmrQcNodeMapper nodeMapper, EmrAuditService auditService,
                             ObjectProvider<EmrArchiveService> selfProvider) {
        this.recordMapper = recordMapper;
        this.visitMapper = visitMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.guard = guard;
        this.tenantService = tenantService;
        this.nodeMapper = nodeMapper;
        this.auditService = auditService;
        this.selfProvider = selfProvider;
    }

    /* ==================== 归档状态机 ==================== */

    /**
     * 提交归档: 3(已审核) → 4(已归档)。
     * 校验: status 必须为 3, 就诊存在且已有出院时间(未出院不可归档);
     * 副作用(best-effort, 均不影响归档主流程): 版本快照、异步 PDF、归档级质控、审计/节点留痕、SSE 通知。
     */
    public HisInpMedicalRecord submitForArchive(Long recordId) {
        HisInpMedicalRecord record = requireRecordAccess(recordId);
        if (record.getStatus() == null || record.getStatus() != 3) {
            throw new BizException(400, "仅已审核(3)状态的病历可归档, 当前状态: " + statusLabel(record.getStatus()));
        }
        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        if (visit == null) {
            throw new BizException(400, "住院就诊记录不存在, 无法归档");
        }
        if (visit.getDischargeDate() == null) {
            throw new BizException(400, "患者尚未办理出院, 不可归档");
        }

        LoginUser u = requireLogin();
        String operatorName = truncate(displayName(u), 50);
        LocalDateTime now = LocalDateTime.now();
        int affected = recordMapper.update(null, new LambdaUpdateWrapper<HisInpMedicalRecord>()
                .set(HisInpMedicalRecord::getStatus, 4)
                .set(HisInpMedicalRecord::getArchiveTime, now)
                .set(HisInpMedicalRecord::getArchiveBy, operatorName)
                .set(HisInpMedicalRecord::getUpdateTime, now)
                .eq(HisInpMedicalRecord::getId, recordId)
                .eq(HisInpMedicalRecord::getStatus, 3));
        if (affected == 0) {
            throw new BizException("病历状态已变化, 请刷新后重试");
        }

        /* 1. 版本快照: 归档不修改正文, 快照即归档版本基线 */
        snapshotQuietly(recordId, "归档", u);
        /* 2. 异步生成 PDF(落盘后回填 pdf_path/pdf_generated_time) */
        submitPdfGenerationQuietly(recordId);
        /* 3. 归档级内涵质控(已有自动缺陷则跳过, 防重复累加) */
        evaluateArchiveQcQuietly(recordId);
        /* 4. 审计与质控节点留痕 */
        auditQuietly(recordId, "ARCHIVE", "病历归档: 操作人=" + operatorName + ", 归档时间=" + now.format(DT_FMT));
        logNodeQuietly(record, "归档", "病历归档完成: 操作人" + operatorName
                + ", 患者出院时间" + (visit.getDischargeDate() == null ? "-" : visit.getDischargeDate().format(DT_FMT))
                + ", 归档时间" + now.format(DT_FMT));

        HisInpMedicalRecord updated = recordMapper.selectById(recordId);
        publishToDoctorQuietly(updated == null ? record : updated, visit, EmrEventType.RECORD_ARCHIVED,
                "病历《" + text(record.getTitle()) + "》已完成归档"
                        + (updated != null && updated.getArchiveTime() != null
                        ? "(" + updated.getArchiveTime().format(DT_FMT) + ")" : ""));
        log.info("病历归档完成: recordId={}, visitId={}, operator={}", recordId, record.getInpVisitId(), operatorName);
        return sameRecord(updated, record);
    }

    /** 批量归档: 逐条调用 submitForArchive, 单条失败不阻断(仅告警), 返回成功条数。 */
    public int batchArchive(List<Long> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            throw new BizException(400, "请至少选择一份病历进行归档");
        }
        int success = 0;
        for (Long id : recordIds) {
            if (id == null) {
                continue;
            }
            try {
                submitForArchive(id);
                success++;
            } catch (Exception e) {
                log.warn("批量归档单条失败(不阻断): recordId={}, err={}", id, e.getMessage());
            }
        }
        log.info("批量归档完成: 共{}份, 成功{}份", recordIds.size(), success);
        return success;
    }

    /**
     * 召回申请: 4(已归档) → 5(召回中)。原因必填; recall_approved 置 0 待审批。
     * SSE: 通知病案科(按科室名含"病案"定向推送, 未匹配到则广播)。
     */
    public HisInpMedicalRecord requestRecall(Long recordId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "召回原因不能为空");
        }
        HisInpMedicalRecord record = requireRecordAccess(recordId);
        if (record.getStatus() == null || record.getStatus() != 4) {
            throw new BizException(400, "仅已归档(4)状态的病历可申请召回, 当前状态: " + statusLabel(record.getStatus()));
        }
        LoginUser u = requireLogin();
        String operatorName = truncate(displayName(u), 50);
        String trimmedReason = truncate(reason.trim(), 500);
        LocalDateTime now = LocalDateTime.now();
        int affected = recordMapper.update(null, new LambdaUpdateWrapper<HisInpMedicalRecord>()
                .set(HisInpMedicalRecord::getStatus, 5)
                .set(HisInpMedicalRecord::getRecallTime, now)
                .set(HisInpMedicalRecord::getRecallBy, operatorName)
                .set(HisInpMedicalRecord::getRecallReason, trimmedReason)
                .set(HisInpMedicalRecord::getRecallApproved, 0)
                .set(HisInpMedicalRecord::getUpdateTime, now)
                .eq(HisInpMedicalRecord::getId, recordId)
                .eq(HisInpMedicalRecord::getStatus, 4));
        if (affected == 0) {
            throw new BizException("病历状态已变化, 请刷新后重试");
        }

        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        auditQuietly(recordId, "RECALL_REQUEST", "召回申请: 申请人=" + operatorName + ", 原因=" + trimmedReason);
        logNodeQuietly(record, "召回", "病历召回申请(待审批): 申请人" + operatorName + ", 原因: " + trimmedReason);

        Map<String, Object> data = eventData(record, visit, "病历《" + text(record.getTitle())
                + "》已发起召回申请, 待病案科审批(申请人: " + operatorName + ", 原因: " + trimmedReason + ")");
        notifyRecordDeptQuietly(EmrEventType.RECORD_RECALLED, data);
        log.info("病历召回申请: recordId={}, applicant={}, reason={}", recordId, operatorName, trimmedReason);
        return sameRecord(recordMapper.selectById(recordId), record);
    }

    /**
     * 审批召回(仅 5召回中 且 recall_approved=0 待审批):
     * approved=true → 3(已审核, 返修), recall_approved=1;
     * approved=false → 4(已归档, 驳回), recall_approved=2; 均记录审批人。
     * SSE: 通知召回申请人(按 recall_by 姓名反查用户定向, 反查失败广播)。
     */
    public HisInpMedicalRecord approveRecall(Long recordId, boolean approved) {
        HisInpMedicalRecord record = requireRecordAccess(recordId);
        if (record.getStatus() == null || record.getStatus() != 5
                || record.getRecallApproved() == null || record.getRecallApproved() != 0) {
            throw new BizException(400, "仅召回中(5)且待审批(recall_approved=0)的病历可审批, 当前状态: "
                    + statusLabel(record.getStatus())
                    + (record.getRecallApproved() == null ? "" : "(审批状态: " + record.getRecallApproved() + ")"));
        }
        LoginUser u = requireLogin();
        String operatorName = truncate(displayName(u), 50);
        LocalDateTime now = LocalDateTime.now();
        int affected = recordMapper.update(null, new LambdaUpdateWrapper<HisInpMedicalRecord>()
                .set(HisInpMedicalRecord::getStatus, approved ? 3 : 4)
                .set(HisInpMedicalRecord::getRecallApproved, approved ? 1 : 2)
                .set(HisInpMedicalRecord::getRecallApprover, operatorName)
                .set(HisInpMedicalRecord::getUpdateTime, now)
                .eq(HisInpMedicalRecord::getId, recordId)
                .eq(HisInpMedicalRecord::getStatus, 5)
                .eq(HisInpMedicalRecord::getRecallApproved, 0));
        if (affected == 0) {
            throw new BizException("病历状态已变化, 请刷新后重试");
        }

        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        String result = approved ? "通过(退回返修)" : "驳回(维持归档)";
        auditQuietly(recordId, "RECALL_APPROVE", "召回审批: 审批人=" + operatorName + ", 结果=" + result);
        logNodeQuietly(record, "召回", "病历召回审批: 审批人" + operatorName + ", 结果" + result
                + ", 原申请人=" + text(record.getRecallBy()));

        Map<String, Object> data = eventData(record, visit, "病历《" + text(record.getTitle()) + "》召回申请"
                + (approved ? "已通过, 病历退回已审核状态, 修改后请重新归档" : "已被驳回, 病历维持归档状态")
                + "(审批人: " + operatorName + ")");
        Long applicantUserId = resolveUserIdByName(record.getRecallBy());
        publishSafely(applicantUserId, EmrEventType.RECORD_RECALLED, data);
        log.info("病历召回审批: recordId={}, approved={}, approver={}", recordId, approved, operatorName);
        return sameRecord(recordMapper.selectById(recordId), record);
    }

    /** 封存: 4(已归档) → 6(已封存), 原因必填(医疗纠纷争议固定证据)。事件 RECORD_SEALED。 */
    public HisInpMedicalRecord seal(Long recordId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "封存原因不能为空");
        }
        HisInpMedicalRecord record = requireRecordAccess(recordId);
        if (record.getStatus() == null || record.getStatus() != 4) {
            throw new BizException(400, "仅已归档(4)状态的病历可封存, 当前状态: " + statusLabel(record.getStatus()));
        }
        LoginUser u = requireLogin();
        String operatorName = truncate(displayName(u), 50);
        String trimmedReason = truncate(reason.trim(), 500);
        LocalDateTime now = LocalDateTime.now();
        int affected = recordMapper.update(null, new LambdaUpdateWrapper<HisInpMedicalRecord>()
                .set(HisInpMedicalRecord::getStatus, 6)
                .set(HisInpMedicalRecord::getSealTime, now)
                .set(HisInpMedicalRecord::getSealBy, operatorName)
                .set(HisInpMedicalRecord::getSealReason, trimmedReason)
                .set(HisInpMedicalRecord::getUpdateTime, now)
                .eq(HisInpMedicalRecord::getId, recordId)
                .eq(HisInpMedicalRecord::getStatus, 4));
        if (affected == 0) {
            throw new BizException("病历状态已变化, 请刷新后重试");
        }

        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        auditQuietly(recordId, "SEAL", "病历封存: 操作人=" + operatorName + ", 原因=" + trimmedReason);
        logNodeQuietly(record, "封存", "病历封存: 操作人" + operatorName + ", 原因: " + trimmedReason);
        publishToDoctorQuietly(record, visit, EmrEventType.RECORD_SEALED,
                "病历《" + text(record.getTitle()) + "》已封存(原因: " + trimmedReason + ")");
        log.info("病历封存: recordId={}, operator={}", recordId, operatorName);
        return sameRecord(recordMapper.selectById(recordId), record);
    }

    /** 解封: 6(已封存) → 4(已归档), 清空封存时间/操作人/原因。事件 RECORD_UNSEALED。 */
    public HisInpMedicalRecord unseal(Long recordId) {
        HisInpMedicalRecord record = requireRecordAccess(recordId);
        if (record.getStatus() == null || record.getStatus() != 6) {
            throw new BizException(400, "仅已封存(6)状态的病历可解封, 当前状态: " + statusLabel(record.getStatus()));
        }
        LoginUser u = requireLogin();
        String operatorName = truncate(displayName(u), 50);
        LocalDateTime now = LocalDateTime.now();
        int affected = recordMapper.update(null, new LambdaUpdateWrapper<HisInpMedicalRecord>()
                .set(HisInpMedicalRecord::getStatus, 4)
                .set(HisInpMedicalRecord::getSealTime, null)
                .set(HisInpMedicalRecord::getSealBy, null)
                .set(HisInpMedicalRecord::getSealReason, null)
                .set(HisInpMedicalRecord::getUpdateTime, now)
                .eq(HisInpMedicalRecord::getId, recordId)
                .eq(HisInpMedicalRecord::getStatus, 6));
        if (affected == 0) {
            throw new BizException("病历状态已变化, 请刷新后重试");
        }

        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        auditQuietly(recordId, "UNSEAL", "病历解封: 操作人=" + operatorName);
        logNodeQuietly(record, "解封", "病历解封: 操作人" + operatorName);
        publishToDoctorQuietly(record, visit, EmrEventType.RECORD_UNSEALED,
                "病历《" + text(record.getTitle()) + "》已解封, 恢复为已归档状态");
        log.info("病历解封: recordId={}, operator={}", recordId, operatorName);
        return sameRecord(recordMapper.selectById(recordId), record);
    }

    /**
     * 归档催促(P7a-5 联调补齐): 向记录医生 SSE 推送归档提醒(走 QC_REMINDER 质控提醒通道)。
     * 未归档(status&lt;4)病历均可催; 已归档/封存后再催无意义, 明确报 400。推送 best-effort 不阻断,
     * 操作审计留痕。
     */
    public void urge(Long recordId) {
        HisInpMedicalRecord record = requireRecordAccess(recordId);
        if (record.getStatus() != null && record.getStatus() >= 4) {
            throw new BizException(400, "病历已归档或封存, 无需催促归档");
        }
        LoginUser u = requireLogin();
        String operatorName = truncate(displayName(u), 50);
        HisInpVisit visit = record.getInpVisitId() == null ? null : visitMapper.selectById(record.getInpVisitId());
        publishToDoctorQuietly(record, visit, EmrEventType.QC_REMINDER,
                "病案科归档催促: 病历《" + text(record.getTitle()) + "》尚未归档, 请尽快完成书写并提交归档");
        auditQuietly(recordId, "URGE", "归档催促: 操作人=" + operatorName);
        log.info("病历归档催促: recordId={}, operator={}", recordId, operatorName);
    }

    /* ==================== 查询 ==================== */

    /**
     * 归档列表(多条件分页): status/医生姓名(模糊)/科室/归档时间区间 可选筛选,
     * 机构范围由 readScopeOrg 收口(牵头/超管全院, 成员机构仅本机构); 归档时间倒序。
     */
    public IPage<HisInpMedicalRecord> list(Long deptId, Integer status, String doctorName,
                                           String startDate, String endDate, int page, int size) {
        Long tenantId = TenantContext.require();
        Long scope = readScopeOrg();
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        LambdaQueryWrapper<HisInpMedicalRecord> qw = new LambdaQueryWrapper<>();
        qw.eq(status != null, HisInpMedicalRecord::getStatus, status);
        qw.eq(scope != null, HisInpMedicalRecord::getOrgId, scope);
        if (deptId != null) {
            List<Long> visitIds = jdbcTemplate.queryForList(
                    "SELECT id FROM his_inp_visit WHERE dept_id = ? AND tenant_id = ? AND deleted = 0",
                    Long.class, deptId, tenantId);
            if (visitIds.isEmpty()) {
                return new Page<>(p, s);
            }
            qw.in(HisInpMedicalRecord::getInpVisitId, visitIds);
        }
        if (StringUtils.hasText(doctorName)) {
            List<Long> doctorIds = jdbcTemplate.queryForList(
                    "SELECT id FROM his_staff WHERE staff_name LIKE ? AND tenant_id = ? AND deleted = 0",
                    Long.class, "%" + doctorName.trim() + "%", tenantId);
            if (doctorIds.isEmpty()) {
                return new Page<>(p, s);
            }
            qw.in(HisInpMedicalRecord::getDoctorId, doctorIds);
        }
        LocalDateTime from = parseDayStart(startDate);
        LocalDateTime to = parseDayEnd(endDate);
        qw.ge(from != null, HisInpMedicalRecord::getArchiveTime, from);
        qw.le(to != null, HisInpMedicalRecord::getArchiveTime, to);
        qw.orderByDesc(HisInpMedicalRecord::getArchiveTime).orderByDesc(HisInpMedicalRecord::getId);
        return recordMapper.selectPage(new Page<>(p, s), qw);
    }

    /**
     * 逾期未归档清单(出院超 {@link #OVERDUE_DAYS} 天且 status<4):
     * 返回 recordId/recordType/typeLabel/title/patientName/inpNo/deptId/deptName/doctorId/doctorName/
     * dischargeTime(yyyy-MM-dd HH:mm)/overdueDays; 出院时间升序。机构范围与 deptId 可选过滤。
     */
    public List<Map<String, Object>> overdueList(Long deptId) {
        Long tenantId = TenantContext.require();
        Long scope = readScopeOrg();
        StringBuilder sql = new StringBuilder(OVERDUE_SQL_BASE);
        List<Object> params = new ArrayList<>();
        sql.append(" AND r.tenant_id = ? ");
        params.add(tenantId);
        if (scope != null) {
            sql.append(" AND r.org_id = ? ");
            params.add(scope);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ? ");
            params.add(deptId);
        }
        sql.append(" ORDER BY v.discharge_date ASC, r.id ASC");
        return jdbcTemplate.query(sql.toString(), (rs, i) -> overdueRow(rs), params.toArray());
    }

    /** 单条详情(存在 + 机构归属校验)。 */
    public HisInpMedicalRecord detail(Long recordId) {
        return requireRecordAccess(recordId);
    }

    /* ==================== 统计 ==================== */

    /**
     * 归档统计(显式 tenant_id + 机构范围; startDate/endDate 作用于归档时间区间口径):
     * - totalArchived: 已归档总数(status>=4, 区间内);
     * - submissionRate72h: 出院后 72h 内归档占比(分母=区间内已归档且有出院时间);
     * - returnRate: 有召回记录且审批通过(recall_approved=1)的占比;
     * - monthlyArchived: 本月归档数(固定当月口径, 不受区间影响);
     * - overdueCount: 逾期未归档数(当前口径);
     * - deptProgress: 按科室归档进度 top10 {deptId, deptName, archived, total, rate}(累计口径);
     * - monthlyTrend: 近6月归档数趋势 [{month, count}](固定口径, 空月补0)。
     */
    public Map<String, Object> archiveStats(Long deptId, String startDate, String endDate) {
        Long tenantId = TenantContext.require();
        Long scope = readScopeOrg();
        LocalDateTime from = parseDayStart(startDate);
        LocalDateTime to = parseDayEnd(endDate);

        Map<String, Object> out = new LinkedHashMap<>();

        /* 1. 归档汇总(区间口径: total / 72h 内归档 / 有出院时间的基数 / 召回通过数) */
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS total, "
                        + "SUM(CASE WHEN r.archive_time IS NOT NULL AND v.discharge_date IS NOT NULL "
                        + "  AND TIMESTAMPDIFF(HOUR, v.discharge_date, r.archive_time) BETWEEN 0 AND "
                        + ARCHIVE_SLA_HOURS + " THEN 1 ELSE 0 END) AS within72, "
                        + "SUM(CASE WHEN v.discharge_date IS NOT NULL THEN 1 ELSE 0 END) AS base, "
                        + "SUM(CASE WHEN r.recall_time IS NOT NULL AND r.recall_approved = 1 THEN 1 ELSE 0 END) AS recalled "
                        + "FROM his_inp_medical_record r "
                        + "JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                        + "WHERE r.deleted = 0 AND r.status >= 4 AND r.tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (scope != null) {
            sql.append(" AND r.org_id = ? ");
            params.add(scope);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ? ");
            params.add(deptId);
        }
        if (from != null) {
            sql.append(" AND r.archive_time >= ? ");
            params.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND r.archive_time <= ? ");
            params.add(Timestamp.valueOf(to));
        }
        Map<String, Object> row = jdbcTemplate.queryForMap(sql.toString(), params.toArray());
        long total = toLong(row.get("total"));
        long within72 = toLong(row.get("within72"));
        long base = toLong(row.get("base"));
        long recalled = toLong(row.get("recalled"));
        out.put("totalArchived", total);
        out.put("submissionRate72h", percent(within72, base));
        out.put("returnRate", percent(recalled, total));

        /* 2. 本月归档数(固定当月口径) */
        List<Object> monthParams = new ArrayList<>();
        monthParams.add(tenantId);
        StringBuilder monthSql = new StringBuilder(
                "SELECT COUNT(*) FROM his_inp_medical_record r "
                        + "WHERE r.deleted = 0 AND r.status >= 4 AND r.tenant_id = ? "
                        + "AND r.archive_time >= DATE_FORMAT(NOW(), '%Y-%m-01')");
        appendScopeAndDept(monthSql, monthParams, scope, deptId);
        Long monthly = jdbcTemplate.queryForObject(monthSql.toString(), Long.class, monthParams.toArray());
        out.put("monthlyArchived", monthly == null ? 0L : monthly);

        /* 3. 逾期未归档数(当前口径) */
        List<Object> overdueParams = new ArrayList<>();
        overdueParams.add(tenantId);
        StringBuilder overdueSql = new StringBuilder(
                "SELECT COUNT(*) FROM his_inp_medical_record r "
                        + "JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                        + "WHERE r.deleted = 0 AND r.status < 4 AND v.discharge_date IS NOT NULL "
                        + "AND v.discharge_date < DATE_SUB(NOW(), INTERVAL " + OVERDUE_DAYS + " DAY) "
                        + "AND r.tenant_id = ? ");
        if (scope != null) {
            overdueSql.append(" AND r.org_id = ? ");
            overdueParams.add(scope);
        }
        if (deptId != null) {
            overdueSql.append(" AND v.dept_id = ? ");
            overdueParams.add(deptId);
        }
        Long overdue = jdbcTemplate.queryForObject(overdueSql.toString(), Long.class, overdueParams.toArray());
        out.put("overdueCount", overdue == null ? 0L : overdue);

        /* 4. 按科室归档进度 top10(已归档/应归档=已出院人数, 累计口径) */
        List<Object> progressParams = new ArrayList<>();
        progressParams.add(tenantId);
        StringBuilder progressSql = new StringBuilder(
                "SELECT v.dept_id AS deptId, d.dept_name AS deptName, COUNT(*) AS total, "
                        + "SUM(CASE WHEN r.status >= 4 THEN 1 ELSE 0 END) AS archived "
                        + "FROM his_inp_medical_record r "
                        + "JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                        + "LEFT JOIN his_dept d ON v.dept_id = d.id "
                        + "WHERE r.deleted = 0 AND r.tenant_id = ? AND v.discharge_date IS NOT NULL ");
        if (scope != null) {
            progressSql.append(" AND r.org_id = ? ");
            progressParams.add(scope);
        }
        if (deptId != null) {
            progressSql.append(" AND v.dept_id = ? ");
            progressParams.add(deptId);
        }
        progressSql.append(" GROUP BY v.dept_id, d.dept_name ORDER BY total DESC, archived DESC LIMIT 10");
        List<Map<String, Object>> progressRows = jdbcTemplate.queryForList(progressSql.toString(), progressParams.toArray());
        List<Map<String, Object>> deptProgress = new ArrayList<>();
        for (Map<String, Object> pr : progressRows) {
            long t = toLong(pr.get("total"));
            long a = toLong(pr.get("archived"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deptId", pr.get("deptId"));
            m.put("deptName", pr.get("deptName"));
            m.put("archived", a);
            m.put("total", t);
            m.put("rate", percent(a, t));
            deptProgress.add(m);
        }
        out.put("deptProgress", deptProgress);

        /* 5. 近6月归档趋势(固定口径, 空月补 0) */
        LocalDate cursorMonth = LocalDate.now().withDayOfMonth(1).minusMonths(5);
        List<Object> trendParams = new ArrayList<>();
        trendParams.add(tenantId);
        trendParams.add(Timestamp.valueOf(cursorMonth.atStartOfDay()));
        StringBuilder trendSql = new StringBuilder(
                "SELECT DATE_FORMAT(r.archive_time, '%Y-%m') AS ym, COUNT(*) AS cnt "
                        + "FROM his_inp_medical_record r "
                        + "JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                        + "WHERE r.deleted = 0 AND r.status >= 4 AND r.archive_time IS NOT NULL "
                        + "AND r.tenant_id = ? AND r.archive_time >= ? ");
        if (scope != null) {
            trendSql.append(" AND r.org_id = ? ");
            trendParams.add(scope);
        }
        if (deptId != null) {
            trendSql.append(" AND v.dept_id = ? ");
            trendParams.add(deptId);
        }
        trendSql.append(" GROUP BY ym ORDER BY ym");
        Map<String, Long> trendCounts = new HashMap<>();
        for (Map<String, Object> tr : jdbcTemplate.queryForList(trendSql.toString(), trendParams.toArray())) {
            trendCounts.put(String.valueOf(tr.get("ym")), toLong(tr.get("cnt")));
        }
        List<Map<String, Object>> monthlyTrend = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String ym = cursorMonth.format(MONTH_FMT);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("month", ym);
            t.put("count", trendCounts.getOrDefault(ym, 0L));
            monthlyTrend.add(t);
            cursorMonth = cursorMonth.plusMonths(1);
        }
        out.put("monthlyTrend", monthlyTrend);
        return out;
    }

    /* ==================== 定时任务 ==================== */

    /**
     * 超时未归档扫描(每小时): 遍历租户→设 TenantContext→查询逾期未归档(出院超 3 天, status<4)→
     * 逐条 SSE 提醒记录医生/主治医生(无力接收的记 warn 不阻断); finally 恢复上下文。
     * 参考 EmrTimelinessService 租户扫描模式(定时任务无 ThreadLocal 上下文)。
     */
    @Scheduled(fixedRate = 3_600_000)
    public void checkArchiveTimeout() {
        List<SysTenant> tenants;
        try {
            tenants = tenantService.listAll();
        } catch (Exception e) {
            log.warn("归档超时扫描跳过(租户表未就绪): {}", e.getMessage());
            return;
        }
        if (tenants == null) {
            return;
        }
        for (SysTenant t : tenants) {
            if (t == null || t.getId() == null
                    || SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                continue; // 平台运营方租户无医院业务数据
            }
            Long prev = TenantContext.get();
            try {
                TenantContext.set(t.getId());
                scanOverdueTenant();
            } catch (Exception e) {
                log.warn("租户[{}] 归档超时扫描失败: {}", t.getId(), e.getMessage());
            } finally {
                if (prev == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(prev);
                }
            }
        }
    }

    /** 单租户扫描: 逾期未归档逐条推送提醒(单条 try-catch, 失败不阻断批量)。 */
    private void scanOverdueTenant() {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                OVERDUE_SQL_BASE + " AND r.tenant_id = ? ORDER BY v.discharge_date ASC, r.id ASC",
                (rs, i) -> overdueRow(rs), TenantContext.get());
        int pushed = 0;
        for (Map<String, Object> row : rows) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("recordId", String.valueOf(row.get("recordId")));
                data.put("recordType", row.get("recordType"));
                data.put("typeLabel", row.get("typeLabel"));
                data.put("title", row.get("title"));
                data.put("patientName", row.get("patientName"));
                data.put("inpNo", row.get("inpNo"));
                data.put("deptName", row.get("deptName"));
                data.put("dischargeTime", row.get("dischargeTime"));
                data.put("overdueDays", row.get("overdueDays"));
                data.put("urgency", "overdue-archive");
                data.put("message", "病历《" + row.get("title") + "》患者已出院 " + row.get("overdueDays")
                        + " 天仍未归档, 请尽快完成归档");
                Long doctorId = toLongObj(row.get("doctorId"));
                Long visitDoctorId = toLongObj(row.get("visitDoctorId"));
                if (doctorId != null) {
                    eventPublisher.publish(doctorId, EmrEventType.QC_REMINDER, data);
                }
                if (visitDoctorId != null && !visitDoctorId.equals(doctorId)) {
                    eventPublisher.publish(visitDoctorId, EmrEventType.QC_REMINDER, data);
                }
                if (doctorId == null && visitDoctorId == null) {
                    eventPublisher.publish(null, EmrEventType.QC_REMINDER, data);
                }
                pushed++;
            } catch (Exception e) {
                log.warn("归档超时提醒单条推送失败(不阻断): recordId={}, err={}", row.get("recordId"), e.getMessage());
            }
        }
        if (!rows.isEmpty()) {
            log.info("归档超时扫描完成: 逾期未归档{}条, 已触发提醒{}条", rows.size(), pushed);
        }
    }

    /* ==================== 异步 PDF 生成 ==================== */

    /** 提交异步 PDF 生成(经代理调用保证 @Async 生效; 提交失败仅告警)。 */
    private void submitPdfGenerationQuietly(Long recordId) {
        if (pdfExportService == null) {
            return;
        }
        try {
            selfProvider.getObject().generateArchivePdfAsync(recordId, TenantContext.get());
        } catch (Exception e) {
            log.warn("归档PDF异步提交失败(不影响归档): recordId={}, err={}", recordId, e.getMessage());
        }
    }

    /**
     * 异步生成归档 PDF(best-effort): 读取病历 → 组装 HTML(密文经 EmrDocumentService 解密) →
     * PdfExportService 生成 A4 PDF → 落 {upload.path}/emr-archive/{yyyyMM}/{recordId}-{uuid8}.pdf →
     * 回填 pdf_path/pdf_generated_time。ThreadLocal 由调用线程捕获后在本线程恢复(参考 EmrTemplateService)。
     */
    @Async
    public void generateArchivePdfAsync(Long recordId, Long tenantId) {
        Long prevTenant = TenantContext.get();
        try {
            if (tenantId != null) {
                TenantContext.set(tenantId);
            }
            if (pdfExportService == null || recordId == null) {
                return;
            }
            HisInpMedicalRecord record = recordMapper.selectById(recordId);
            if (record == null) {
                log.warn("归档PDF生成跳过: 病历不存在 recordId={}", recordId);
                return;
            }
            String title = "住院病历-" + recordTypeLabel(record.getRecordType());
            byte[] pdf = pdfExportService.generatePdf(buildArchiveHtml(record), title);
            if (pdf == null || pdf.length == 0) {
                log.warn("归档PDF生成结果为空, 跳过回填: recordId={}", recordId);
                return;
            }
            String url = writePdfFile(pdf, recordId);
            jdbcTemplate.update(
                    "UPDATE his_inp_medical_record SET pdf_path = ?, pdf_generated_time = NOW() "
                            + "WHERE id = ? AND tenant_id = ? AND deleted = 0",
                    url, recordId, tenantId);
            log.info("归档PDF生成完成: recordId={}, url={}, size={}B", recordId, url, pdf.length);
        } catch (Exception e) {
            log.warn("归档PDF生成失败(best-effort, 不影响归档): recordId={}, err={}", recordId, e.getMessage());
        } finally {
            if (prevTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(prevTenant);
            }
        }
    }

    /** 组装归档 PDF 的 HTML: 标题 + 元信息 + 正文(富文本原样 / Tiptap 密文解密后包 pre)。 */
    private String buildArchiveHtml(HisInpMedicalRecord record) {
        StringBuilder sb = new StringBuilder(1024);
        String title = StringUtils.hasText(record.getTitle())
                ? record.getTitle() : recordTypeLabel(record.getRecordType());
        sb.append("<h2 style=\"text-align:center;\">").append(escapeHtml(title)).append("</h2>");
        sb.append("<p>记录类型: ").append(escapeHtml(recordTypeLabel(record.getRecordType())))
                .append(" &nbsp; 记录时间: ").append(record.getRecordTime() == null ? "-" : record.getRecordTime().format(DT_FMT))
                .append(" &nbsp; 归档时间: ").append(record.getArchiveTime() == null ? "-" : record.getArchiveTime().format(DT_FMT))
                .append("</p>");
        String content = record.getContent();
        if (!StringUtils.hasText(content)) {
            sb.append("<p>(无正文内容)</p>");
            return sb.toString();
        }
        String trimmed = content.trim();
        char first = trimmed.charAt(0);
        if (first == '<') {
            sb.append(content);
        } else {
            String plain = content;
            if (first != '{' && first != '[' && documentService != null) {
                try {
                    plain = documentService.loadDocument(1, record.getId(), trimmed);
                } catch (Exception e) {
                    log.warn("归档PDF正文解密失败(沿用原文): recordId={}, err={}", record.getId(), e.getMessage());
                }
            }
            sb.append("<pre style=\"white-space:pre-wrap;\">").append(escapeHtml(plain)).append("</pre>");
        }
        return sb.toString();
    }

    /** PDF 落盘: {upload.path}/emr-archive/{yyyyMM}/{recordId}-{uuid8}.pdf, 返回 {url-prefix} 访问 URL。 */
    private String writePdfFile(byte[] pdf, Long recordId) throws IOException {
        String ym = LocalDate.now().format(YM_FMT);
        String filename = recordId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".pdf";
        Path root = Paths.get(uploadPath).toAbsolutePath().normalize();
        Path dir = root.resolve("emr-archive").resolve(ym);
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), pdf);
        String prefix = urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/";
        return prefix + "emr-archive/" + ym + "/" + filename;
    }

    /* ==================== 副作用助手(best-effort) ==================== */

    /** 版本快照(失败仅告警): operate_type='归档', operator 职工ID优先。 */
    private void snapshotQuietly(Long recordId, String operateType, LoginUser u) {
        if (versionService == null) {
            return;
        }
        try {
            Long operatorId = u.getStaffId() != null ? u.getStaffId() : u.getUserId();
            versionService.saveVersion(recordId, operateType, operatorId, displayName(u));
        } catch (Exception e) {
            log.warn("归档版本快照失败(不影响归档): recordId={}, err={}", recordId, e.getMessage());
        }
    }

    /** 归档级内涵质控(stage=2, 失败仅告警): 该病历已有自动缺陷时跳过, 防重复调用累加缺陷(与 startReview 同口径)。 */
    private void evaluateArchiveQcQuietly(Long recordId) {
        if (qualityService == null) {
            return;
        }
        try {
            Long existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_emr_qc_defect "
                            + "WHERE record_id = ? AND auto_generated = 1 AND tenant_id = ? AND deleted = 0",
                    Long.class, recordId, TenantContext.require());
            if (existing != null && existing > 0) {
                log.debug("归档级质控跳过(已有自动缺陷): recordId={}", recordId);
                return;
            }
            qualityService.evaluateContent(recordId, 2);
            log.info("归档级内涵质控执行完成: recordId={}", recordId);
        } catch (Exception e) {
            log.warn("归档级质控失败(不影响归档): recordId={}, err={}", recordId, e.getMessage());
        }
    }

    /** 审计留痕(失败仅告警): 落 his_emr_audit_log(scope=1 住院)。 */
    private void auditQuietly(Long recordId, String action, String detail) {
        try {
            auditService.log(EmrAuditService.SCOPE_INP, recordId, action, detail);
        } catch (Exception e) {
            log.warn("归档审计留痕失败(不影响主流程): recordId={}, action={}, err={}", recordId, action, e.getMessage());
        }
    }

    /** 质控节点留痕(失败仅告警): node_type=归档/召回/封存/解封, operator 职工ID优先。 */
    private void logNodeQuietly(HisInpMedicalRecord record, String nodeType, String desc) {
        try {
            HisEmrQcNode node = new HisEmrQcNode();
            node.setRecordId(record.getId());
            node.setVisitId(record.getInpVisitId());
            node.setNodeType(nodeType);
            node.setNodeDesc(truncate(desc, 500));
            node.setScoreBefore(record.getQualityScore());
            node.setScoreAfter(record.getQualityScore());
            LoginUser u = UserContext.get();
            node.setOperatorId(u == null ? null : (u.getStaffId() != null ? u.getStaffId() : u.getUserId()));
            node.setOperatorName(u == null ? "system" : displayName(u));
            nodeMapper.insert(node);
        } catch (Exception e) {
            log.warn("归档节点留痕失败(不影响主流程): recordId={}, nodeType={}, err={}",
                    record.getId(), nodeType, e.getMessage());
        }
    }

    /** 定向推送记录医生(无记录医生则广播); 推送层异常不外抛。 */
    private void publishToDoctorQuietly(HisInpMedicalRecord record, HisInpVisit visit,
                                        EmrEventType type, String message) {
        try {
            publishSafely(record.getDoctorId(), type, eventData(record, visit, message));
        } catch (Exception e) {
            log.warn("归档SSE推送失败(不影响主流程): recordId={}, type={}, err={}",
                    record.getId(), type, e.getMessage());
        }
    }

    /** 通用安全发布: targetUserId 为空则广播(EmrEventPublisher 同步派发, 监听器内部已捕获异常)。 */
    private void publishSafely(Long targetUserId, EmrEventType type, Map<String, Object> data) {
        eventPublisher.publish(targetUserId, type, data);
    }

    /** 通知病案科: 按科室名含"病案"定向推送, 未匹配到则广播。 */
    private void notifyRecordDeptQuietly(EmrEventType type, Map<String, Object> data) {
        try {
            List<Long> deptIds = jdbcTemplate.queryForList(
                    "SELECT id FROM his_dept WHERE dept_name LIKE '%病案%' AND tenant_id = ? AND deleted = 0 "
                            + "ORDER BY id LIMIT 1",
                    Long.class, TenantContext.get());
            if (!deptIds.isEmpty()) {
                eventPublisher.publishToDept(deptIds.get(0), type, data);
            } else {
                eventPublisher.publish(null, type, data);
            }
        } catch (Exception e) {
            log.warn("召回通知病案科失败(降级广播): {}", e.getMessage());
            eventPublisher.publish(null, type, data);
        }
    }

    /** SSE 业务负载信封: recordId(字符串防丢精度)/患者/标题/消息。 */
    private Map<String, Object> eventData(HisInpMedicalRecord record, HisInpVisit visit, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", String.valueOf(record.getId()));
        data.put("visitId", record.getInpVisitId() == null ? null : String.valueOf(record.getInpVisitId()));
        data.put("recordType", record.getRecordType());
        data.put("typeLabel", recordTypeLabel(record.getRecordType()));
        data.put("title", record.getTitle());
        data.put("status", record.getStatus());
        data.put("inpNo", visit == null ? null : visit.getInpNo());
        data.put("patientName", visit == null ? null : patientNameOf(visit.getId()));
        data.put("message", message);
        return data;
    }

    /* ==================== 私有助手 ==================== */

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

    /** 写守卫: 记录归属机构须与当前登录机构一致; 平台超管放行(沿袭 MrManualQcService 模式)。 */
    private void requireSameOrg(Long orgId, String msg) {
        LoginUser u = requireLogin();
        if (u.hasRole(Roles.SUPER_ADMIN)) {
            return;
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法进行归档操作");
        }
        if (orgId != null && !orgId.equals(u.getOrgId())) {
            throw new BizException(403, msg);
        }
    }

    private LoginUser requireLogin() {
        LoginUser u = UserContext.get();
        if (u == null || u.getUserId() == null) {
            throw new BizException(401, "未登录");
        }
        return u;
    }

    /**
     * 读隔离范围: 牵头机构/平台超管返回 null(全院可见), 成员机构锁定本机构;
     * 未归属机构且非牵头/非超管直接 403, 防"scope=null 视为全院"口径漏洞(与 MrManualQcService 同口径)。
     */
    private Long readScopeOrg() {
        LoginUser u = UserContext.get();
        Long scope = guard.scopeOrgId(null);
        if (scope == null && u != null && !u.hasRole(Roles.SUPER_ADMIN)
                && !guard.isLead(u) && u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法查询归档数据");
        }
        return scope;
    }

    /** 统计 SQL 追加机构范围与科室过滤(record 表别名 r, 需已 JOIN his_inp_visit v)。 */
    private static void appendScopeAndDept(StringBuilder sql, List<Object> params, Long scope, Long deptId) {
        if (scope != null) {
            sql.append(" AND r.org_id = ? ");
            params.add(scope);
        }
        if (deptId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM his_inp_visit v WHERE v.id = r.inp_visit_id "
                    + "AND v.dept_id = ? AND v.deleted = 0) ");
            params.add(deptId);
        }
    }

    /** 患者姓名(联查就诊→患者, 失败返回 null)。 */
    private String patientNameOf(Long visitId) {
        if (visitId == null) {
            return null;
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT p.name FROM his_inp_visit v JOIN his_patient p ON v.patient_id = p.id "
                            + "WHERE v.id = ? AND v.tenant_id = ? AND v.deleted = 0 LIMIT 1",
                    String.class, visitId, TenantContext.get());
            return names.isEmpty() ? null : names.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 按显示名反查用户ID(username 或 real_name 匹配, 失败返回 null)。 */
    private Long resolveUserIdByName(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        try {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM sys_user WHERE tenant_id = ? AND deleted = 0 "
                            + "AND (username = ? OR real_name = ?) ORDER BY id LIMIT 1",
                    Long.class, TenantContext.get(), name, name);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 逾期行 Map(键驼峰, dischargeTime 格式化, overdueDays 按自然日差)。 */
    private static Map<String, Object> overdueRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordId", rs.getLong("recordId"));
        int recordType = rs.getInt("recordType");
        m.put("recordType", recordType);
        m.put("typeLabel", recordTypeLabel(recordType));
        m.put("title", rs.getString("title"));
        m.put("patientName", rs.getString("patientName"));
        m.put("inpNo", rs.getString("inpNo"));
        m.put("deptId", rs.getObject("deptId"));
        m.put("deptName", rs.getString("deptName"));
        m.put("doctorId", rs.getObject("doctorId"));
        m.put("doctorName", rs.getString("doctorName"));
        Timestamp discharge = rs.getTimestamp("dischargeDate");
        if (discharge == null) {
            m.put("dischargeTime", null);
            m.put("overdueDays", null);
        } else {
            LocalDateTime d = discharge.toLocalDateTime();
            m.put("dischargeTime", d.format(DT_FMT));
            m.put("overdueDays", (int) java.time.Duration.between(d, LocalDateTime.now()).toDays());
        }
        return m;
    }

    /** 查询结果记录判空兜底: selectById 意外为 null 时回退旧对象(不改变调用方语义)。 */
    private static HisInpMedicalRecord sameRecord(HisInpMedicalRecord updated, HisInpMedicalRecord fallback) {
        return updated == null ? fallback : updated;
    }

    /** 记录类型标签(1-15, 与 InpMedRecordService.RECORD_TYPE_LABELS 同口径)。 */
    private static String recordTypeLabel(Integer type) {
        if (type == null) {
            return "病历";
        }
        switch (type) {
            case 1: return "入院记录";
            case 2: return "首次病程记录";
            case 3: return "日常病程记录";
            case 4: return "查房记录";
            case 5: return "术前小结";
            case 6: return "手术记录";
            case 7: return "术后病程记录";
            case 8: return "出院小结";
            case 9: return "死亡记录";
            case 10: return "病案首页";
            case 11: return "交接班记录";
            case 12: return "转科记录";
            case 13: return "知情同意书";
            case 14: return "讨论记录";
            case 15: return "会诊记录";
            default: return "病历";
        }
    }

    /** 状态文案(1草稿/2已提交/3已审核/4已归档/5召回中/6已封存)。 */
    private static String statusLabel(Integer status) {
        if (status == null) {
            return "-";
        }
        switch (status) {
            case 1: return "草稿";
            case 2: return "已提交";
            case 3: return "已审核";
            case 4: return "已归档";
            case 5: return "召回中";
            case 6: return "已封存";
            default: return "未知(" + status + ")";
        }
    }

    /** 百分比(保留 1 位小数, 分母 ≤0 返回 0.0)。 */
    private static double percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    /** 数值取 Long(null/异常返回 0)。 */
    private static long toLong(Object v) {
        Long l = toLongObj(v);
        return l == null ? 0L : l;
    }

    /** 对象转 Long(null/异常返回 null; 兼容 Number 与字符串雪花ID)。 */
    private static Long toLongObj(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 日期解析(yyyy-MM-dd), 空/非法返回 null。 */
    private static LocalDate parseDate(String date) {
        if (!StringUtils.hasText(date)) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 区间起始(当日 00:00:00)。 */
    private static LocalDateTime parseDayStart(String date) {
        LocalDate d = parseDate(date);
        return d == null ? null : d.atStartOfDay();
    }

    /** 区间结束(当日 23:59:59)。 */
    private static LocalDateTime parseDayEnd(String date) {
        LocalDate d = parseDate(date);
        return d == null ? null : d.atTime(23, 59, 59);
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

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
