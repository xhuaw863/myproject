package com.yb.hi.service.doctor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.common.DateUtil;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.DiseInfoReq;
import com.yb.hi.dto.MdtrtInfoReq;
import com.yb.hi.dto.doctor.VisitDraftReq;
import com.yb.hi.dto.doctor.VisitFinishReq;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisMedicalRecord;
import com.yb.hi.entity.doctor.HisOrder;
import com.yb.hi.entity.doctor.HisPrescription;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.entity.outpatient.HisPatientInsu;
import com.yb.hi.entity.outpatient.HisRegistration;
import com.yb.hi.entity.yb.HisUploadStatus;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisOrderMapper;
import com.yb.hi.mapper.doctor.HisPrescriptionMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientInsuMapper;
import com.yb.hi.mapper.outpatient.HisRegistrationMapper;
import com.yb.hi.platform.service.DeptScopeResolver;
import com.yb.hi.platform.service.SystemParamResolver;
import com.yb.hi.service.OutpatientService;
import com.yb.hi.service.emr.EmrAuditService;
import com.yb.hi.service.emr.EmrDocumentService;
import com.yb.hi.service.inpatient.EmrVersionService;
import com.yb.hi.service.emr.EmrElementService;
import com.yb.hi.service.yb.UploadStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 就诊服务(医生站核心): 候诊队列 / 挂号建候诊 / 接诊 / 完成接诊(病历+诊断+2203上传)
 */
@Slf4j
@Service
public class HisVisitService extends ServiceImpl<HisVisitMapper, HisVisit> {

    /** 参数键: 2203 就诊信息上传方式(enum: realtime即时 / deferred定时批量补传; 定义见 VisitUploadModeParamSeeder) */
    public static final String P_INSURANCE_VISIT_UPLOAD_MODE = "yb.visit.upload.mode";
    public static final String UPLOAD_MODE_REALTIME = "realtime";
    public static final String UPLOAD_MODE_DEFERRED = "deferred";

    private final OutpatientService outpatientService;
    private final HisDiagnosisService diagnosisService;
    private final HisMedicalRecordService medicalRecordService;
    // 费用汇总/参保信息直取 Mapper: HisOrderService 已注入本服务, 再注 Service 会构成构造器循环依赖
    private final HisPrescriptionMapper prescriptionMapper;
    private final HisOrderMapper orderMapper;
    private final HisPatientInsuMapper patientInsuMapper;
    // 挂号状态闭环(完成接诊置挂号已完成): 直取 Mapper 避免与 HisRegistrationService 循环依赖
    private final HisRegistrationMapper registrationMapper;
    // 医生站就诊级科室判权(DeptScopeResolver 唯一口径)
    private final DeptScopeResolver deptScopeResolver;
    // 上传管线状态机(M5: 2203 结果落库/收费入口守卫/退号撤销)
    private final UploadStatusService uploadStatusService;
    // 2203 上传方式参数(yb.visit.upload.mode 四级作用域解析, 按登录机构上下文取生效值)
    private final SystemParamResolver paramResolver;
    // Phase B 门诊结构化病历版本快照(仅依赖 JdbcTemplate, 与本服务无环)
    private final EmrVersionService emrVersionService;
    // Phase C 门诊结构化病历数据元抽取(仅依赖 Mapper/Guard, 与本服务无环)
    private final EmrElementService emrElementService;
    // P3 Tiptap 双轨(密文轨): AES-GCM 加密落 his_visit.content + emrField 抽取, 与本服务无环
    private final EmrDocumentService emrDocumentService;
    // P3 Tiptap 双轨(留痕): 门诊病历(scope=2)保存/完成审计, 仅依赖 Mapper
    private final EmrAuditService emrAuditService;
    // 完成接诊 T1 事务(与 CashierService 同风格; 诊断"先删后插"并发死锁整体重试用)
    private final TransactionTemplate txTemplate;

    public HisVisitService(OutpatientService outpatientService, HisDiagnosisService diagnosisService,
                           HisMedicalRecordService medicalRecordService, HisPrescriptionMapper prescriptionMapper,
                           HisOrderMapper orderMapper, HisPatientInsuMapper patientInsuMapper,
                           HisRegistrationMapper registrationMapper, DeptScopeResolver deptScopeResolver,
                           UploadStatusService uploadStatusService, SystemParamResolver paramResolver,
                           EmrVersionService emrVersionService,
                           EmrElementService emrElementService, EmrDocumentService emrDocumentService,
                           EmrAuditService emrAuditService,
                           PlatformTransactionManager transactionManager) {
        this.outpatientService = outpatientService;
        this.diagnosisService = diagnosisService;
        this.medicalRecordService = medicalRecordService;
        this.prescriptionMapper = prescriptionMapper;
        this.orderMapper = orderMapper;
        this.patientInsuMapper = patientInsuMapper;
        this.registrationMapper = registrationMapper;
        this.deptScopeResolver = deptScopeResolver;
        this.uploadStatusService = uploadStatusService;
        this.paramResolver = paramResolver;
        this.emrVersionService = emrVersionService;
        this.emrElementService = emrElementService;
        this.emrDocumentService = emrDocumentService;
        this.emrAuditService = emrAuditService;
        this.txTemplate = new TransactionTemplate(transactionManager);
        // T1 事务改读已提交: 诊断"先删后插"在默认 RR 下并发完成接诊互相抢 idx_visit 间隙锁,
        // RC 不取间隙锁, 从根上消除该死锁类别(死锁整体重试仍保留作兜底)
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 候诊/就诊队列分页查询 */
    public IPage<HisVisit> queuePage(long page, long size, LocalDate workDate, Integer visitStatus,
                                     Long deptId, Long staffId, String keyword) {
        LambdaQueryChainWrapper<HisVisit> q = lambdaQuery()
                .eq(workDate != null, HisVisit::getWorkDate, workDate)
                .eq(visitStatus != null, HisVisit::getVisitStatus, visitStatus)
                .eq(deptId != null, HisVisit::getDeptId, deptId)
                .eq(staffId != null, HisVisit::getStaffId, staffId);
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisVisit::getPatientName, keyword)
                    .or().like(HisVisit::getRegNo, keyword)
                    .or().like(HisVisit::getPatientNo, keyword));
        }
        return q.orderByAsc(HisVisit::getVisitStatus).orderByDesc(HisVisit::getId)
                .page(new Page<>(page, size));
    }

    /**
     * 挂号成功后创建候诊就诊记录(visit_status=1), 与挂号1:1
     */
    @Transactional(rollbackFor = Exception.class)
    public HisVisit createFromRegistration(HisRegistration reg, HisPatient patient) {
        HisVisit v = new HisVisit();
        v.setRegistrationId(reg.getId());
        v.setRegNo(reg.getRegNo());
        v.setMdtrtId(reg.getMdtrtId());
        v.setIptOtpNo(reg.getIptOtpNo());
        v.setPatientId(reg.getPatientId());
        v.setPatientNo(reg.getPatientNo());
        v.setPatientName(reg.getPatientName());
        if (patient != null) {
            v.setGender(patient.getGender());
            v.setAge(patient.getAge());
        }
        v.setPsnNo(reg.getPsnNo());
        v.setInsutype(reg.getInsutype());
        v.setMedType(reg.getMedType());
        v.setDeptId(reg.getDeptId());
        v.setDeptCode(reg.getDeptCode());
        v.setDeptName(reg.getDeptName());
        v.setStaffId(reg.getStaffId());
        v.setAtddrNo(reg.getAtddrNo());
        v.setDrName(reg.getDrName());
        v.setWorkDate(reg.getWorkDate() == null ? LocalDate.now() : reg.getWorkDate());
        v.setVisitStatus(1);
        v.setQueueNo(reg.getQueueNo());
        save(v);
        log.info("创建候诊就诊: visitId={}, regNo={}", v.getId(), reg.getRegNo());
        return v;
    }

    /** 挂号退号时取消候诊就诊(visit_status=4), 并撤销上传管线 VISIT 状态(不再补传, 收费守卫阻断) */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByRegistration(Long registrationId) {
        HisVisit v = lambdaQuery().eq(HisVisit::getRegistrationId, registrationId).one();
        if (v != null && v.getVisitStatus() != null && v.getVisitStatus() == 1) {
            v.setVisitStatus(4);
            updateById(v);
            uploadStatusService.markRevoked(TenantContext.require(), HisUploadStatus.BIZ_VISIT, v.getId(), v.getMdtrtId());
        }
    }

    /**
     * 医生站就诊级科室判权(B4, DeptScopeResolver 唯一口径):
     * 管理员/牵头机构用户直通; 其余用户仅可操作授权科室范围内的就诊(就诊所属科室不在授权集即拒绝)。
     */
    public void requireVisitScope(Long visitId) {
        if (visitId == null || deptScopeResolver.isUnrestricted()) {
            return;
        }
        HisVisit v = getById(visitId);
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        if (v.getDeptId() == null || !deptScopeResolver.currentAuthDeptIds().contains(v.getDeptId())) {
            throw new BizException(403, "无该就诊所属科室的数据权限");
        }
    }

    /**
     * 接诊: 候诊(1) -> 接诊中(2), 记录接诊时间
     */
    @Transactional(rollbackFor = Exception.class)
    public HisVisit startVisit(Long visitId) {
        requireVisitScope(visitId);
        HisVisit v = getById(visitId);
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        if (v.getVisitStatus() != null && v.getVisitStatus() >= 3) {
            throw new BizException("该就诊已完成或已取消");
        }
        v.setVisitStatus(2);
        v.setVisitTime(LocalDateTime.now());
        updateById(v);
        return v;
    }

    /**
     * 完成接诊: T1 事务(病历字段+SOAP病历+诊断+挂号状态闭环, 状态位条件抢占防双窗口重复完成)
     * -> T2 事务外按参数分派 2203 上传(yb.visit.upload.mode: realtime即时调用 /
     *    deferred 落待传(0)队列由定时任务批量补传; 结果落 his_upload_status 状态机,
     *    失败/入队异常均不阻断接诊完成, 收费入口守卫强制补传兜底)。
     * 诊断"先删后插"替换式写入并发下曾死锁(idx_visit 间隙锁, 压测复现): T1 事务已改读已提交(不取间隙锁)
     * 从根上消除, 并保留 DeadlockLoserDataAccessException 整体重试(每次新事务)作兜底;
     * 2203 不得在事务内执行(真实平台响应秒级, 会长时间占事务与连接)。
     */
    public HisVisit finishVisit(VisitFinishReq req) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        requireVisitScope(req.getVisitId());
        // T1 事务: 落库(死锁整体重试)
        HisVisit v = executeWithDeadlockRetry(() -> txTemplate.execute(status -> doFinishTx(req)));
        // T2 事务外: 医保2203就诊信息上传(时机由机构级参数控制, 默认 realtime 与历史行为一致;
        // deferred 不即时调医保接口, 落待传队列由 UploadStatusSweeper 定时批量补传)
        boolean upload = req.getUploadYb() == null || req.getUploadYb();
        if (upload && StringUtils.hasText(v.getMdtrtId())) {
            if (isVisitUploadDeferred()) {
                try {
                    uploadStatusService.markPending(TenantContext.require(),
                            HisUploadStatus.BIZ_VISIT, v.getId(), v.getMdtrtId());
                    log.info("2203按参数 deferred 入待传队列: visitId={}", v.getId());
                } catch (Exception e) {
                    // 入队失败不阻断接诊完成: 收费入口守卫(ensureVisitUploaded)仍会现场补传兜底
                    log.warn("2203待传队列入队失败: visitId={}, err={}", v.getId(), e.getMessage());
                }
            } else {
                boolean ok = false;
                String msgid = null;
                String err = "2203未执行";
                try {
                    YbResponse resp = tryUploadVisitInfo(v, diagnosisService.listByVisit(v.getId()));
                    ok = resp != null && resp.isSuccess();
                    msgid = resp == null || resp.getInfRefmsgid() == null ? null : resp.getInfRefmsgid();
                    if (!ok) {
                        err = resp == null ? "医保无响应" : (resp.isUnknown() ? "医保响应未知(超时)" : resp.getErrMsg());
                    }
                } catch (Exception e) {
                    // 上传失败不阻断接诊完成, 仅记录日志
                    err = e.getMessage();
                    log.warn("2203就诊信息上传失败: visitId={}, err={}", v.getId(), e.getMessage());
                }
                uploadStatusService.recordVisit(TenantContext.require(), v.getId(), v.getMdtrtId(), ok, msgid, err);
            }
        }
        log.info("完成接诊: visitId={}, patient={}", v.getId(), v.getPatientName());
        return v;
    }
    
    /** 2203 是否配置为定时批量补传(deferred); 参数未定义/解析异常一律回退 realtime, 与历史行为一致 */
    private boolean isVisitUploadDeferred() {
        try {
            String mode = paramResolver.resolve(P_INSURANCE_VISIT_UPLOAD_MODE);
            return StringUtils.hasText(mode) && UPLOAD_MODE_DEFERRED.equalsIgnoreCase(mode.trim());
        } catch (Exception e) {
            log.warn("2203上传方式参数解析失败({}), 默认 realtime", e.getMessage());
            return false;
        }
    }

    /**
     * 接诊中显式保存诊断(OP-B 报卡前移): 诊断面板"保存诊断"按钮经此替换式落库,
     * 使医保对接/报卡判定等就诊过程中的消费方能读到已存诊断。
     * 与完成接诊同一口径: 科室判权 + 仅接诊中(visitStatus=2)可保存 + 死锁整体重试(先删后插)
     * + 诊断医师/科室归集参数同源; 完成接诊的替换式落库仍保留作兜底(不点保存也不丢诊断)。
     */
    public List<HisDiagnosis> saveConsultDiagnoses(Long visitId, List<HisDiagnosis> diagnoses) {
        if (visitId == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        requireVisitScope(visitId);
        HisVisit v = getById(visitId);
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        if (v.getVisitStatus() == null || v.getVisitStatus() != 2) {
            throw new BizException(400, "仅接诊中的患者可保存诊断(请先开始接诊或已完成接诊)");
        }
        executeWithDeadlockRetry(() -> txTemplate.execute(st -> {
            diagnosisService.saveDiagnoses(v.getId(), v.getDeptName(), v.getAtddrNo(), v.getDrName(),
                    diagnoses, v.getStaffId(), v.getDeptId());
            return null;
        }));
        log.info("接诊中诊断已保存: visitId={}, count={}", visitId, diagnoses == null ? 0 : diagnoses.size());
        return diagnosisService.listByVisit(visitId);
    }

    /** T1 事务体: 校验 -> 状态位条件抢占(防双窗口) -> 病历/诊断/挂号闭环落库 */
    private HisVisit doFinishTx(VisitFinishReq req) {
        HisVisit v = getById(req.getVisitId());
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        if (v.getVisitStatus() != null && v.getVisitStatus() >= 3) {
            throw new BizException("该就诊已完成或已取消");
        }

        // 状态位条件抢占: 双窗口/重复提交并发完成接诊时仅一次成功(与收费 T1 守卫同口径);
        // baseMapper.update 返回受影响行数(ServiceImpl.update 返回 boolean 判不了抢占)
        int affected = baseMapper.update(null, Wrappers.<HisVisit>lambdaUpdate()
                .set(HisVisit::getVisitStatus, 3)
                .set(HisVisit::getFinishTime, LocalDateTime.now())
                .eq(HisVisit::getId, v.getId())
                .lt(HisVisit::getVisitStatus, 3)
                .eq(HisVisit::getDeleted, 0));
        if (affected == 0) {
            throw new BizException("该就诊已完成或已取消");
        }

        // 回写就诊主表病历字段与医保扩展字段
        // 方案 B 收敛(B2): 结构化正文为单一真源; SOAP 文本列仅在请求显式携带时回写(历史文本病历),
        // 结构化完成不写 SOAP 列, 下游(病历S/O/A/P、医保2203、打印、回显)按需用 EmrStructureReader 派生
        if (req.getChiefComplaint() != null) v.setChiefComplaint(req.getChiefComplaint());
        if (req.getPresentIllness() != null) v.setPresentIllness(req.getPresentIllness());
        if (req.getPastHistory() != null) v.setPastHistory(req.getPastHistory());
        if (req.getAllergyHistory() != null) v.setAllergyHistory(req.getAllergyHistory());
        if (req.getPhysicalExam() != null) v.setPhysicalExam(req.getPhysicalExam());
        if (req.getAuxExam() != null) v.setAuxExam(req.getAuxExam());
        if (req.getTreatmentOpinion() != null) v.setTreatmentOpinion(req.getTreatmentOpinion());
        if (req.getDiseTypeCode() != null) v.setDiseTypeCode(req.getDiseTypeCode());
        if (req.getBirctrlType() != null) v.setBirctrlType(req.getBirctrlType());
        if (req.getBirctrlMatnDate() != null) v.setBirctrlMatnDate(parseDate(req.getBirctrlMatnDate()));
        if (req.getStructure() != null) v.setStructure(req.getStructure());
        if (req.getEmrTemplateId() != null) v.setEmrTemplateId(req.getEmrTemplateId());
        // OP-A 诊后去向(离院/转科/转留观/转院)
        if (req.getDisposition() != null) v.setDisposition(req.getDisposition());
        if (req.getDispositionDeptId() != null) v.setDispositionDeptId(req.getDispositionDeptId());
        if (req.getDispositionNote() != null) v.setDispositionNote(req.getDispositionNote());
        // P3 Tiptap 双轨: 前端提交 Tiptap JSON → 密文落 content + emrFormat=1, 同步维护扁平 structure;
        // 下方 EmrStructureReader.read(v) 派生 S/O/A/P 及 2203 主诉因 structure 扁平口径不变而继续工作
        if (req.getContent() != null && isTiptapDocument(req.getContent())) {
            v.setContent(emrDocumentService.encrypt(req.getContent()));
            v.setEmrFormat(1);
            Map<String, String> fieldMap = emrDocumentService.extractFieldMap(req.getContent());
            v.setStructure(JSON.toJSONString(fieldMap));
            emrAuditService.log(2, v.getId(), "SUBMIT", null);
            emrElementService.syncFromTiptap(2, v.getId(), req.getContent());
        }
        v.setVisitStatus(3);
        v.setFinishTime(LocalDateTime.now());
        updateById(v);

        // 挂号状态闭环: 就诊完成 -> 挂号置已完成(3, 条件更新仅已挂号1可置3);
        // 防止"就诊已完成/已接诊仍可退号"与医保2202撤销挂号冲突(cancel侧有对向守卫)
        if (v.getRegistrationId() != null) {
            registrationMapper.update(null, Wrappers.<HisRegistration>lambdaUpdate()
                    .set(HisRegistration::getStatus, 3)
                    .eq(HisRegistration::getId, v.getRegistrationId())
                    .eq(HisRegistration::getStatus, 1));
        }

        // 保存诊断(替换式: 先删后插, 并发下可能死锁, 由外层整体重试收敛)
        diagnosisService.saveDiagnoses(v.getId(), v.getDeptName(), v.getAtddrNo(), v.getDrName(),
                req.getDiagnoses(), v.getStaffId(), v.getDeptId());

        // 组装正式病历(SOAP): 结构化正文优先派生 S/O/A/P(方案 B B2); 无结构化沿用请求 SOAP 文本
        List<HisDiagnosis> savedDiag = diagnosisService.listByVisit(v.getId());
        String assessment = savedDiag.stream().map(HisDiagnosis::getDiagName)
                .filter(StringUtils::hasText).collect(Collectors.joining(","));
        HisMedicalRecord record = new HisMedicalRecord();
        record.setVisitId(v.getId());
        // 方案 B(B2): 单一真源—统一从 v(已合并本次请求携带的 SOAP 列或 structure)经派生器组装 S/O/A/P;
        // 结构化病历按 fieldKey 对齐取值, 历史文本病历自动回退旧 SOAP 列
        Map<String, String> soapView = EmrStructureReader.read(v);
        record.setSubjective(EmrStructureReader.subjectiveText(soapView));
        record.setObjective(EmrStructureReader.objectiveText(soapView));
        record.setPlan(EmrStructureReader.planText(soapView));
        record.setAllergyHistory(soapView.getOrDefault("allergyHistory", ""));
        record.setAuxExam(soapView.getOrDefault("auxExam", ""));
        record.setAssessment(assessment);
        record.setDrName(v.getDrName());
        record.setDrSign(v.getDrName());
        medicalRecordService.saveRecord(record);
        // Phase C: 完成接诊正式病历后同步数据元(scope=2, 以最新 structure 为准)
        emrElementService.syncOutpVisit(v.getId());
        return v;
    }

    /** 死锁整体重试: 每次重试走新事务, 最多3次(诊断替换式写入 RR 间隙锁死锁收敛) */
    private <T> T executeWithDeadlockRetry(Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (DeadlockLoserDataAccessException e) {
                if (++attempt >= 3) {
                    log.error("完成接诊事务死锁重试3次仍失败", e);
                    throw e;
                }
                log.warn("完成接诊诊断写入死锁, 第{}次重试", attempt);
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * 补传 2203(收费入口守卫与定时扫描共用): 显式租户上下文加载就诊与诊断后调用医保 2203;
     * 失败不抛异常, 返回回执供调用方判定落状态。
     */
    public YbResponse uploadVisitYb(Long tenantId, Long visitId) {
        // 嵌套租户上下文: 保存外层(请求线程可能已有租户上下文)并在 finally 恢复, 不得 clear 掉请求上下文
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            HisVisit v = lambdaQuery()
                    .eq(HisVisit::getId, visitId)
                    .eq(HisVisit::getDeleted, 0)
                    .last("LIMIT 1")
                    .one();
            if (v == null) {
                return null;
            }
            if (v.getVisitStatus() != null && v.getVisitStatus() == 4) {
                // 返回而非抛异常: 退号后遗留的待传(0)队列行由扫描器安全跳过, 不计失败退避
                log.warn("2203补传跳过: 就诊已退号撤销, visitId={}", visitId);
                return null;
            }
            List<HisDiagnosis> diagnoses = diagnosisService.listByVisit(visitId);
            return tryUploadVisitInfo(v, diagnoses);
        } catch (BizException e) {
            log.warn("2203补传拒绝: visitId={}, err={}", visitId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("2203补传异常: visitId={}, err={}", visitId, e.getMessage());
            return null;
        } finally {
            if (outer == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(outer);
            }
        }
    }

    /**
     * 收费入口守卫(设计 §3.5): 规范顺序上就诊上传(2203)是结算前置,
     * VISIT 未传成功时先补传 2203, 成功才继续 2204/2206/2207; 失败抛异常阻断收费。
     */
    public void ensureVisitUploaded(Long visitId) {
        HisVisit v = getById(visitId);
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        if (!StringUtils.hasText(v.getMdtrtId())) {
            // 无医保就诊信息: 由收费建单前置校验拒绝(提示改用自费), 上传状态无意义
            return;
        }
        HisUploadStatus row = uploadStatusService.findByBiz(TenantContext.require(),
                HisUploadStatus.BIZ_VISIT, visitId);
        if (row != null && row.getStatus() != null
                && row.getStatus() == HisUploadStatus.STATUS_UPLOADED) {
            return;
        }
        if (row != null && row.getStatus() != null
                && row.getStatus() == HisUploadStatus.STATUS_REVOKED) {
            throw new BizException("该就诊已退号撤销, 不能收费");
        }
        // 待传/失败待补: 先补传 2203, 成功才继续收费链
        YbResponse resp = uploadVisitYb(TenantContext.require(), visitId);
        boolean ok = resp != null && resp.isSuccess();
        String err = resp == null ? "医保无响应" : (resp.isUnknown() ? "医保响应未知(超时)" : resp.getErrMsg());
        uploadStatusService.recordVisit(TenantContext.require(), visitId, v.getMdtrtId(), ok,
                ok ? resp.getInfRefmsgid() : null, ok ? null : err);
        if (!ok) {
            throw new BizException("医保就诊信息(2203)尚未上传成功, 本次补传失败: "
                    + err + "; 已记录待补传, 系统将自动重试, 稍后重试收费");
        }
    }

    /** 组装并调用医保2203(失败不抛异常, 由调用方按回执判定) */
    private YbResponse tryUploadVisitInfo(HisVisit v, List<HisDiagnosis> diagnoses) {
        MdtrtInfoReq mdtrt = new MdtrtInfoReq();
        mdtrt.setMdtrtId(v.getMdtrtId());
        mdtrt.setPsnNo(v.getPsnNo());
        mdtrt.setMedType(v.getMedType() != null ? v.getMedType() : "11");
        mdtrt.setBegntime(v.getVisitTime() == null ? DateUtil.currentDateTime() : DateUtil.format(v.getVisitTime()));
        // 方案 B(B2): 主诉从 structure 派生(无结构化自动回退旧 SOAP 列)
        mdtrt.setMainCondDscr(EmrStructureReader.read(v).get("chiefComplaint"));
        mdtrt.setDiseTypeCode(v.getDiseTypeCode());
        mdtrt.setBirctrlType(v.getBirctrlType());
        mdtrt.setBirctrlMatnDate(v.getBirctrlMatnDate() == null ? null : v.getBirctrlMatnDate().toString());
        if (!CollectionUtils.isEmpty(diagnoses)) {
            HisDiagnosis main = diagnoses.stream()
                    .filter(d -> "1".equals(d.getMaindiagFlag())).findFirst()
                    .orElse(diagnoses.get(0));
            mdtrt.setDiseCodg(main.getDiagCode());
            mdtrt.setDiseName(main.getDiagName());
        }

        List<DiseInfoReq> diseList = new ArrayList<>();
        if (!CollectionUtils.isEmpty(diagnoses)) {
            for (HisDiagnosis d : diagnoses) {
                DiseInfoReq di = new DiseInfoReq();
                // 门诊2203的diseinfo节点(文档表103/105)仅含10个字段, 不含mdtrt_id/psn_no/adm_cond(住院2401/2402才需要),
                // 故此处不设置, 避免向门诊报文夹带文档外字段(DiseInfoReq为门诊/住院共用DTO)
                di.setDiagType(d.getDiagType());
                di.setDiagSrtNo(d.getDiagSrtNo());
                di.setDiagCode(d.getDiagCode());
                di.setDiagName(d.getDiagName());
                di.setDiagDept(d.getDiagDept());
                di.setDiseDorNo(d.getDiseDorNo());
                di.setDiseDorName(d.getDiseDorName());
                di.setDiagTime(d.getDiagTime() == null ? DateUtil.currentDateTime() : DateUtil.format(d.getDiagTime()));
                di.setValiFlag(d.getValiFlag());
                di.setMaindiagFlag(d.getMaindiagFlag());
                diseList.add(di);
            }
        }
        // 带患者参保地区划(规范表3: 2203输入含psn_no时insuplc_admdvs必填)
        return outpatientService.uploadVisitInfo(mdtrt, diseList, insuplcAdmdvsOf(v));
    }

    /* ==================== 病历草稿 / 历史 / 费用 / 参保 ==================== */

    /**
     * 保存病历草稿: 回写病历字段与医保扩展字段, 不改变就诊状态(草稿随完成接诊正式落病历)
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveDraft(VisitDraftReq req) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        requireVisitScope(req.getVisitId());
        HisVisit v = getById(req.getVisitId());
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        if (v.getVisitStatus() != null && v.getVisitStatus() >= 3) {
            throw new BizException("该就诊已完成或已取消, 不能保存草稿");
        }
        // SOAP 逐列: 仅当请求携带(非 null)才回写, 结构化模式不发 SOAP 时不清空既有文本病历(双模式并存)
        if (req.getChiefComplaint() != null) v.setChiefComplaint(req.getChiefComplaint());
        if (req.getPresentIllness() != null) v.setPresentIllness(req.getPresentIllness());
        if (req.getPastHistory() != null) v.setPastHistory(req.getPastHistory());
        if (req.getAllergyHistory() != null) v.setAllergyHistory(req.getAllergyHistory());
        if (req.getPhysicalExam() != null) v.setPhysicalExam(req.getPhysicalExam());
        if (req.getAuxExam() != null) v.setAuxExam(req.getAuxExam());
        if (req.getTreatmentOpinion() != null) v.setTreatmentOpinion(req.getTreatmentOpinion());
        if (req.getDiseTypeCode() != null) v.setDiseTypeCode(req.getDiseTypeCode());
        if (req.getBirctrlType() != null) v.setBirctrlType(req.getBirctrlType());
        if (req.getBirctrlMatnDate() != null) v.setBirctrlMatnDate(parseDate(req.getBirctrlMatnDate()));
        if (req.getFollowupDate() != null) v.setFollowupDate(parseDate(req.getFollowupDate()));
        if (req.getFollowupNote() != null) v.setFollowupNote(req.getFollowupNote());
        // 结构化病历: 落库前对旧 structure 做版本快照(scope=2), 再回写新结构;
        // P3 Tiptap 双轨: 本次提交 Tiptap 文档同样先对旧版本快照(含旧 Tiptap 密文轨)再覆写
        boolean tiptapChanged = req.getContent() != null && isTiptapDocument(req.getContent());
        boolean structureChanged = req.getStructure() != null && !req.getStructure().equals(v.getStructure());
        if (structureChanged || tiptapChanged) {
            snapshotOutpVersion(v);
        }
        if (structureChanged) {
            v.setStructure(req.getStructure());
        }
        if (req.getEmrTemplateId() != null) v.setEmrTemplateId(req.getEmrTemplateId());
        // P3 Tiptap 双轨: 前端提交 Tiptap JSON → 密文落 content + emrFormat=1,
        // 同时维护扁平 structure(fieldKey→值)供 EmrStructureReader 下游(SOAP派生/2203主诉/历史列表)不变
        if (tiptapChanged) {
            v.setContent(emrDocumentService.encrypt(req.getContent()));
            v.setEmrFormat(1);
            Map<String, String> fieldMap = emrDocumentService.extractFieldMap(req.getContent());
            v.setStructure(JSON.toJSONString(fieldMap));
            emrAuditService.log(2, v.getId(), "UPDATE", null);
            emrElementService.syncFromTiptap(2, v.getId(), req.getContent());
        }
        updateById(v);
        // Phase C: 结构化病历落库后同步数据元(先删后插幂等; 失败不影响草稿保存)
        emrElementService.syncOutpVisit(v.getId());
        log.info("保存病历草稿: visitId={}, structured={}, tiptap={}", v.getId(), v.getStructure() != null, tiptapChanged);
    }

    /** 门诊结构化病历版本快照(静默): 覆盖旧 structure/content 前落 his_emr_version(scope=2, refId=visitId) 一行; 失败仅告警 */
    private void snapshotOutpVersion(HisVisit v) {
        LoginUser user = UserContext.get();
        if (user == null || user.getUserId() == null) {
            return;
        }
        try {
            String contentSnapshot;
            String structureSnapshot = v.getStructure();
            if (v.getEmrFormat() != null && v.getEmrFormat() == 1 && StringUtils.hasText(v.getContent())) {
                // P3 Tiptap 双轨: 明文 Tiptap 文档落 content 快照(回溯/回滚用), 扁平 structure 原样落 structure 快照
                contentSnapshot = emrDocumentService.loadDocument(2, v.getId(), v.getContent());
            } else {
                // 旧格式: SOAP 汇总文本落 content 快照(与既有口径一致)
                Map<String, String> soapView = EmrStructureReader.read(v);
                contentSnapshot = joinNonEmpty("S:", EmrStructureReader.subjectiveText(soapView),
                        "O:", EmrStructureReader.objectiveText(soapView),
                        "P:", EmrStructureReader.planText(soapView));
            }
            emrVersionService.saveVersionByScope(2, v.getId(), contentSnapshot, structureSnapshot,
                    user.getUserId(),
                    StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername(),
                    "save");
        } catch (Exception e) {
            log.warn("门诊病历版本快照失败(不影响主流程): visitId={}, err={}", v.getId(), e.getMessage());
        }
    }

    /** Tiptap 文档判定(与住院 InpMedRecordService 同口径): 解析 JSON 且顶层 type=doc */
    private boolean isTiptapDocument(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        String s = content.trim();
        if (s.isEmpty() || s.charAt(0) != '{') {
            return false;
        }
        try {
            JSONObject obj = JSON.parseObject(s);
            return obj != null && "doc".equals(String.valueOf(obj.get("type")));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 患者历史就诊(已完成, visit_status=3): 按完成时间倒序取前 N 条, 附主诊断名称
     */
    public List<Map<String, Object>> listHistory(Long patientId, int limit) {
        if (patientId == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        int n = limit < 1 ? 5 : Math.min(limit, 50);
        List<HisVisit> visits = lambdaQuery()
                .eq(HisVisit::getPatientId, patientId)
                .eq(HisVisit::getVisitStatus, 3)
                .orderByDesc(HisVisit::getFinishTime)
                .orderByDesc(HisVisit::getId)
                .last("LIMIT " + n)
                .list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (HisVisit v : visits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", v.getId());
            row.put("workDate", v.getWorkDate());
            row.put("deptName", v.getDeptName());
            row.put("drName", v.getDrName());
            Map<String, String> soapView = EmrStructureReader.read(v);
            row.put("chiefComplaint", soapView.get("chiefComplaint"));
            row.put("treatmentOpinion", soapView.get("treatmentOpinion"));
            row.put("mainDiagName", mainDiagName(v.getId()));
            result.add(row);
        }
        return result;
    }

    /** 就诊主诊断名称(maindiag_flag=1, 缺失取首条) */
    private String mainDiagName(Long visitId) {
        List<HisDiagnosis> diags = diagnosisService.listByVisit(visitId);
        if (CollectionUtils.isEmpty(diags)) {
            return null;
        }
        return diags.stream()
                .filter(d -> "1".equals(d.getMaindiagFlag()))
                .map(HisDiagnosis::getDiagName)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(diags.get(0).getDiagName());
    }

    /**
     * 就诊费用汇总: 处方(rx)与检查/治疗单(order)各自笔数与金额, 及总金额。
     * 仅统计有效单(status>0): 医生已作废的处方/医嘱单不得计入费用(与收银、报表口径一致)。
     */
    public Map<String, Object> getFeeSummary(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        Map<String, Object> rx = aggFee(prescriptionMapper.selectMaps(new QueryWrapper<HisPrescription>()
                .select("COUNT(*) AS cnt", "IFNULL(SUM(total_amount), 0) AS total")
                .eq("visit_id", visitId).gt("status", 0)));
        Map<String, Object> od = aggFee(orderMapper.selectMaps(new QueryWrapper<HisOrder>()
                .select("COUNT(*) AS cnt", "IFNULL(SUM(total_amount), 0) AS total")
                .eq("visit_id", visitId).gt("status", 0)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rxTotal", rx.get("total"));
        result.put("orderTotal", od.get("total"));
        result.put("totalFee", toBd(rx.get("total")).add(toBd(od.get("total"))));
        result.put("rxCount", rx.get("cnt"));
        result.put("orderCount", od.get("cnt"));
        return result;
    }

    /** 聚合行转(cnt, total), 空结果兜底 0 */
    private static Map<String, Object> aggFee(List<Map<String, Object>> rows) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(rows) || rows.get(0) == null) {
            r.put("cnt", 0);
            r.put("total", BigDecimal.ZERO);
            return r;
        }
        Map<String, Object> row = rows.get(0);
        r.put("cnt", row.get("cnt") == null ? 0 : ((Number) row.get("cnt")).intValue());
        r.put("total", toBd(row.get("total")));
        return r;
    }

    /** 患者参保地区划(规范表3: 医保交易输入含psn_no时insuplc_admdvs必填), 取与就诊psn_no匹配的参保记录 */
    private String insuplcAdmdvsOf(HisVisit v) {
        if (v.getPatientId() == null) {
            return null;
        }
        HisPatientInsu insu = patientInsuMapper.selectOne(new LambdaQueryWrapper<HisPatientInsu>()
                .eq(HisPatientInsu::getPatientId, v.getPatientId())
                .eq(HisPatientInsu::getPsnNo, v.getPsnNo())
                .orderByAsc(HisPatientInsu::getId)
                .last("LIMIT 1"));
        return insu == null ? null : insu.getInsuplcAdmdvs();
    }

    /**
     * 患者医保参保信息(his_patient_insu, 一人可多条: 职工+居民/参保+停保, 按id序返回)
     */
    public List<HisPatientInsu> getInsuInfo(Long patientId) {
        if (patientId == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        return patientInsuMapper.selectList(new LambdaQueryWrapper<HisPatientInsu>()
                .eq(HisPatientInsu::getPatientId, patientId)
                .orderByAsc(HisPatientInsu::getId));
    }

    /** yyyy-MM-dd 字符串转 LocalDate, 空返回 null, 非法格式报错 */
    private static LocalDate parseDate(String d) {
        if (!StringUtils.hasText(d)) {
            return null;
        }
        try {
            return LocalDate.parse(d.trim());
        } catch (Exception e) {
            throw new BizException(400, "日期格式不正确(yyyy-MM-dd): " + d);
        }
    }

    /** Number/BigDecimal 统转 BigDecimal, null 兕底 0 */
    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        return new BigDecimal(v.toString());
    }

    /** 拼接非空字段 */
    private String joinNonEmpty(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < parts.length; i += 2) {
            if (StringUtils.hasText(parts[i + 1])) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(parts[i]).append(parts[i + 1]);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
