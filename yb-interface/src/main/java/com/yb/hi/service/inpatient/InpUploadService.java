package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yb.hi.common.DateUtil;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.AdmissionReq;
import com.yb.hi.dto.DischargeReq;
import com.yb.hi.dto.DiseInfoReq;
import com.yb.hi.entity.inpatient.HisInpDiagnosis;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.yb.HisUploadStatus;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.inpatient.HisInpDiagnosisMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.service.InpatientService;
import com.yb.hi.service.yb.UploadStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 住院入出院登记上报服务(批次4 M3): 把 HIS 入院/出院业务事件上报医保 2401/2402。
 * 定位为"上报"而非入院写入本身——mdtrt_id 由 1101/登记预填, 2401 不回写就诊医保ID(与 HIS 业务入院两条独立路径)。
 * 责任分工: 2401/2402 纯上传语义, 失败落 his_upload_status(status=2) 待 UploadStatusSweeper 按 biz_type 补传(INP_REG/INP_DISCH);
 * 区别于 2304/2305 UNKNOWN 归 CompTaskSweeper(资金结算语义)。
 * 说明: jdbcTemplate 手写 SQL 不走租户插件, 显式带 tenant_id AND deleted=0; 上报入口在业务事务提交后触发, 不持有行锁做网络 IO。
 */
@Slf4j
@Service
public class InpUploadService {

    private final HisInpVisitMapper visitMapper;
    private final HisInpDiagnosisMapper diagnosisMapper;
    private final InpatientService inpatientService;
    private final UploadStatusService uploadStatusService;
    private final JdbcTemplate jdbcTemplate;

    public InpUploadService(HisInpVisitMapper visitMapper, HisInpDiagnosisMapper diagnosisMapper,
                            InpatientService inpatientService, UploadStatusService uploadStatusService,
                            JdbcTemplate jdbcTemplate) {
        this.visitMapper = visitMapper;
        this.diagnosisMapper = diagnosisMapper;
        this.inpatientService = inpatientService;
        this.uploadStatusService = uploadStatusService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 2401 入院登记上报 ==================== */

    /**
     * 入院登记上报(业务事务提交后触发, 失败不阻断): 医保患者(psn_no 有值)上报 2401 并记 INP_REG;
     * 自费患者(无 psn_no)无医保入院登记语义, 跳过不上报。
     */
    public void reportAdmission(Long tenantId, Long visitId) {
        HisInpVisit visit = loadVisit(tenantId, visitId);
        if (visit == null || !StringUtils.hasText(visit.getPsnNo())) {
            return;
        }
        YbResponse resp = null;
        try {
            resp = uploadInpRegister(tenantId, visitId);
        } catch (Exception e) {
            log.warn("住院入院登记(2401)上报异常: visitId={}, 原因: {}", visitId, e.getMessage());
        }
        boolean ok = resp != null && resp.isSuccess();
        String err = resp == null ? "医保无响应" : (resp.isUnknown() ? "医保响应未知(超时)" : resp.getErrMsg());
        String msgid = ok && resp.getInfRefmsgid() != null ? resp.getInfRefmsgid() : null;
        uploadStatusService.record(tenantId, HisUploadStatus.BIZ_INP_REG, visitId, visit.getMdtrtId(), ok, msgid,
                ok ? null : err);
        if (!ok) {
            log.warn("住院入院登记(2401)上报失败已登记待补传: visitId={}, err={}", visitId, err);
        }
    }

    /**
     * 组装并调用 2401(供上报入口与 UploadStatusSweeper 补传共用): 显式租户上下文加载就诊+入院诊断, 失败不抛异常返回回执。
     * @return 医保回执; 非医保/就诊不存在返回 null
     */
    public YbResponse uploadInpRegister(Long tenantId, Long visitId) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            HisInpVisit visit = visitMapper.selectById(visitId);
            if (visit == null || !StringUtils.hasText(visit.getPsnNo())) {
                return null;
            }
            AdmissionReq req = buildAdmissionReq(visit, tenantId);
            List<DiseInfoReq> dise = buildDiseInfo(visit, diagnosisMapper.selectList(
                    new LambdaQueryWrapper<HisInpDiagnosis>()
                            .eq(HisInpDiagnosis::getInpVisitId, visitId)
                            .eq(HisInpDiagnosis::getDiagType, 1)
                            .eq(HisInpDiagnosis::getDeleted, 0)
                            .orderByAsc(HisInpDiagnosis::getSortNo)), tenantId);
            return inpatientService.admission(req, dise);
        } catch (Exception e) {
            log.warn("2401 入院上报组装/调用异常: visitId={}, 原因: {}", visitId, e.getMessage());
            return null;
        } finally {
            if (outer == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(outer);
            }
        }
    }

    /* ==================== 2402 出院办理上报 ==================== */

    /**
     * 出院办理上报(业务事务提交后触发, 失败不阻断): 医保患者(psn_no+mdtrt_id 齐全)上报 2402 并记 INP_DISCH;
     * 缺医保标识(自费或医保就诊未生成)无出院登记语义, 跳过。
     */
    public void reportDischarge(Long tenantId, Long visitId) {
        HisInpVisit visit = loadVisit(tenantId, visitId);
        if (visit == null || !StringUtils.hasText(visit.getPsnNo()) || !StringUtils.hasText(visit.getMdtrtId())) {
            return;
        }
        YbResponse resp = null;
        try {
            resp = uploadInpDischarge(tenantId, visitId);
        } catch (Exception e) {
            log.warn("住院出院办理(2402)上报异常: visitId={}, 原因: {}", visitId, e.getMessage());
        }
        boolean ok = resp != null && resp.isSuccess();
        String err = resp == null ? "医保无响应" : (resp.isUnknown() ? "医保响应未知(超时)" : resp.getErrMsg());
        String msgid = ok && resp.getInfRefmsgid() != null ? resp.getInfRefmsgid() : null;
        uploadStatusService.record(tenantId, HisUploadStatus.BIZ_INP_DISCH, visitId, visit.getMdtrtId(), ok, msgid,
                ok ? null : err);
        if (!ok) {
            log.warn("住院出院办理(2402)上报失败已登记待补传: visitId={}, err={}", visitId, err);
        }
    }

    /**
     * 组装并调用 2402(供上报入口与 UploadStatusSweeper 补传共用): 出院诊断(diagType=4)优先, 缺省回退入院诊断。
     * @return 医保回执; 非医保/无 mdtrt_id/就诊不存在返回 null
     */
    public YbResponse uploadInpDischarge(Long tenantId, Long visitId) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            HisInpVisit visit = visitMapper.selectById(visitId);
            if (visit == null || !StringUtils.hasText(visit.getPsnNo()) || !StringUtils.hasText(visit.getMdtrtId())) {
                return null;
            }
            DischargeReq req = new DischargeReq();
            req.setMdtrtId(visit.getMdtrtId());
            req.setPsnNo(visit.getPsnNo());
            req.setInsutype(visit.getInsutype());
            req.setEndtime(visit.getDischargeDate() == null ? DateUtil.currentDateTime()
                    : DateUtil.format(visit.getDischargeDate()));
            Map<String, Object> dept = lookupDept(visit.getDeptId(), tenantId);
            if (dept != null) {
                req.setDscgDeptCodg(str(dept.get("yb_dept_code")));
                req.setDscgDeptName(str(dept.get("dept_name")));
            }
            List<HisInpDiagnosis> diagnoses = diagnosisMapper.selectList(
                    new LambdaQueryWrapper<HisInpDiagnosis>()
                            .eq(HisInpDiagnosis::getInpVisitId, visitId)
                            .eq(HisInpDiagnosis::getDeleted, 0)
                            .orderByAsc(HisInpDiagnosis::getDiagType)
                            .orderByAsc(HisInpDiagnosis::getSortNo));
            HisInpDiagnosis main = pickMain(diagnoses);
            if (main != null) {
                req.setDiseCodg(main.getDiagCode());
                req.setDiseName(main.getDiagName());
            }
            List<DiseInfoReq> dise = buildDiseInfo(visit, diagnoses, tenantId);
            return inpatientService.discharge(req, dise);
        } catch (Exception e) {
            log.warn("2402 出院上报组装/调用异常: visitId={}, 原因: {}", visitId, e.getMessage());
            return null;
        } finally {
            if (outer == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(outer);
            }
        }
    }

    /* ==================== 报文组装 ==================== */

    private AdmissionReq buildAdmissionReq(HisInpVisit visit, Long tenantId) {
        AdmissionReq req = new AdmissionReq();
        req.setPsnNo(visit.getPsnNo());
        req.setInsutype(visit.getInsutype());
        req.setMedType(StringUtils.hasText(visit.getMedType()) ? visit.getMedType() : "21");
        req.setIptNo(visit.getInpNo());
        req.setBegntime(visit.getAdmitDate() == null ? DateUtil.currentDateTime() : DateUtil.format(visit.getAdmitDate()));
        req.setConerName(visit.getContactName());
        req.setTel(visit.getContactPhone());
        req.setAdmDiagDscr(visit.getAdmitDiag());
        req.setInsuplcAdmdvs(lookupInsuplcAdmdvs(visit, tenantId));
        Map<String, Object> dept = lookupDept(visit.getDeptId(), tenantId);
        if (dept != null) {
            req.setAdmDeptCodg(str(dept.get("yb_dept_code")));
            req.setAdmDeptName(str(dept.get("dept_name")));
        }
        Map<String, Object> staff = lookupStaff(visit.getDoctorId(), tenantId);
        if (staff != null) {
            req.setAtddrNo(str(staff.get("atddr_no")));
            req.setChfpdrName(str(staff.get("staff_name")));
        }
        HisInpDiagnosis main = pickMain(diagnosisMapper.selectList(
                new LambdaQueryWrapper<HisInpDiagnosis>()
                        .eq(HisInpDiagnosis::getInpVisitId, visit.getId())
                        .eq(HisInpDiagnosis::getDeleted, 0)
                        .orderByAsc(HisInpDiagnosis::getDiagType)
                        .orderByAsc(HisInpDiagnosis::getSortNo)));
        if (main != null) {
            req.setDscgMaindiagCode(main.getDiagCode());
            req.setDscgMaindiagName(main.getDiagName());
        }
        return req;
    }

    /** 住院 diseinfo: 携带 mdtrt_id/psn_no/adm_cond(2401/2402 专用, 门诊 2203 不设置) */
    private List<DiseInfoReq> buildDiseInfo(HisInpVisit visit, List<HisInpDiagnosis> diagnoses, Long tenantId) {
        List<DiseInfoReq> list = new ArrayList<>();
        if (diagnoses == null) {
            return list;
        }
        for (HisInpDiagnosis d : diagnoses) {
            DiseInfoReq di = new DiseInfoReq();
            di.setMdtrtId(visit.getMdtrtId());
            di.setPsnNo(visit.getPsnNo());
            di.setDiagType(d.getDiagType() == null ? null : String.valueOf(d.getDiagType()));
            di.setDiagSrtNo(d.getSortNo());
            di.setDiagCode(d.getDiagCode());
            di.setDiagName(d.getDiagName());
            di.setAdmCond(d.getAdmitCondition() == null ? null : String.valueOf(d.getAdmitCondition()));
            di.setMaindiagFlag(d.getIsMain() == null ? null : String.valueOf(d.getIsMain()));
            Map<String, Object> staff = lookupStaff(d.getDiagDoctorId(), tenantId);
            if (staff != null) {
                di.setDiseDorNo(str(staff.get("atddr_no")));
                di.setDiseDorName(str(staff.get("staff_name")));
            }
            di.setDiagTime(d.getDiagTime() == null ? DateUtil.currentDateTime() : DateUtil.format(d.getDiagTime()));
            di.setValiFlag("1");
            list.add(di);
        }
        return list;
    }

    /* ==================== 只读查表辅助 ==================== */

    private HisInpVisit loadVisit(Long tenantId, Long visitId) {
        Long outer = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            return visitMapper.selectById(visitId);
        } finally {
            if (outer == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(outer);
            }
        }
    }

    private String lookupInsuplcAdmdvs(HisInpVisit visit, Long tenantId) {
        if (visit.getPatientId() == null || !StringUtils.hasText(visit.getPsnNo())) {
            return null;
        }
        List<String> vals = jdbcTemplate.queryForList(
                "SELECT insuplc_admdvs FROM his_patient_insu"
                        + " WHERE patient_id = ? AND psn_no = ? AND deleted = 0 AND tenant_id = ? ORDER BY id LIMIT 1",
                String.class, visit.getPatientId(), visit.getPsnNo(), tenantId);
        return vals.isEmpty() ? null : vals.get(0);
    }

    private Map<String, Object> lookupDept(Long deptId, Long tenantId) {
        if (deptId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT dept_name, COALESCE(yb_dept_code, dept_caty) AS yb_dept_code FROM his_dept"
                        + " WHERE id = ? AND deleted = 0 AND tenant_id = ?", deptId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> lookupStaff(Long staffId, Long tenantId) {
        if (staffId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT staff_name, atddr_no FROM his_staff WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                staffId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static HisInpDiagnosis pickMain(List<HisInpDiagnosis> diagnoses) {
        if (diagnoses == null || diagnoses.isEmpty()) {
            return null;
        }
        for (HisInpDiagnosis d : diagnoses) {
            if (d.getIsMain() != null && d.getIsMain() == 1) {
                return d;
            }
        }
        return diagnoses.get(0);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
