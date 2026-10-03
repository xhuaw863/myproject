package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisEmrDrawingTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.inpatient.EmrDrawingTemplateMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 医学图示模板服务: 维护 SVG 人体/部位标注底图(his_emr_drawing_template, 类别 body_front/body_back/head/oral/hand/foot/wound/custom)。
 *
 * 约定:
 * - 写操作(新建/更新/删除)仅牵头机构管理员({@link OrgAccessGuard#requireLeadOrg}), 新建行绑定当前机构;
 * - 列表接口不返回 svgTemplate 大字段(仅元数据, 供选择器渲染), 详情接口才带 SVG 源串;
 * - 模板编码租户内唯一(应用层预检, 友好 400); 模板数量少, 列表不分页。
 */
@Slf4j
@Service
public class EmrDrawingTemplateService {

    private final EmrDrawingTemplateMapper templateMapper;
    private final OrgAccessGuard guard;

    public EmrDrawingTemplateService(EmrDrawingTemplateMapper templateMapper, OrgAccessGuard guard) {
        this.templateMapper = templateMapper;
        this.guard = guard;
    }

    /* ================= 查询 ================= */

    /** 图示模板列表(category 可选精确过滤, keyword 模糊匹配编码/名称; 按类别→ID 升序; 不含 svgTemplate 大字段) */
    public R<List<HisEmrDrawingTemplate>> list(String category, String keyword) {
        LambdaQueryWrapper<HisEmrDrawingTemplate> qw = Wrappers.<HisEmrDrawingTemplate>lambdaQuery()
                .eq(StringUtils.hasText(category), HisEmrDrawingTemplate::getCategory,
                        category == null ? null : category.trim())
                .select(HisEmrDrawingTemplate.class, c -> !"svg_template".equals(c.getColumn()));
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like(HisEmrDrawingTemplate::getCode, kw).or().like(HisEmrDrawingTemplate::getTitle, kw));
        }
        qw.orderByAsc(HisEmrDrawingTemplate::getCategory).orderByAsc(HisEmrDrawingTemplate::getId);
        return R.ok(templateMapper.selectList(qw));
    }

    /** 模板详情(含 svgTemplate SVG 源串) */
    public R<HisEmrDrawingTemplate> get(Long id) {
        HisEmrDrawingTemplate t = id == null ? null : templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(400, "医学图示模板不存在");
        }
        return R.ok(t);
    }

    /* ================= 维护 ================= */

    /** 新建/更新模板(id 空=新建并绑定当前机构; 编码租户内唯一; 仅牵头机构管理员) */
    public R<HisEmrDrawingTemplate> save(HisEmrDrawingTemplate in) {
        if (in == null) {
            throw new BizException(400, "图示模板内容不能为空");
        }
        guard.requireLeadOrg("仅牵头机构管理员可维护医学图示模板");
        if (!StringUtils.hasText(in.getCode())) {
            throw new BizException(400, "模板编码不能为空");
        }
        if (!StringUtils.hasText(in.getTitle())) {
            throw new BizException(400, "模板名称不能为空");
        }
        String code = in.getCode().trim();
        if (in.getId() == null) {
            if (!StringUtils.hasText(in.getCategory())) {
                throw new BizException(400, "模板类别不能为空");
            }
            if (!StringUtils.hasText(in.getSvgTemplate())) {
                throw new BizException(400, "SVG模板内容不能为空");
            }
            ensureCodeAvailable(code, null);
            HisEmrDrawingTemplate t = new HisEmrDrawingTemplate();
            t.setOrgId(guard.currentOrgId());
            t.setCode(code);
            t.setTitle(in.getTitle().trim());
            t.setCategory(in.getCategory().trim());
            t.setSvgTemplate(in.getSvgTemplate());
            t.setDescription(in.getDescription());
            t.setStatus(in.getStatus() != null ? in.getStatus() : 1);
            templateMapper.insert(t);
            log.info("新建医学图示模板: id={}, code={}, category={}", t.getId(), code, t.getCategory());
            return R.ok(templateMapper.selectById(t.getId()));
        }
        HisEmrDrawingTemplate exist = templateMapper.selectById(in.getId());
        if (exist == null) {
            throw new BizException(400, "医学图示模板不存在");
        }
        if (!code.equals(exist.getCode())) {
            ensureCodeAvailable(code, exist.getId());
            exist.setCode(code);
        }
        exist.setTitle(in.getTitle().trim());
        if (StringUtils.hasText(in.getCategory())) {
            exist.setCategory(in.getCategory().trim());
        }
        if (in.getSvgTemplate() != null) {
            exist.setSvgTemplate(in.getSvgTemplate());
        }
        if (in.getDescription() != null) {
            exist.setDescription(in.getDescription());
        }
        if (in.getStatus() != null) {
            exist.setStatus(in.getStatus());
        }
        templateMapper.updateById(exist);
        log.info("更新医学图示模板: id={}, code={}", exist.getId(), exist.getCode());
        return R.ok(templateMapper.selectById(exist.getId()));
    }

    /** 删除模板(逻辑删除; 仅牵头机构管理员) */
    public R<Void> delete(Long id) {
        guard.requireLeadOrg("仅牵头机构管理员可维护医学图示模板");
        HisEmrDrawingTemplate exist = id == null ? null : templateMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "医学图示模板不存在");
        }
        templateMapper.deleteById(id);
        log.info("删除医学图示模板: id={}, code={}", id, exist.getCode());
        return R.ok();
    }

    /* ================= 内部实现 ================= */

    /** 模板编码租户内查重(逻辑删除行不参与) */
    private void ensureCodeAvailable(String code, Long excludeId) {
        boolean dup = !templateMapper.selectList(Wrappers.<HisEmrDrawingTemplate>lambdaQuery()
                .eq(HisEmrDrawingTemplate::getCode, code)
                .ne(excludeId != null, HisEmrDrawingTemplate::getId, excludeId)).isEmpty();
        if (dup) {
            throw new BizException(400, "模板编码已存在: " + code);
        }
    }
}
