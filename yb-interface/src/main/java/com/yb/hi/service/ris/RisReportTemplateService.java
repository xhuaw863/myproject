package com.yb.hi.service.ris;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.ris.RisElementDTO;
import com.yb.hi.dto.ris.RisReportTemplateDTO;
import com.yb.hi.entity.ris.HisRisReportElement;
import com.yb.hi.entity.ris.HisRisReportTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.ris.HisRisReportElementMapper;
import com.yb.hi.mapper.ris.HisRisReportTemplateMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 报告模板服务(所见/结论/印象/技术描述四段模板 + 全院/科室/个人三级作用域 + 使用热度)。
 * 口径:
 * 1) 机构隔离: 创建/编辑以登录机构落 org_id; 查询为 机构模板 + org_id=0 全局种子模板 的并集;
 * 2) 三级继承: 全院(template_level=1) + 科室(level=2, owner_dept_id) + 个人(level=3, owner_staff_id),
 *    listForDoctor 一次取三级并集, 按 use_count 热度优先 + sort_order 次序;
 * 3) 数据元(elements)随模板整体替换式保存(旧逻辑删除, 新行插入), 保留审计;
 * 4) 删除为逻辑删除(模板 + 挂数据元一并软删), Mapper 查询经租户插件自动按租户过滤。
 */
@Slf4j
@Service
public class RisReportTemplateService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final HisRisReportTemplateMapper templateMapper;
    private final HisRisReportElementMapper elementMapper;
    private final OrgAccessGuard guard;

    public RisReportTemplateService(HisRisReportTemplateMapper templateMapper,
                                    HisRisReportElementMapper elementMapper,
                                    OrgAccessGuard guard) {
        this.templateMapper = templateMapper;
        this.elementMapper = elementMapper;
        this.guard = guard;
    }

    /* ================= 模板维护 ================= */

    /** 创建模板(含数据元列表): 名称/模态/科室类型必填, 编码空则服务端生成, 同租户防重。 */
    @Transactional(rollbackFor = Exception.class)
    public HisRisReportTemplate create(RisReportTemplateDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getTemplateName())) {
            throw new BizException(400, "模板名称不能为空");
        }
        if (!StringUtils.hasText(dto.getModality())) {
            throw new BizException(400, "适用模态不能为空");
        }
        if (!StringUtils.hasText(dto.getDeptType())) {
            throw new BizException(400, "适用科室类型不能为空");
        }
        Long orgId = guard.currentOrgId();
        String code = StringUtils.hasText(dto.getTemplateCode())
                ? dto.getTemplateCode().trim()
                : "TPL" + LocalDateTime.now().format(TS) + (int) (Math.random() * 90 + 10);
        Long dup = templateMapper.selectCount(Wrappers.<HisRisReportTemplate>lambdaQuery()
                .eq(HisRisReportTemplate::getTemplateCode, code));
        if (dup != null && dup > 0) {
            throw new BizException("模板编码已存在: " + code);
        }
        HisRisReportTemplate t = new HisRisReportTemplate();
        t.setOrgId(orgId);
        t.setTemplateCode(code);
        t.setTemplateName(dto.getTemplateName().trim());
        t.setModality(dto.getModality().trim().toUpperCase());
        t.setBodyPart(StringUtils.hasText(dto.getBodyPart()) ? dto.getBodyPart().trim() : null);
        t.setDeptType(dto.getDeptType().trim());
        t.setTemplateLevel(dto.getTemplateLevel() == null ? 1 : dto.getTemplateLevel());
        t.setOwnerDeptId(dto.getOwnerDeptId());
        t.setOwnerStaffId(dto.getOwnerStaffId());
        t.setFindingsTemplate(dto.getFindingsTemplate());
        t.setConclusionTemplate(dto.getConclusionTemplate());
        t.setImpressionTemplate(dto.getImpressionTemplate());
        t.setTechniqueTemplate(dto.getTechniqueTemplate());
        t.setNormalFlag(dto.getNormalFlag() == null ? 0 : dto.getNormalFlag());
        t.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
        t.setUseCount(0);
        t.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        templateMapper.insert(t);
        saveElements(t.getId(), orgId, dto.getElements());
        log.info("RIS模板创建: id={}, code={}, name={}, level={}",
                t.getId(), t.getTemplateCode(), t.getTemplateName(), t.getTemplateLevel());
        return t;
    }

    /** 更新模板(编码不允许变更; 数据元列表非空时整体替换, null 不动数据元)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisRisReportTemplate update(Long id, RisReportTemplateDTO dto) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        if (dto == null) {
            throw new BizException(400, "模板内容不能为空");
        }
        HisRisReportTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(400, "模板不存在");
        }
        if (StringUtils.hasText(dto.getTemplateName())) {
            t.setTemplateName(dto.getTemplateName().trim());
        }
        if (StringUtils.hasText(dto.getModality())) {
            t.setModality(dto.getModality().trim().toUpperCase());
        }
        if (dto.getBodyPart() != null) {
            t.setBodyPart(StringUtils.hasText(dto.getBodyPart()) ? dto.getBodyPart().trim() : null);
        }
        if (StringUtils.hasText(dto.getDeptType())) {
            t.setDeptType(dto.getDeptType().trim());
        }
        if (dto.getTemplateLevel() != null) {
            t.setTemplateLevel(dto.getTemplateLevel());
        }
        if (dto.getOwnerDeptId() != null) {
            t.setOwnerDeptId(dto.getOwnerDeptId());
        }
        if (dto.getOwnerStaffId() != null) {
            t.setOwnerStaffId(dto.getOwnerStaffId());
        }
        if (dto.getFindingsTemplate() != null) {
            t.setFindingsTemplate(dto.getFindingsTemplate());
        }
        if (dto.getConclusionTemplate() != null) {
            t.setConclusionTemplate(dto.getConclusionTemplate());
        }
        if (dto.getImpressionTemplate() != null) {
            t.setImpressionTemplate(dto.getImpressionTemplate());
        }
        if (dto.getTechniqueTemplate() != null) {
            t.setTechniqueTemplate(dto.getTechniqueTemplate());
        }
        if (dto.getNormalFlag() != null) {
            t.setNormalFlag(dto.getNormalFlag());
        }
        if (dto.getSortOrder() != null) {
            t.setSortOrder(dto.getSortOrder());
        }
        if (dto.getStatus() != null) {
            t.setStatus(dto.getStatus());
        }
        templateMapper.updateById(t);
        if (dto.getElements() != null) {
            saveElements(t.getId(), t.getOrgId(), dto.getElements());
        }
        log.info("RIS模板更新: id={}, name={}", t.getId(), t.getTemplateName());
        return templateMapper.selectById(id);
    }

    /** 逻辑删除模板(数据元一并软删, 保留审计)。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        HisRisReportTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(400, "模板不存在");
        }
        templateMapper.deleteById(id);
        elementMapper.delete(Wrappers.<HisRisReportElement>lambdaQuery()
                .eq(HisRisReportElement::getTemplateId, id));
        log.info("RIS模板删除: id={}, code={}", id, t.getTemplateCode());
    }

    /** 模板详情: 模板实体 + 挂载数据元(按段/排序)。 */
    public Map<String, Object> getById(Long id) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        HisRisReportTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(400, "模板不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", t);
        out.put("elements", listElementsByTemplate(id));
        return out;
    }

    /* ================= 查询 ================= */

    /**
     * 按模态 + 科室类型查询启用模板(机构模板 + org_id=0 全局种子并集)。
     * modality 入参为空时不过滤; 模板 modality=ALL 视为通用。
     */
    public List<HisRisReportTemplate> listByModality(String modality, String deptType) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisRisReportTemplate> qw =
                Wrappers.<HisRisReportTemplate>lambdaQuery()
                        .eq(HisRisReportTemplate::getStatus, 1);
        appendOrgScope(qw);
        if (StringUtils.hasText(modality)) {
            String m = modality.trim().toUpperCase();
            qw.and(w -> w.eq(HisRisReportTemplate::getModality, m)
                    .or().eq(HisRisReportTemplate::getModality, "ALL"));
        }
        if (StringUtils.hasText(deptType)) {
            qw.eq(HisRisReportTemplate::getDeptType, deptType.trim());
        }
        qw.orderByAsc(HisRisReportTemplate::getSortOrder)
                .orderByDesc(HisRisReportTemplate::getUseCount)
                .orderByDesc(HisRisReportTemplate::getId)
                .last("LIMIT 200");
        return templateMapper.selectList(qw);
    }

    /**
     * 医生工作台三级继承查询: 全院(1) + 本科室(2, owner_dept_id) + 本人(3, owner_staff_id) 的启用模板并集,
     * use_count 热度优先(医生高频模板置顶), 其次 sort_order。staffId/deptId 为空时仅取全院级。
     */
    public List<HisRisReportTemplate> listForDoctor(Long staffId, Long deptId, String modality) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisRisReportTemplate> qw =
                Wrappers.<HisRisReportTemplate>lambdaQuery()
                        .eq(HisRisReportTemplate::getStatus, 1)
                        .eq(HisRisReportTemplate::getTemplateLevel, 1);
        appendOrgScope(qw);
        if (deptId != null || staffId != null) {
            qw.or(w -> {
                w.eq(HisRisReportTemplate::getTemplateLevel, 2);
                if (deptId != null) {
                    w.eq(HisRisReportTemplate::getOwnerDeptId, deptId);
                }
            });
            qw.or(w -> {
                w.eq(HisRisReportTemplate::getTemplateLevel, 3);
                if (staffId != null) {
                    w.eq(HisRisReportTemplate::getOwnerStaffId, staffId);
                }
            });
        }
        if (StringUtils.hasText(modality)) {
            String m = modality.trim().toUpperCase();
            qw.and(w -> w.eq(HisRisReportTemplate::getModality, m)
                    .or().eq(HisRisReportTemplate::getModality, "ALL"));
        }
        qw.orderByDesc(HisRisReportTemplate::getUseCount)
                .orderByAsc(HisRisReportTemplate::getSortOrder)
                .orderByDesc(HisRisReportTemplate::getId)
                .last("LIMIT 200");
        return templateMapper.selectList(qw);
    }

    /** 使用次数 +1(医生选用模板时回填热度, 失败不阻断选用流程)。 */
    public void incrementUseCount(Long templateId) {
        if (templateId == null) {
            return;
        }
        try {
            templateMapper.update(null, Wrappers.<HisRisReportTemplate>lambdaUpdate()
                    .eq(HisRisReportTemplate::getId, templateId)
                    .setSql("use_count = IFNULL(use_count, 0) + 1"));
        } catch (Exception e) {
            log.warn("RIS模板使用计数失败: templateId={}, {}", templateId, e.getMessage());
        }
    }

    /* ================= 辅助 ================= */

    /** 数据元列表(按段 FINDINGS/CONCLUSION/TECHNIQUE 排序展示)。 */
    private List<HisRisReportElement> listElementsByTemplate(Long templateId) {
        return elementMapper.selectList(Wrappers.<HisRisReportElement>lambdaQuery()
                .eq(HisRisReportElement::getTemplateId, templateId)
                .orderByAsc(HisRisReportElement::getSortOrder)
                .orderByAsc(HisRisReportElement::getId));
    }

    /** 数据元整体替换: 旧行逻辑删除(保留审计), 新行重新插入。 */
    private void saveElements(Long templateId, Long orgId, List<RisElementDTO> elements) {
        elementMapper.delete(Wrappers.<HisRisReportElement>lambdaQuery()
                .eq(HisRisReportElement::getTemplateId, templateId));
        if (elements == null || elements.isEmpty()) {
            return;
        }
        List<HisRisReportElement> rows = new ArrayList<>();
        for (RisElementDTO e : elements) {
            if (e == null || !StringUtils.hasText(e.getElementCode())) {
                continue;
            }
            HisRisReportElement el = new HisRisReportElement();
            el.setOrgId(orgId);
            el.setTemplateId(templateId);
            el.setElementCode(e.getElementCode().trim());
            el.setElementName(StringUtils.hasText(e.getElementName()) ? e.getElementName().trim() : e.getElementCode().trim());
            el.setElementType(StringUtils.hasText(e.getElementType()) ? e.getElementType().trim() : "TEXT");
            el.setSection(StringUtils.hasText(e.getSection()) ? e.getSection().trim() : "FINDINGS");
            el.setValueUnit(e.getValueUnit());
            el.setValueOptions(e.getValueOptions());
            el.setDefaultValue(e.getDefaultValue());
            el.setNormalRange(e.getNormalRange());
            el.setSortOrder(e.getSortOrder() == null ? 0 : e.getSortOrder());
            el.setRequired(e.getRequired() == null ? 0 : e.getRequired());
            rows.add(el);
        }
        for (HisRisReportElement el : rows) {
            elementMapper.insert(el);
        }
    }

    /** 机构范围过滤: 登录机构模板 + org_id=0 全局种子模板并集(牵头未传 scope 时看全部)。 */
    private void appendOrgScope(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisRisReportTemplate> qw) {
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            qw.and(w -> w.eq(HisRisReportTemplate::getOrgId, scope)
                    .or().eq(HisRisReportTemplate::getOrgId, 0L));
        }
    }
}

