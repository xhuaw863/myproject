package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.doctor.AdmissionCertReq;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.doctor.HisAdmissionCert;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.doctor.HisAdmissionCertMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院证服务: 开具(自动补全患者/医师/机构) / 按就诊查询 / 按患者查待入院有效证(住院登记选证) / 作废 / 打印数据
 */
@Slf4j
@Service
public class HisAdmissionCertService extends ServiceImpl<HisAdmissionCertMapper, HisAdmissionCert> {

    private final HisVisitMapper visitMapper;
    private final HisDeptMapper deptMapper;
    private final HisPatientMapper patientMapper;

    public HisAdmissionCertService(HisVisitMapper visitMapper, HisDeptMapper deptMapper, HisPatientMapper patientMapper) {
        this.visitMapper = visitMapper;
        this.deptMapper = deptMapper;
        this.patientMapper = patientMapper;
    }

    /** 查询某次就诊的住院证列表 */
    public List<HisAdmissionCert> listByVisit(Long visitId) {
        return lambdaQuery().eq(HisAdmissionCert::getVisitId, visitId).orderByDesc(HisAdmissionCert::getId).list();
    }

    /** 按患者查待入院有效证(status=1, 开具时间倒序): 住院登记页选证预填用 */
    public List<HisAdmissionCert> listPendingByPatient(Long patientId) {
        if (patientId == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        return lambdaQuery()
                .eq(HisAdmissionCert::getPatientId, patientId)
                .eq(HisAdmissionCert::getStatus, 1)
                .orderByDesc(HisAdmissionCert::getApplyTime)
                .orderByDesc(HisAdmissionCert::getId)
                .list();
    }

    /**
     * 开具住院证: 校验就诊 -> 补全患者/医师/机构 -> 落库
     */
    @Transactional(rollbackFor = Exception.class)
    public HisAdmissionCert create(AdmissionCertReq req) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        HisVisit visit = visitMapper.selectById(req.getVisitId());
        if (visit == null) {
            throw new BizException(400, "就诊记录不存在");
        }

        HisAdmissionCert c = new HisAdmissionCert();
        c.setVisitId(visit.getId());
        c.setPatientId(visit.getPatientId());
        c.setPatientName(visit.getPatientName());
        c.setAdmitDeptId(req.getAdmitDeptId());
        c.setAdmitDeptName(req.getAdmitDeptName());
        c.setAdmitDiagnosis(req.getAdmitDiagnosis());
        c.setConditionSummary(req.getConditionSummary());
        c.setAdmitPurpose(req.getAdmitPurpose());
        c.setUrgency(req.getUrgency() == null ? 1 : req.getUrgency());
        c.setStatus(1);
        c.setApplyDrId(visit.getStaffId());
        c.setApplyDrName(visit.getDrName());
        c.setApplyTime(LocalDateTime.now());
        c.setOrgId(resolveOrgId(visit));
        save(c);
        log.info("开具住院证: id={}, visitId={}, patient={}", c.getId(), visit.getId(), visit.getPatientName());
        return c;
    }

    /**
     * 作废住院证: 状态置 3(已作废); 已入院(2)的证不可作废(已被住院登记消费, 溯源已建立)
     */
    @Transactional(rollbackFor = Exception.class)
    public HisAdmissionCert cancel(Long id) {
        if (id == null) {
            throw new BizException(400, "住院证ID不能为空");
        }
        HisAdmissionCert c = getById(id);
        if (c == null) {
            throw new BizException(400, "住院证不存在");
        }
        if (c.getStatus() != null && c.getStatus() == 3) {
            throw new BizException("该住院证已作废");
        }
        if (c.getStatus() != null && c.getStatus() == 2) {
            throw new BizException("该住院证已办理入院, 不能作废");
        }
        c.setStatus(3);
        updateById(c);
        log.info("作废住院证: id={}, visitId={}", c.getId(), c.getVisitId());
        return c;
    }

    /**
     * 住院证打印数据: 证本体 + 患者基本信息 + 就诊临床摘要(主诉/现病史/查体/辅检/过敏史) + 医院名
     * (与处方笺 printData 同构, 前端 printAdmissionCert 消费规范版式)
     */
    public Map<String, Object> printData(Long id) {
        HisAdmissionCert cert = getById(id);
        if (cert == null) {
            throw new BizException(400, "住院证不存在");
        }
        HisPatient patient = cert.getPatientId() == null ? null : patientMapper.selectById(cert.getPatientId());
        HisVisit visit = cert.getVisitId() == null ? null : visitMapper.selectById(cert.getVisitId());
        LoginUser user = UserContext.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hospitalName", user == null ? null : user.getTenantName());
        result.put("cert", cert);
        result.put("admission", cert);
        result.put("patient", patient);
        result.put("visit", visit);
        // 方案 B(B2): 结构化病历时 his_visit SOAP 列为空, 附派生 soap 供住院证病情摘要取数(历史文本病历自动回退旧列)
        if (visit != null) {
            result.put("soap", EmrStructureReader.read(visit));
        }
        return result;
    }

    /**
     * 机构归属: 优先取就诊科室归属机构(his_visit 无 org_id 列, 经 his_dept 关联);
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
