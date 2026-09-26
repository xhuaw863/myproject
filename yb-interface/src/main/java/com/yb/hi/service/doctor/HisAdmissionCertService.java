package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.doctor.AdmissionCertReq;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.doctor.HisAdmissionCert;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.doctor.HisAdmissionCertMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 住院证服务: 开具(自动补全患者/医师/机构) / 按就诊查询 / 作废
 */
@Slf4j
@Service
public class HisAdmissionCertService extends ServiceImpl<HisAdmissionCertMapper, HisAdmissionCert> {

    private final HisVisitMapper visitMapper;
    private final HisDeptMapper deptMapper;

    public HisAdmissionCertService(HisVisitMapper visitMapper, HisDeptMapper deptMapper) {
        this.visitMapper = visitMapper;
        this.deptMapper = deptMapper;
    }

    /** 查询某次就诊的住院证列表 */
    public List<HisAdmissionCert> listByVisit(Long visitId) {
        return lambdaQuery().eq(HisAdmissionCert::getVisitId, visitId).orderByDesc(HisAdmissionCert::getId).list();
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
     * 作废住院证: 状态置 3(已作废)
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
        c.setStatus(3);
        updateById(c);
        log.info("作废住院证: id={}, visitId={}", c.getId(), c.getVisitId());
        return c;
    }

    /**
     * 机构归属: 优先取就诊科室归属机构(his_visit 无 org_id 列, 经 his_dept 关联);
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
