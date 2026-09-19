package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.common.DateUtil;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.DiseInfoReq;
import com.yb.hi.dto.MdtrtInfoReq;
import com.yb.hi.dto.doctor.VisitFinishReq;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisMedicalRecord;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.entity.outpatient.HisRegistration;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.service.OutpatientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

    public HisVisitService(OutpatientService outpatientService, HisDiagnosisService diagnosisService,
                           HisMedicalRecordService medicalRecordService) {
        this.outpatientService = outpatientService;
        this.diagnosisService = diagnosisService;
        this.medicalRecordService = medicalRecordService;
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
        v.setDeptId(reg.getDeptId());
        v.setDeptCode(reg.getDeptCode());
        v.setDeptName(reg.getDeptName());
        v.setStaffId(reg.getStaffId());
        v.setAtddrNo(reg.getAtddrNo());
        v.setDrName(reg.getDrName());
        v.setWorkDate(reg.getWorkDate() == null ? LocalDate.now() : reg.getWorkDate());
        v.setVisitStatus(1);
        save(v);
        log.info("创建候诊就诊: visitId={}, regNo={}", v.getId(), reg.getRegNo());
        return v;
    }

    /** 挂号退号时取消候诊就诊(visit_status=4) */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByRegistration(Long registrationId) {
        HisVisit v = lambdaQuery().eq(HisVisit::getRegistrationId, registrationId).one();
        if (v != null && v.getVisitStatus() != null && v.getVisitStatus() == 1) {
            v.setVisitStatus(4);
            updateById(v);
        }
    }

    /**
     * 接诊: 候诊(1) -> 接诊中(2), 记录接诊时间
     */
    @Transactional(rollbackFor = Exception.class)
    public HisVisit startVisit(Long visitId) {
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
        HisVisit v = getById(req.getVisitId());
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        if (v.getVisitStatus() != null && v.getVisitStatus() >= 3) {
            throw new BizException("该就诊已完成或已取消");
        }

        // 回写就诊主表病历字段
        v.setChiefComplaint(req.getChiefComplaint());
        v.setPresentIllness(req.getPresentIllness());
        v.setPastHistory(req.getPastHistory());
        v.setPhysicalExam(req.getPhysicalExam());
        v.setTreatmentOpinion(req.getTreatmentOpinion());
        v.setVisitStatus(3);
        v.setFinishTime(LocalDateTime.now());
        updateById(v);

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
                "既往史:", req.getPastHistory()));
        record.setObjective(req.getPhysicalExam());
        record.setAssessment(assessment);
        record.setPlan(req.getTreatmentOpinion());
        record.setDrName(v.getDrName());
        record.setDrSign(v.getDrName());
        medicalRecordService.saveRecord(record);

        // 医保2203就诊信息上传
        boolean upload = req.getUploadYb() == null || req.getUploadYb();
        if (upload && StringUtils.hasText(v.getMdtrtId())) {
            try {
                uploadVisitInfo(v, savedDiag);
            } catch (Exception e) {
                // 上传失败不阻断接诊完成, 仅记录日志
                log.warn("2203就诊信息上传失败: visitId={}, err={}", v.getId(), e.getMessage());
            }
        }
        log.info("完成接诊: visitId={}, patient={}", v.getId(), v.getPatientName());
        return v;
    }

    /** 组装并调用医保2203 */
    private void uploadVisitInfo(HisVisit v, List<HisDiagnosis> diagnoses) {
        MdtrtInfoReq mdtrt = new MdtrtInfoReq();
        mdtrt.setMdtrtId(v.getMdtrtId());
        mdtrt.setPsnNo(v.getPsnNo());
        mdtrt.setMedType("11");
        mdtrt.setBegntime(v.getVisitTime() == null ? DateUtil.currentDateTime() : DateUtil.format(v.getVisitTime()));
        mdtrt.setMainCondDscr(v.getChiefComplaint());
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
                di.setMdtrtId(v.getMdtrtId());
                di.setPsnNo(v.getPsnNo());
                di.setDiagType(d.getDiagType());
                di.setDiagSrtNo(d.getDiagSrtNo());
                di.setDiagCode(d.getDiagCode());
                di.setDiagName(d.getDiagName());
                di.setAdmCond(d.getAdmCond());
                di.setDiagDept(d.getDiagDept());
                di.setDiseDorNo(d.getDiseDorNo());
                di.setDiseDorName(d.getDiseDorName());
                di.setDiagTime(d.getDiagTime() == null ? DateUtil.currentDateTime() : DateUtil.format(d.getDiagTime()));
                di.setValiFlag(d.getValiFlag());
                di.setMaindiagFlag(d.getMaindiagFlag());
                diseList.add(di);
            }
        }
        YbResponse resp = outpatientService.uploadVisitInfo(mdtrt, diseList);
        if (resp == null || !resp.isSuccess()) {
            String err = resp == null ? "医保无响应" : resp.getErrMsg();
            throw new BizException("2203上传失败: " + err);
        }
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
