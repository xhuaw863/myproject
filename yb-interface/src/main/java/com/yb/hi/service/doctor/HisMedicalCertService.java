package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.doctor.MedicalCertReq;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisMedicalCert;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.doctor.HisMedicalCertMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 诊断证明服务: 开具(自动补全患者/医师/机构, 诊断缺省取就诊诊断) / 按就诊查询 / 打印数据
 */
@Slf4j
@Service
public class HisMedicalCertService extends ServiceImpl<HisMedicalCertMapper, HisMedicalCert> {

    private final HisVisitMapper visitMapper;
    private final HisDeptMapper deptMapper;
    private final HisDiagnosisService diagnosisService;
    private final HisPatientMapper patientMapper;

    public HisMedicalCertService(HisVisitMapper visitMapper, HisDeptMapper deptMapper,
                                 HisDiagnosisService diagnosisService, HisPatientMapper patientMapper) {
        this.visitMapper = visitMapper;
        this.deptMapper = deptMapper;
        this.diagnosisService = diagnosisService;
        this.patientMapper = patientMapper;
    }

    /** 查询某次就诊的诊断证明列表 */
    public List<HisMedicalCert> listByVisit(Long visitId) {
        return lambdaQuery().eq(HisMedicalCert::getVisitId, visitId).orderByDesc(HisMedicalCert::getId).list();
    }

    /** 诊断证明打印数据: 证明本体 + 患者基本信息 + 类型名 + 医院名(前端 printCert 消费) */
    public Map<String, Object> printData(Long id) {
        HisMedicalCert c = getById(id);
        if (c == null) {
            throw new BizException(400, "证明不存在");
        }
        HisPatient patient = c.getPatientId() == null ? null : patientMapper.selectById(c.getPatientId());
        LoginUser user = UserContext.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hospitalName", user == null ? null : user.getTenantName());
        result.put("cert", c);
        result.put("patient", patient);
        result.put("certTypeName", certTypeName(c.getCertType()));
        return result;
    }

    /** 证明类型码翻译(与前端 CERT_TYPES 对齐) */
    private String certTypeName(Integer type) {
        if (type == null) {
            return "诊断证明书";
        }
        switch (type) {
            case 2: return "病假证明";
            case 3: return "转诊证明";
            default: return "诊断证明书";
        }
    }

    /**
     * 开具诊断证明: 校验就诊 -> 补全患者/医师/机构 -> 落库
     */
    @Transactional(rollbackFor = Exception.class)
    public HisMedicalCert create(MedicalCertReq req) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        HisVisit visit = visitMapper.selectById(req.getVisitId());
        if (visit == null) {
            throw new BizException(400, "就诊记录不存在");
        }

        HisMedicalCert c = new HisMedicalCert();
        c.setVisitId(visit.getId());
        c.setPatientId(visit.getPatientId());
        c.setPatientName(visit.getPatientName());
        c.setCertType(req.getCertType() == null ? 1 : req.getCertType());
        c.setDiagnosis(StringUtils.hasText(req.getDiagnosis()) ? req.getDiagnosis() : buildDiagnosis(visit.getId()));
        c.setCertContent(req.getCertContent());
        c.setSickLeaveDays(req.getSickLeaveDays());
        c.setRemark(req.getRemark());
        c.setIssueDrId(visit.getStaffId());
        c.setIssueDrName(visit.getDrName());
        c.setIssueTime(LocalDateTime.now());
        c.setOrgId(resolveOrgId(visit));
        // OP-B 诊断证明审核流: 按开具科室配置决定初始审核态(开关开启=1待审, 否则=0无须审核直接可打印)
        c.setAuditStatus(certAuditRequired(visit) ? 1 : 0);
        save(c);
        log.info("开具诊断证明: id={}, visitId={}, certType={}, auditStatus={}", c.getId(), visit.getId(), c.getCertType(), c.getAuditStatus());
        return c;
    }

    /**
     * 审核诊断证明(OP-B): 仅待审(1)可审核; 通过置 audit_status=2, 驳回置 3 并记录原因/审核人/时间。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisMedicalCert audit(Long id, boolean pass, String remark) {
        HisMedicalCert c = getById(id);
        if (c == null) {
            throw new BizException(400, "证明不存在");
        }
        if (c.getAuditStatus() == null || c.getAuditStatus() != 1) {
            throw new BizException(409, "仅待审核状态的证明可执行审核");
        }
        LoginUser user = UserContext.get();
        c.setAuditStatus(pass ? 2 : 3);
        c.setAuditRemark(remark);
        c.setAuditTime(LocalDateTime.now());
        if (user != null) {
            c.setAuditorId(user.getStaffId() != null ? user.getStaffId() : user.getUserId());
            c.setAuditorName(user.getRealName());
        }
        updateById(c);
        log.info("审核诊断证明: id={}, pass={}, auditStatus={}", id, pass, c.getAuditStatus());
        return c;
    }

    /** 判定开具科室是否启用诊断证明审核(his_dept.cert_audit_required=1) */
    private boolean certAuditRequired(HisVisit visit) {
        if (visit.getDeptId() == null) {
            return false;
        }
        HisDept dept = deptMapper.selectById(visit.getDeptId());
        return dept != null && Integer.valueOf(1).equals(dept.getCertAuditRequired());
    }

    /** 汇总就诊诊断名称(请求未填诊断时回填) */
    private String buildDiagnosis(Long visitId) {
        List<HisDiagnosis> ds = diagnosisService.listByVisit(visitId);
        if (CollectionUtils.isEmpty(ds)) {
            return null;
        }
        return ds.stream().map(HisDiagnosis::getDiagName)
                .filter(StringUtils::hasText).collect(Collectors.joining(","));
    }

    /**
     * 机构归属: 优先取开具科室(就诊科室)归属机构(his_visit 无 org_id 列, 经 his_dept 关联);
     * 科室缺失或未标注机构时回退登录会话机构(仍无法确定则拒绝, 不允许落 NULL 导致机构维度查询丢失)。
     */
    private Long resolveOrgId(HisVisit visit) {
        if (visit.getDeptId() != null) {
            HisDept dept = deptMapper.selectById(visit.getDeptId());
            if (dept != null && dept.getOrgId() != null) {
                return dept.getOrgId();
            }
        }
        LoginUser u = UserContext.get();
        if (u == null || u.getOrgId() == null) {
            throw new BizException(403, "无法确定文书归属机构, 请维护科室机构归属后重试");
        }
        return u.getOrgId();
    }
}
