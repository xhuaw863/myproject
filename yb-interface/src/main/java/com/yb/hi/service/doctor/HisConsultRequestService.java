package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.doctor.ConsultReq;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.doctor.HisConsultRequest;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.doctor.HisConsultRequestMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 会诊申请服务: 发起(自动补全患者/申请科室/申请医师/机构) / 按就诊查询
 */
@Slf4j
@Service
public class HisConsultRequestService extends ServiceImpl<HisConsultRequestMapper, HisConsultRequest> {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final HisVisitMapper visitMapper;
    private final HisDeptMapper deptMapper;

    public HisConsultRequestService(HisVisitMapper visitMapper, HisDeptMapper deptMapper) {
        this.visitMapper = visitMapper;
        this.deptMapper = deptMapper;
    }

    /** 查询某次就诊的会诊申请列表 */
    public List<HisConsultRequest> listByVisit(Long visitId) {
        return lambdaQuery().eq(HisConsultRequest::getVisitId, visitId).orderByDesc(HisConsultRequest::getId).list();
    }

    /**
     * 发起会诊: 校验就诊 -> 补全患者/申请科室/申请医师/机构 -> 落库
     */
    @Transactional(rollbackFor = Exception.class)
    public HisConsultRequest create(ConsultReq req) {
        if (req == null || req.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        HisVisit visit = visitMapper.selectById(req.getVisitId());
        if (visit == null) {
            throw new BizException(400, "就诊记录不存在");
        }

        HisConsultRequest r = new HisConsultRequest();
        r.setVisitId(visit.getId());
        r.setPatientId(visit.getPatientId());
        r.setPatientName(visit.getPatientName());
        r.setApplyDeptId(visit.getDeptId());
        r.setApplyDeptName(visit.getDeptName());
        r.setApplyDrId(visit.getStaffId());
        r.setApplyDrName(visit.getDrName());
        r.setConsultDeptId(req.getConsultDeptId());
        r.setConsultDeptName(req.getConsultDeptName());
        r.setConsultPurpose(req.getConsultPurpose());
        r.setConditionSummary(req.getConditionSummary());
        r.setUrgency(req.getUrgency() == null ? 1 : req.getUrgency());
        r.setExpectedTime(parseDateTime(req.getExpectedTime()));
        r.setStatus(1);
        r.setOrgId(resolveOrgId(visit));
        save(r);
        log.info("发起会诊: id={}, visitId={}, consultDept={}", r.getId(), visit.getId(), r.getConsultDeptName());
        return r;
    }

    /**
     * 解析期望会诊时间: 兼容 yyyy-MM-dd HH:mm:ss / ISO yyyy-MM-ddTHH:mm:ss / 仅日期(取当天 00:00)
     */
    private LocalDateTime parseDateTime(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String t = text.trim().replace('T', ' ');
        int dot = t.indexOf('.');
        if (dot > 0) {
            t = t.substring(0, dot);
        }
        if (t.length() == 10) {
            return LocalDate.parse(t, DATE_FMT).atStartOfDay();
        }
        if (t.length() == 16) {
            t = t + ":00";
        }
        try {
            return LocalDateTime.parse(t, DT_FMT);
        } catch (DateTimeParseException e) {
            throw new BizException(400, "期望会诊时间格式不正确, 应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    /**
     * 机构归属: 优先取申请科室(就诊科室)归属机构(his_visit 无 org_id 列, 经 his_dept 关联);
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
