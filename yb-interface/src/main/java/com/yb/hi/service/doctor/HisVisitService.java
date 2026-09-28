package com.yb.hi.service.doctor;

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
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.doctor.HisOrderMapper;
import com.yb.hi.mapper.doctor.HisPrescriptionMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientInsuMapper;
import com.yb.hi.mapper.outpatient.HisRegistrationMapper;
import com.yb.hi.platform.service.DeptScopeResolver;
import com.yb.hi.service.OutpatientService;
import com.yb.hi.service.yb.UploadStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

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

    public HisVisitService(OutpatientService outpatientService, HisDiagnosisService diagnosisService,
                           HisMedicalRecordService medicalRecordService, HisPrescriptionMapper prescriptionMapper,
                           HisOrderMapper orderMapper, HisPatientInsuMapper patientInsuMapper,
                           HisRegistrationMapper registrationMapper, DeptScopeResolver deptScopeResolver,
                           UploadStatusService uploadStatusService) {
        this.outpatientService = outpatientService;
        this.diagnosisService = diagnosisService;
        this.medicalRecordService = medicalRecordService;
        this.prescriptionMapper = prescriptionMapper;
        this.orderMapper = orderMapper;
        this.patientInsuMapper = patientInsuMapper;
        this.registrationMapper = registrationMapper;
        this.deptScopeResolver = deptScopeResolver;
        this.uploadStatusService = uploadStatusService;
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
     * 完成接诊: 保存病历字段+SOAP病历+诊断 -> 就诊中(2) -> 已完成(3, 待收费) -> 可选2203上传
     */
    @Transactional(rollbackFor = Exception.class)
    public HisVisit finishVisit(VisitFinishReq req) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        requireVisitScope(req.getVisitId());
        HisVisit v = getById(req.getVisitId());
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        if (v.getVisitStatus() != null && v.getVisitStatus() >= 3) {
            throw new BizException("该就诊已完成或已取消");
        }

        // 回写就诊主表病历字段与医保扩展字段
        v.setChiefComplaint(req.getChiefComplaint());
        v.setPresentIllness(req.getPresentIllness());
        v.setPastHistory(req.getPastHistory());
        v.setAllergyHistory(req.getAllergyHistory());
        v.setPhysicalExam(req.getPhysicalExam());
        v.setAuxExam(req.getAuxExam());
        v.setTreatmentOpinion(req.getTreatmentOpinion());
        v.setDiseTypeCode(req.getDiseTypeCode());
        v.setBirctrlType(req.getBirctrlType());
        v.setBirctrlMatnDate(parseDate(req.getBirctrlMatnDate()));
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

        // 保存诊断(替换式)
        diagnosisService.saveDiagnoses(v.getId(), v.getDeptName(), v.getAtddrNo(), v.getDrName(),
                req.getDiagnoses());

        // 组装SOAP病历
        List<HisDiagnosis> savedDiag = diagnosisService.listByVisit(v.getId());
        String assessment = savedDiag.stream().map(HisDiagnosis::getDiagName)
                .filter(StringUtils::hasText).collect(Collectors.joining(","));
        HisMedicalRecord record = new HisMedicalRecord();
        record.setVisitId(v.getId());
        record.setSubjective(joinNonEmpty("主诉:", req.getChiefComplaint(), "现病史:", req.getPresentIllness(),
                "既往史:", req.getPastHistory(), "过敏史:", req.getAllergyHistory()));
        record.setObjective(joinNonEmpty("体格检查:", req.getPhysicalExam(), "辅助检查:", req.getAuxExam()));
        record.setAssessment(assessment);
        record.setPlan(req.getTreatmentOpinion());
        record.setAllergyHistory(req.getAllergyHistory());
        record.setAuxExam(req.getAuxExam());
        record.setDrName(v.getDrName());
        record.setDrSign(v.getDrName());
        medicalRecordService.saveRecord(record);

        // 医保2203就诊信息上传(M5: 结果落 his_upload_status 状态机, 失败不阻断接诊完成,
        // 由收费入口守卫强制补传 + 定时扫描指数退避重试)
        boolean upload = req.getUploadYb() == null || req.getUploadYb();
        if (upload && StringUtils.hasText(v.getMdtrtId())) {
            boolean ok = false;
            String msgid = null;
            String err = "2203未执行";
            try {
                YbResponse resp = tryUploadVisitInfo(v, savedDiag);
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
        log.info("完成接诊: visitId={}, patient={}", v.getId(), v.getPatientName());
        return v;
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
                throw new BizException("就诊已退号撤销, 无需补传");
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
        mdtrt.setMainCondDscr(v.getChiefComplaint());
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
        v.setChiefComplaint(req.getChiefComplaint());
        v.setPresentIllness(req.getPresentIllness());
        v.setPastHistory(req.getPastHistory());
        v.setAllergyHistory(req.getAllergyHistory());
        v.setPhysicalExam(req.getPhysicalExam());
        v.setAuxExam(req.getAuxExam());
        v.setTreatmentOpinion(req.getTreatmentOpinion());
        v.setDiseTypeCode(req.getDiseTypeCode());
        v.setBirctrlType(req.getBirctrlType());
        v.setBirctrlMatnDate(parseDate(req.getBirctrlMatnDate()));
        v.setFollowupDate(parseDate(req.getFollowupDate()));
        v.setFollowupNote(req.getFollowupNote());
        updateById(v);
        log.info("保存病历草稿: visitId={}", v.getId());
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
            row.put("chiefComplaint", v.getChiefComplaint());
            row.put("treatmentOpinion", v.getTreatmentOpinion());
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
