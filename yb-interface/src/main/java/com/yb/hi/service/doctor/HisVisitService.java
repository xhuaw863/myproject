package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
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
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.doctor.HisOrderMapper;
import com.yb.hi.mapper.doctor.HisPrescriptionMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientInsuMapper;
import com.yb.hi.service.OutpatientService;
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

    public HisVisitService(OutpatientService outpatientService, HisDiagnosisService diagnosisService,
                           HisMedicalRecordService medicalRecordService, HisPrescriptionMapper prescriptionMapper,
                           HisOrderMapper orderMapper, HisPatientInsuMapper patientInsuMapper) {
        this.outpatientService = outpatientService;
        this.diagnosisService = diagnosisService;
        this.medicalRecordService = medicalRecordService;
        this.prescriptionMapper = prescriptionMapper;
        this.orderMapper = orderMapper;
        this.patientInsuMapper = patientInsuMapper;
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
        YbResponse resp = outpatientService.uploadVisitInfo(mdtrt, diseList);
        if (resp == null || !resp.isSuccess()) {
            String err = resp == null ? "医保无响应" : resp.getErrMsg();
            throw new BizException("2203上传失败: " + err);
        }
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
     * 就诊费用汇总: 处方(rx)与检查/治疗单(order)各自笔数与金额, 及总金额
     */
    public Map<String, Object> getFeeSummary(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        Map<String, Object> rx = aggFee(prescriptionMapper.selectMaps(new QueryWrapper<HisPrescription>()
                .select("COUNT(*) AS cnt", "IFNULL(SUM(total_amount), 0) AS total")
                .eq("visit_id", visitId)));
        Map<String, Object> od = aggFee(orderMapper.selectMaps(new QueryWrapper<HisOrder>()
                .select("COUNT(*) AS cnt", "IFNULL(SUM(total_amount), 0) AS total")
                .eq("visit_id", visitId)));
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
