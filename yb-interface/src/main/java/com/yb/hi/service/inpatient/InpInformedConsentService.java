package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.InformedConsentDTO;
import com.yb.hi.entity.inpatient.HisInpInformedConsent;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.inpatient.HisInpInformedConsentMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 住院知情同意书服务: 创建→签署→撤销状态机闭环(1待签 2已签 3已撤销)。
 * 状态流转均采用乐观更新(WHERE status=旧值), 并发冲突提示刷新重试。
 */
@Slf4j
@Service
public class InpInformedConsentService {

    private final HisInpInformedConsentMapper consentMapper;
    private final HisInpVisitMapper visitMapper;
    private final OrgAccessGuard guard;

    public InpInformedConsentService(HisInpInformedConsentMapper consentMapper,
                                     HisInpVisitMapper visitMapper, OrgAccessGuard guard) {
        this.consentMapper = consentMapper;
        this.visitMapper = visitMapper;
        this.guard = guard;
    }

    /** 就诊维度同意书分页(创建时间倒序) */
    public R<IPage<HisInpInformedConsent>> listByVisit(Long visitId, Page<HisInpInformedConsent> page) {
        if (visitId == null) {
            throw new BizException(400, "visitId不能为空");
        }
        Page<HisInpInformedConsent> p = page != null ? page : new Page<>(1, 10);
        IPage<HisInpInformedConsent> result = consentMapper.selectPage(p,
                new LambdaQueryWrapper<HisInpInformedConsent>()
                        .eq(HisInpInformedConsent::getInpVisitId, visitId)
                        .orderByDesc(HisInpInformedConsent::getCreateTime));
        return R.ok(result);
    }

    /** 同意书详情 */
    public R<HisInpInformedConsent> getDetail(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        HisInpInformedConsent c = consentMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "知情同意书不存在");
        }
        return R.ok(c);
    }

    /** 创建同意书(status=1待签, 机构归属: 优先就诊机构, 回退当前登录机构) */
    @Transactional(rollbackFor = Exception.class)
    public R<HisInpInformedConsent> create(InformedConsentDTO dto) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "inpVisitId不能为空");
        }
        if (dto.getConsentType() == null) {
            throw new BizException(400, "同意书类型不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(dto.getInpVisitId());
        if (visit == null) {
            throw new BizException(404, "住院就诊不存在");
        }
        HisInpInformedConsent c = new HisInpInformedConsent();
        c.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : guard.currentOrgId());
        c.setInpVisitId(dto.getInpVisitId());
        c.setConsentType(dto.getConsentType());
        c.setTitle(dto.getTitle());
        c.setTemplateId(dto.getTemplateId());
        c.setContent(dto.getContent());
        c.setDoctorId(dto.getDoctorId());
        c.setStatus(1);
        consentMapper.insert(c);
        log.info("创建知情同意书: id={}, visitId={}, type={}, title={}",
                c.getId(), dto.getInpVisitId(), dto.getConsentType(), dto.getTitle());
        return R.ok(c);
    }

    /** 签署(1待签→2已签, 同时记录患者与医师签署时间) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> sign(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        int affected = consentMapper.update(null, new LambdaUpdateWrapper<HisInpInformedConsent>()
                .eq(HisInpInformedConsent::getId, id)
                .eq(HisInpInformedConsent::getStatus, 1)
                .set(HisInpInformedConsent::getStatus, 2)
                .set(HisInpInformedConsent::getPatientSignTime, now)
                .set(HisInpInformedConsent::getDoctorSignTime, now));
        if (affected == 0) {
            throw new BizException("仅待签状态的同意书可签署, 状态已变化请刷新后重试");
        }
        log.info("知情同意书签署: id={}", id);
        return R.ok();
    }

    /** 撤销(仅已签2→3, 保留签署留痕) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> revoke(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        int affected = consentMapper.update(null, new LambdaUpdateWrapper<HisInpInformedConsent>()
                .eq(HisInpInformedConsent::getId, id)
                .eq(HisInpInformedConsent::getStatus, 2)
                .set(HisInpInformedConsent::getStatus, 3));
        if (affected == 0) {
            throw new BizException("仅已签署的同意书可撤销, 状态已变化请刷新后重试");
        }
        log.info("知情同意书撤销: id={}", id);
        return R.ok();
    }

    /** 编辑(仅待签状态可改, 类型/标题/模板/内容) */
    @Transactional(rollbackFor = Exception.class)
    public R<HisInpInformedConsent> update(Long id, InformedConsentDTO dto) {
        if (id == null || dto == null) {
            throw new BizException(400, "参数不能为空");
        }
        HisInpInformedConsent exist = consentMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "知情同意书不存在");
        }
        LambdaUpdateWrapper<HisInpInformedConsent> uw = new LambdaUpdateWrapper<HisInpInformedConsent>()
                .eq(HisInpInformedConsent::getId, id)
                .eq(HisInpInformedConsent::getStatus, 1);
        if (dto.getConsentType() != null) {
            uw.set(HisInpInformedConsent::getConsentType, dto.getConsentType());
        }
        if (dto.getTitle() != null) {
            uw.set(HisInpInformedConsent::getTitle, dto.getTitle());
        }
        if (dto.getTemplateId() != null) {
            uw.set(HisInpInformedConsent::getTemplateId, dto.getTemplateId());
        }
        if (dto.getContent() != null) {
            uw.set(HisInpInformedConsent::getContent, dto.getContent());
        }
        if (dto.getDoctorId() != null) {
            uw.set(HisInpInformedConsent::getDoctorId, dto.getDoctorId());
        }
        int affected = consentMapper.update(null, uw);
        if (affected == 0) {
            throw new BizException("仅待签状态的同意书可编辑, 状态已变化请刷新后重试");
        }
        HisInpInformedConsent latest = consentMapper.selectById(id);
        log.info("知情同意书编辑: id={}, title={}", id, latest != null ? latest.getTitle() : null);
        return R.ok(latest);
    }
}
