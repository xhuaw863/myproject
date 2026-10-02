package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.inpatient.OrderTemplateDTO;
import com.yb.hi.entity.inpatient.HisOrderTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisOrderTemplateMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 住院医嘱模板/套餐服务: 个人(1)/科室(2)/全院(3)三级模板的维护与检索, items 序列化为 JSON 落库。
 * 模板使用次数 usageCount 供开嘱侧 incrementUsage 递增, 列表按使用热度排序。
 */
@Slf4j
@Service
public class OrderTemplateService {

    private final HisOrderTemplateMapper templateMapper;
    private final OrgAccessGuard guard;

    public OrderTemplateService(HisOrderTemplateMapper templateMapper, OrgAccessGuard guard) {
        this.templateMapper = templateMapper;
        this.guard = guard;
    }

    /** 模板分页(templateType/scopeType/deptId/doctorId/status/applyScene/surgeryPhase/关键字 可选, 使用热度倒序) */
    public R<IPage<HisOrderTemplate>> list(Integer templateType, Integer scopeType, Long deptId,
                                           Long doctorId, Integer status, String keyword,
                                           Integer applyScene, Integer surgeryPhase, Page<HisOrderTemplate> page) {
        Page<HisOrderTemplate> p = page != null ? page : new Page<>(1, 10);
        LambdaQueryWrapper<HisOrderTemplate> qw = new LambdaQueryWrapper<HisOrderTemplate>()
                // 机构隔离: 仅本机构可见(含个人/科室/全院三级, 全院=本机构全院, 不跨机构共享; 与路径模板同语义)
                .eq(HisOrderTemplate::getOrgId, guard.currentOrgId())
                .orderByDesc(HisOrderTemplate::getUsageCount)
                .orderByDesc(HisOrderTemplate::getCreateTime);
        if (templateType != null) {
            qw.eq(HisOrderTemplate::getTemplateType, templateType);
        }
        if (scopeType != null) {
            qw.eq(HisOrderTemplate::getScopeType, scopeType);
        }
        if (deptId != null) {
            qw.eq(HisOrderTemplate::getDeptId, deptId);
        }
        if (doctorId != null) {
            qw.eq(HisOrderTemplate::getDoctorId, doctorId);
        }
        if (status != null) {
            // 开嘱侧选择器传 status=1: 停用模板不应出现在模板/套餐引用列表
            qw.eq(HisOrderTemplate::getStatus, status);
        }
        if (applyScene != null) {
            // 手术模板选择器传 applyScene=2; 普通住院开嘱传 1 或空
            qw.eq(HisOrderTemplate::getApplyScene, applyScene);
        }
        if (surgeryPhase != null) {
            qw.eq(HisOrderTemplate::getSurgeryPhase, surgeryPhase);
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(HisOrderTemplate::getTemplateName, keyword);
        }
        IPage<HisOrderTemplate> result = templateMapper.selectPage(p, qw);
        return R.ok(result);
    }

    /** 模板详情 */
    public R<HisOrderTemplate> getDetail(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        HisOrderTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(404, "医嘱模板不存在");
        }
        return R.ok(t);
    }

    /** 创建模板(status默认启用1, items序列化为JSON; 个人模板医生归属当前登录职工) */
    @Transactional(rollbackFor = Exception.class)
    public R<HisOrderTemplate> create(OrderTemplateDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getTemplateName())) {
            throw new BizException(400, "模板名称不能为空");
        }
        HisOrderTemplate t = new HisOrderTemplate();
        t.setOrgId(guard.currentOrgId());
        t.setTemplateName(dto.getTemplateName());
        t.setTemplateType(dto.getTemplateType() != null ? dto.getTemplateType() : 1);
        t.setScopeType(dto.getScopeType() != null ? dto.getScopeType() : 1);
        t.setDeptId(dto.getDeptId());
        t.setDoctorId(dto.getDoctorId() != null ? dto.getDoctorId() : currentStaffId());
        if (dto.getItems() != null) {
            t.setItems(JSON.toJSONString(dto.getItems()));
        }
        t.setDiseaseCode(dto.getDiseaseCode());
        t.setApplyScene(dto.getApplyScene() != null ? dto.getApplyScene() : 1);
        t.setSurgeryPhase(dto.getSurgeryPhase());
        t.setUsageCount(0);
        t.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        templateMapper.insert(t);
        log.info("创建医嘱模板: id={}, name={}, type={}, scope={}",
                t.getId(), t.getTemplateName(), t.getTemplateType(), t.getScopeType());
        return R.ok(t);
    }

    /** 编辑模板(个人模板仅归属医生本人可改, null字段不覆盖) */
    @Transactional(rollbackFor = Exception.class)
    public R<HisOrderTemplate> update(Long id, OrderTemplateDTO dto) {
        if (id == null || dto == null) {
            throw new BizException(400, "参数不能为空");
        }
        HisOrderTemplate exist = templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "医嘱模板不存在");
        }
        requireMutable(exist);
        if (StringUtils.hasText(dto.getTemplateName())) {
            exist.setTemplateName(dto.getTemplateName());
        }
        if (dto.getTemplateType() != null) {
            exist.setTemplateType(dto.getTemplateType());
        }
        if (dto.getScopeType() != null) {
            exist.setScopeType(dto.getScopeType());
        }
        if (dto.getDeptId() != null) {
            exist.setDeptId(dto.getDeptId());
        }
        if (dto.getItems() != null) {
            exist.setItems(JSON.toJSONString(dto.getItems()));
        }
        if (dto.getDiseaseCode() != null) {
            exist.setDiseaseCode(dto.getDiseaseCode());
        }
        if (dto.getApplyScene() != null) {
            exist.setApplyScene(dto.getApplyScene());
        }
        if (dto.getSurgeryPhase() != null) {
            exist.setSurgeryPhase(dto.getSurgeryPhase());
        }
        if (dto.getStatus() != null) {
            exist.setStatus(dto.getStatus());
        }
        templateMapper.updateById(exist);
        log.info("编辑医嘱模板: id={}, name={}", id, exist.getTemplateName());
        return R.ok(exist);
    }

    /** 删除模板(逻辑删除; 个人模板仅归属医生本人可删) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> remove(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        HisOrderTemplate exist = templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "医嘱模板不存在");
        }
        requireMutable(exist);
        templateMapper.deleteById(id);
        log.info("删除医嘱模板: id={}, name={}", id, exist.getTemplateName());
        return R.ok();
    }

    /** 使用次数+1(开嘱引用模板时调用, 原子自增) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> incrementUsage(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        int affected = templateMapper.update(null, new LambdaUpdateWrapper<HisOrderTemplate>()
                .eq(HisOrderTemplate::getId, id)
                .setSql("usage_count = IFNULL(usage_count, 0) + 1"));
        if (affected == 0) {
            throw new BizException(404, "医嘱模板不存在");
        }
        return R.ok();
    }

    /** 我的常用模板(个人级, 使用热度倒序) */
    public R<List<HisOrderTemplate>> getMyTemplates(Long doctorId) {
        Long owner = doctorId != null ? doctorId : currentStaffId();
        List<HisOrderTemplate> list = templateMapper.selectList(new LambdaQueryWrapper<HisOrderTemplate>()
                .eq(HisOrderTemplate::getTemplateType, 1)
                .eq(HisOrderTemplate::getDoctorId, owner)
                .eq(HisOrderTemplate::getStatus, 1)
                .orderByDesc(HisOrderTemplate::getUsageCount)
                .orderByDesc(HisOrderTemplate::getCreateTime));
        return R.ok(list);
    }

    /** 个人模板仅归属医生本人可改删; 科室/全院模板放开 */
    private void requireMutable(HisOrderTemplate t) {
        if (t.getTemplateType() != null && t.getTemplateType() == 1) {
            if (!currentStaffId().equals(t.getDoctorId())) {
                throw new BizException(403, "个人模板仅归属医生本人可维护");
            }
        }
    }

    /** 当前登录职工ID(无职工关联的账号不能维护个人模板) */
    private Long currentStaffId() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.getStaffId() == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法维护医嘱模板");
        }
        return lu.getStaffId();
    }
}
