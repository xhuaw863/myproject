package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.doctor.MedicalCertReq;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisMedicalCert;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.doctor.HisMedicalCertMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 诊断证明服务: 开具(自动补全患者/医师/机构, 诊断缺省取就诊诊断) / 按就诊查询
 */
@Slf4j
@Service
public class HisMedicalCertService extends ServiceImpl<HisMedicalCertMapper, HisMedicalCert> {

    private final HisVisitMapper visitMapper;
    private final HisDeptMapper deptMapper;
    private final HisDiagnosisService diagnosisService;

    public HisMedicalCertService(HisVisitMapper visitMapper, HisDeptMapper deptMapper,
                                 HisDiagnosisService diagnosisService) {
        this.visitMapper = visitMapper;
        this.deptMapper = deptMapper;
        this.diagnosisService = diagnosisService;
    }

    /** 查询某次就诊的诊断证明列表 */
    public List<HisMedicalCert> listByVisit(Long visitId) {
        return lambdaQuery().eq(HisMedicalCert::getVisitId, visitId).orderByDesc(HisMedicalCert::getId).list();
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
        save(c);
        log.info("开具诊断证明: id={}, visitId={}, certType={}", c.getId(), visit.getId(), c.getCertType());
        return c;
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
     * 科室缺失或未标注机构时回退登录会话机构。
     */
    private Long resolveOrgId(HisVisit visit) {
        if (visit.getDeptId() != null) {
            HisDept dept = deptMapper.selectById(visit.getDeptId());
            if (dept != null && dept.getOrgId() != null) {
                return dept.getOrgId();
            }
        }
        LoginUser u = UserContext.get();
        return u == null ? null : u.getOrgId();
    }
}
