package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.doctor.HisConsent;
import com.yb.hi.entity.doctor.HisDogBiteRegister;
import com.yb.hi.entity.doctor.HisGreenChannelCredit;
import com.yb.hi.entity.doctor.HisOutpAgent;
import com.yb.hi.entity.doctor.HisReferral;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.doctor.HisConsentMapper;
import com.yb.hi.mapper.doctor.HisDogBiteRegisterMapper;
import com.yb.hi.mapper.doctor.HisGreenChannelCreditMapper;
import com.yb.hi.mapper.doctor.HisOutpAgentMapper;
import com.yb.hi.mapper.doctor.HisReferralMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 门诊诊间业务聚合服务(OP-D 14.8/14.11):
 * 知情同意书 / 代办登记 / 转诊 / 绿色通道信用 / 犬伤登记 五类业务的最小闭环。
 * 患者/医师/机构归属统一从就诊记录补全, 无就诊场景回退登录会话机构。
 */
@Slf4j
@Service
public class OutpWsBusinessService {

    private final HisVisitMapper visitMapper;
    private final HisDeptMapper deptMapper;
    private final HisConsentMapper consentMapper;
    private final HisOutpAgentMapper agentMapper;
    private final HisReferralMapper referralMapper;
    private final HisGreenChannelCreditMapper greenMapper;
    private final HisDogBiteRegisterMapper dogbiteMapper;

    public OutpWsBusinessService(HisVisitMapper visitMapper, HisDeptMapper deptMapper,
                                 HisConsentMapper consentMapper, HisOutpAgentMapper agentMapper,
                                 HisReferralMapper referralMapper, HisGreenChannelCreditMapper greenMapper,
                                 HisDogBiteRegisterMapper dogbiteMapper) {
        this.visitMapper = visitMapper;
        this.deptMapper = deptMapper;
        this.consentMapper = consentMapper;
        this.agentMapper = agentMapper;
        this.referralMapper = referralMapper;
        this.greenMapper = greenMapper;
        this.dogbiteMapper = dogbiteMapper;
    }

    /* ================= 知情同意书 ================= */

    /** 开具同意书(待签): 校验就诊 -> 补全患者/谈话医师/机构, 医师签署即落当前时间 */
    @Transactional(rollbackFor = Exception.class)
    public HisConsent consentCreate(HisConsent consent) {
        if (consent == null || consent.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        if (consent.getConsentType() == null || consent.getConsentType() < 1 || consent.getConsentType() > 5) {
            throw new BizException(400, "同意书类型仅支持 1特殊检查/2特殊治疗/3输血/4自费/5病危");
        }
        if (!StringUtils.hasText(consent.getTitle())) {
            throw new BizException(400, "同意书标题不能为空");
        }
        HisVisit visit = requireVisit(consent.getVisitId());
        consent.setId(null);
        consent.setPatientId(visit.getPatientId());
        consent.setPatientName(visit.getPatientName());
        consent.setDoctorId(visit.getStaffId());
        consent.setDoctorName(visit.getDrName());
        consent.setDoctorSignTime(LocalDateTime.now());
        consent.setSignTime(null);
        consent.setPatientSignName(null);
        consent.setStatus(1);
        consent.setOrgId(resolveOrgId(visit));
        consentMapper.insert(consent);
        log.info("开具门诊知情同意书: id={}, visitId={}, type={}", consent.getId(), visit.getId(), consent.getConsentType());
        return consent;
    }

    /** 患者/家属签署(1->2): 记录签署姓名/关系/见证人 */
    @Transactional(rollbackFor = Exception.class)
    public HisConsent consentSign(Long id, String signName, String relation, String witnessName) {
        if (!StringUtils.hasText(signName)) {
            throw new BizException(400, "签署人姓名不能为空");
        }
        HisConsent c = requireConsent(id);
        if (c.getStatus() != null && c.getStatus() != 1) {
            throw new BizException("该同意书已签署或已撤销, 不可重复签署");
        }
        c.setPatientSignName(signName.trim());
        c.setRelation(relation);
        c.setWitnessName(witnessName);
        c.setSignTime(LocalDateTime.now());
        c.setStatus(2);
        consentMapper.updateById(c);
        log.info("同意书签署完成: id={}, signName={}", id, signName);
        return c;
    }

    /** 撤销同意书(未撤销->3) */
    @Transactional(rollbackFor = Exception.class)
    public HisConsent consentCancel(Long id) {
        HisConsent c = requireConsent(id);
        if (c.getStatus() != null && c.getStatus() == 3) {
            throw new BizException("该同意书已撤销");
        }
        c.setStatus(3);
        consentMapper.updateById(c);
        return c;
    }

    public List<HisConsent> consentList(Long visitId) {
        return consentMapper.selectList(Wrappers.<HisConsent>lambdaQuery()
                .eq(HisConsent::getVisitId, visitId)
                .orderByDesc(HisConsent::getId));
    }

    /* ================= 代办登记 ================= */

    /** 代办登记: 家属/监护人代问诊留痕(姓名/关系必填) */
    @Transactional(rollbackFor = Exception.class)
    public HisOutpAgent agentCreate(HisOutpAgent agent) {
        if (agent == null || agent.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        if (!StringUtils.hasText(agent.getAgentName()) || !StringUtils.hasText(agent.getRelation())) {
            throw new BizException(400, "代办人姓名与与患者关系必填");
        }
        HisVisit visit = requireVisit(agent.getVisitId());
        agent.setId(null);
        agent.setPatientId(visit.getPatientId());
        agent.setPatientName(visit.getPatientName());
        agent.setStatus(1);
        agent.setOrgId(resolveOrgId(visit));
        agentMapper.insert(agent);
        log.info("门诊代办登记: id={}, visitId={}, agent={}", agent.getId(), visit.getId(), agent.getAgentName());
        return agent;
    }

    public List<HisOutpAgent> agentList(Long visitId) {
        return agentMapper.selectList(Wrappers.<HisOutpAgent>lambdaQuery()
                .eq(HisOutpAgent::getVisitId, visitId)
                .orderByDesc(HisOutpAgent::getId));
    }

    /* ================= 转诊登记 ================= */

    /** 转诊申请: patientId 必填, visitId 可空(慢病续方等无就诊场景) */
    @Transactional(rollbackFor = Exception.class)
    public HisReferral referralCreate(HisReferral referral) {
        if (referral == null || referral.getPatientId() == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        if (!StringUtils.hasText(referral.getToHospital())) {
            throw new BizException(400, "目标医院不能为空");
        }
        referral.setId(null);
        if (referral.getDirection() == null) {
            referral.setDirection(1);
        }
        referral.setStatus(1);
        if (referral.getVisitId() != null) {
            HisVisit visit = requireVisit(referral.getVisitId());
            referral.setPatientName(visit.getPatientName());
            referral.setOrgId(resolveOrgId(visit));
        } else {
            referral.setOrgId(currentOrgId());
        }
        referralMapper.insert(referral);
        log.info("转诊申请: id={}, patientId={}, to={}", referral.getId(), referral.getPatientId(), referral.getToHospital());
        return referral;
    }

    public List<HisReferral> referralList(Long patientId) {
        return referralMapper.selectList(Wrappers.<HisReferral>lambdaQuery()
                .eq(HisReferral::getPatientId, patientId)
                .orderByDesc(HisReferral::getId));
    }

    /** 转诊状态流转: 1已申请→2已接收→3已完成, 任一环节可→4已取消 */
    @Transactional(rollbackFor = Exception.class)
    public HisReferral referralUpdateStatus(Long id, Integer status) {
        if (status == null || status < 1 || status > 4) {
            throw new BizException(400, "状态仅支持 1已申请/2已接收/3已完成/4已取消");
        }
        HisReferral r = referralMapper.selectById(id);
        if (r == null) {
            throw new BizException(400, "转诊记录不存在");
        }
        if (r.getStatus() != null && r.getStatus() >= 3) {
            throw new BizException("该转诊已终结(完成/取消), 不可再变更");
        }
        r.setStatus(status);
        referralMapper.updateById(r);
        return r;
    }

    /* ================= 绿色通道信用 ================= */

    /** 开通绿通信用额度(先诊疗后付费): 额度>0 且同患者仅允许一条启用记录 */
    @Transactional(rollbackFor = Exception.class)
    public HisGreenChannelCredit greenCreate(HisGreenChannelCredit credit) {
        if (credit == null || credit.getPatientId() == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        if (credit.getCreditLimit() == null || credit.getCreditLimit().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "信用额度必须大于0");
        }
        Long dup = greenMapper.selectCount(Wrappers.<HisGreenChannelCredit>lambdaQuery()
                .eq(HisGreenChannelCredit::getPatientId, credit.getPatientId())
                .eq(HisGreenChannelCredit::getStatus, 1));
        if (dup != null && dup > 0) {
            throw new BizException("该患者已有启用的绿通额度, 请先关闭后再开通");
        }
        credit.setId(null);
        credit.setUsedAmount(BigDecimal.ZERO);
        credit.setStatus(1);
        if (credit.getVisitId() != null) {
            HisVisit visit = requireVisit(credit.getVisitId());
            credit.setPatientName(visit.getPatientName());
            credit.setOrgId(resolveOrgId(visit));
        } else {
            credit.setOrgId(currentOrgId());
        }
        greenMapper.insert(credit);
        log.info("绿通信用开通: id={}, patientId={}, limit={}", credit.getId(), credit.getPatientId(), credit.getCreditLimit());
        return credit;
    }

    public List<HisGreenChannelCredit> greenList(Long patientId) {
        return greenMapper.selectList(Wrappers.<HisGreenChannelCredit>lambdaQuery()
                .eq(HisGreenChannelCredit::getPatientId, patientId)
                .orderByDesc(HisGreenChannelCredit::getId));
    }

    /** 关闭绿通额度 */
    @Transactional(rollbackFor = Exception.class)
    public HisGreenChannelCredit greenRevoke(Long id) {
        HisGreenChannelCredit g = greenMapper.selectById(id);
        if (g == null) {
            throw new BizException(400, "绿通额度记录不存在");
        }
        g.setStatus(0);
        greenMapper.updateById(g);
        return g;
    }

    /* ================= 犬伤登记 ================= */

    /** 犬伤暴露登记: 分级/处置/免疫程序随访计划 */
    @Transactional(rollbackFor = Exception.class)
    public HisDogBiteRegister dogbiteCreate(HisDogBiteRegister register) {
        if (register == null || register.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        if (register.getWoundGrade() == null || register.getWoundGrade() < 1 || register.getWoundGrade() > 3) {
            throw new BizException(400, "伤口分级仅支持 1Ⅰ级/2Ⅱ级/3Ⅲ级");
        }
        HisVisit visit = requireVisit(register.getVisitId());
        register.setId(null);
        register.setPatientId(visit.getPatientId());
        register.setPatientName(visit.getPatientName());
        register.setDoctorId(visit.getStaffId());
        register.setDoctorName(visit.getDrName());
        register.setStatus(1);
        register.setOrgId(resolveOrgId(visit));
        dogbiteMapper.insert(register);
        log.info("犬伤登记: id={}, visitId={}, grade={}", register.getId(), visit.getId(), register.getWoundGrade());
        return register;
    }

    public List<HisDogBiteRegister> dogbiteList(Long visitId) {
        return dogbiteMapper.selectList(Wrappers.<HisDogBiteRegister>lambdaQuery()
                .eq(HisDogBiteRegister::getVisitId, visitId)
                .orderByDesc(HisDogBiteRegister::getId));
    }

    /* ================= 公共补全 ================= */

    private HisVisit requireVisit(Long visitId) {
        HisVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        return visit;
    }

    private HisConsent requireConsent(Long id) {
        if (id == null) {
            throw new BizException(400, "同意书ID不能为空");
        }
        HisConsent c = consentMapper.selectById(id);
        if (c == null) {
            throw new BizException(400, "同意书不存在");
        }
        return c;
    }

    /** 机构归属: 优先就诊科室归属机构, 回退登录会话机构(与住院证服务同口径) */
    private Long resolveOrgId(HisVisit visit) {
        if (visit.getDeptId() != null) {
            HisDept dept = deptMapper.selectById(visit.getDeptId());
            if (dept != null && dept.getOrgId() != null) {
                return dept.getOrgId();
            }
        }
        return currentOrgId();
    }

    private Long currentOrgId() {
        LoginUser u = UserContext.get();
        if (u == null || u.getOrgId() == null) {
            throw new BizException(403, "无法确定归属机构, 请维护科室机构归属后重试");
        }
        return u.getOrgId();
    }
}
