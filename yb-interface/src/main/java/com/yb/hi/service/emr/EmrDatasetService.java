package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.inpatient.HisEmrDataset;
import com.yb.hi.entity.inpatient.HisEmrDatasetElement;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.inpatient.EmrDatasetElementMapper;
import com.yb.hi.mapper.inpatient.EmrDatasetMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 病历数据集服务: 维护"章节→小节→数据元"三级结构定义(his_emr_dataset / his_emr_dataset_element),
 * 供病历模板挂载(dataset_id)与结构化书写取元。
 *
 * 约定:
 * - 写操作(新建/更新/删除/排序/克隆)仅牵头机构管理员({@link OrgAccessGuard#requireLeadOrg}),
 *   新建行绑定数据集机构; 读操作受 MyBatis-Plus 租户插件隔离(tenant_id 自动注入/过滤, 实体不显式映射);
 * - 数据集编码租户内唯一(应用层预检, 友好 400); 数据元 field_key 同一数据集内唯一(跨数据集可复用标准键);
 * - get/listElements 返回 章节→小节→数据元 树(章节/小节按 sort_no 首现顺序, 章节直属数据元挂 elements);
 * - 删除数据集级联逻辑删除其数据元, 避免悬挂; 克隆为深拷贝(数据元全部字段复制, sort_no 保序)。
 */
@Slf4j
@Service
public class EmrDatasetService {

    private final EmrDatasetMapper datasetMapper;
    private final EmrDatasetElementMapper elementMapper;
    private final OrgAccessGuard guard;

    public EmrDatasetService(EmrDatasetMapper datasetMapper,
                             EmrDatasetElementMapper elementMapper,
                             OrgAccessGuard guard) {
        this.datasetMapper = datasetMapper;
        this.elementMapper = elementMapper;
        this.guard = guard;
    }

    /* ================= 数据集查询 ================= */

    /** 数据集分页列表(keyword 模糊匹配编码/名称, scope 可选: 0全部 1住院 2门诊 3护理; 按适用范围→ID 升序) */
    public R<IPage<HisEmrDataset>> list(String keyword, Integer scope, long page, long size) {
        LambdaQueryWrapper<HisEmrDataset> qw = Wrappers.<HisEmrDataset>lambdaQuery()
                .eq(scope != null, HisEmrDataset::getScope, scope);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like(HisEmrDataset::getCode, kw).or().like(HisEmrDataset::getName, kw));
        }
        qw.orderByAsc(HisEmrDataset::getScope).orderByAsc(HisEmrDataset::getId);
        long p = page <= 0 ? 1 : page;
        long s = size <= 0 ? 20 : Math.min(size, 200);
        return R.ok(datasetMapper.selectPage(new Page<>(p, s), qw));
    }

    /**
     * 数据集详情: {dataset, chapters:[{chapterKey, chapterName, elements:[章节直属数据元], sections:[{sectionKey, sectionName, elements:[...]}]}]}。
     */
    public R<Map<String, Object>> get(Long id) {
        HisEmrDataset ds = id == null ? null : datasetMapper.selectById(id);
        if (ds == null) {
            throw new BizException(400, "病历数据集不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", ds);
        out.put("chapters", buildTree(loadElements(id)));
        return R.ok(out);
    }

    /** 数据集全部数据元(按章节→小节分组, 组内按 sort_no 升序) */
    public R<List<Map<String, Object>>> listElements(Long datasetId) {
        HisEmrDataset ds = datasetId == null ? null : datasetMapper.selectById(datasetId);
        if (ds == null) {
            throw new BizException(400, "病历数据集不存在");
        }
        return R.ok(buildTree(loadElements(datasetId)));
    }

    /* ================= 数据集维护 ================= */

    /** 新建/更新数据集(id 为空=新建并绑定当前机构; 编码租户内唯一; 仅牵头机构管理员) */
    public R<HisEmrDataset> save(HisEmrDataset dataset) {
        if (dataset == null) {
            throw new BizException(400, "数据集内容不能为空");
        }
        guard.requireLeadOrg("仅牵头机构管理员可维护病历数据集");
        if (!StringUtils.hasText(dataset.getCode())) {
            throw new BizException(400, "数据集编码不能为空");
        }
        if (!StringUtils.hasText(dataset.getName())) {
            throw new BizException(400, "数据集名称不能为空");
        }
        String code = dataset.getCode().trim();
        if (dataset.getId() == null) {
            ensureCodeAvailable(code, null);
            HisEmrDataset d = new HisEmrDataset();
            d.setOrgId(guard.currentOrgId());
            d.setCode(code);
            d.setName(dataset.getName().trim());
            d.setScope(dataset.getScope() != null ? dataset.getScope() : 0);
            d.setDescription(dataset.getDescription());
            d.setStatus(dataset.getStatus() != null ? dataset.getStatus() : 1);
            datasetMapper.insert(d);
            log.info("新建病历数据集: id={}, code={}, name={}", d.getId(), code, d.getName());
            return R.ok(datasetMapper.selectById(d.getId()));
        }
        HisEmrDataset exist = datasetMapper.selectById(dataset.getId());
        if (exist == null) {
            throw new BizException(400, "病历数据集不存在");
        }
        if (!code.equals(exist.getCode())) {
            ensureCodeAvailable(code, exist.getId());
            exist.setCode(code);
        }
        exist.setName(dataset.getName().trim());
        if (dataset.getScope() != null) {
            exist.setScope(dataset.getScope());
        }
        if (dataset.getDescription() != null) {
            exist.setDescription(dataset.getDescription());
        }
        if (dataset.getStatus() != null) {
            exist.setStatus(dataset.getStatus());
        }
        datasetMapper.updateById(exist);
        log.info("更新病历数据集: id={}, code={}", exist.getId(), exist.getCode());
        return R.ok(datasetMapper.selectById(exist.getId()));
    }

    /** 删除数据集(逻辑删除; 级联逻辑删除其全部数据元, 避免悬挂) */
    public R<Void> delete(Long id) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历数据集");
        HisEmrDataset exist = id == null ? null : datasetMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "病历数据集不存在");
        }
        elementMapper.delete(Wrappers.<HisEmrDatasetElement>lambdaQuery()
                .eq(HisEmrDatasetElement::getDatasetId, id));
        datasetMapper.deleteById(id);
        log.info("删除病历数据集: id={}, code={}(级联删除数据元)", id, exist.getCode());
        return R.ok();
    }

    /* ================= 数据元维护 ================= */

    /** 新建/更新数据元(id 为空=新建, 默认 sort_no 追加到数据集末尾; 同数据集内 field_key 唯一; 仅牵头机构管理员) */
    public R<HisEmrDatasetElement> saveElement(HisEmrDatasetElement element) {
        if (element == null) {
            throw new BizException(400, "数据元内容不能为空");
        }
        guard.requireLeadOrg("仅牵头机构管理员可维护病历数据集");
        if (element.getId() == null) {
            return R.ok(createElement(element));
        }
        return R.ok(updateElement(element));
    }

    /** 删除数据元(逻辑删除; 仅牵头机构管理员) */
    public R<Void> deleteElement(Long id) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历数据集");
        HisEmrDatasetElement exist = id == null ? null : elementMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "数据元不存在");
        }
        elementMapper.deleteById(id);
        log.info("删除数据集数据元: id={}, fieldKey={}", id, exist.getFieldKey());
        return R.ok();
    }

    /** 批量更新数据元排序号: items=[{id, sortNo}, ...](仅牵头机构管理员) */
    public R<Void> reorderElements(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException(400, "排序列表不能为空");
        }
        guard.requireLeadOrg("仅牵头机构管理员可维护病历数据集");
        int updated = 0;
        for (Map<String, Object> item : items) {
            Long id = item == null ? null : toLong(item.get("id"));
            Integer sortNo = item == null ? null : toInt(item.get("sortNo"));
            if (id == null || sortNo == null) {
                throw new BizException(400, "排序项缺少id或sortNo");
            }
            HisEmrDatasetElement patch = new HisEmrDatasetElement();
            patch.setId(id);
            patch.setSortNo(sortNo);
            updated += elementMapper.updateById(patch);
        }
        log.info("病历数据集数据元排序更新: {}条", updated);
        return R.ok();
    }

    /* ================= 克隆 ================= */

    /** 克隆数据集(深拷贝数据集与其全部数据元); newCode 空则自动 srcCode_COPY[/N] 避让, newName 空则"原名(副本)" */
    @Transactional(rollbackFor = Exception.class)
    public R<HisEmrDataset> cloneDataset(Long sourceId, String newCode, String newName) {
        guard.requireLeadOrg("仅牵头机构管理员可维护病历数据集");
        if (sourceId == null) {
            throw new BizException(400, "源数据集ID不能为空");
        }
        HisEmrDataset src = datasetMapper.selectById(sourceId);
        if (src == null) {
            throw new BizException(400, "源数据集不存在");
        }
        String code;
        if (StringUtils.hasText(newCode)) {
            code = newCode.trim();
            ensureCodeAvailable(code, null);
        } else {
            String base = src.getCode() + "_COPY";
            code = base;
            int n = 2;
            while (codeExists(code, null) && n <= 50) {
                code = base + n;
                n++;
            }
            if (codeExists(code, null)) {
                throw new BizException(400, "无法自动生成可用副本编码, 请指定新编码");
            }
        }
        HisEmrDataset d = new HisEmrDataset();
        d.setOrgId(guard.currentOrgId());
        d.setCode(code);
        d.setName(StringUtils.hasText(newName) ? newName.trim() : src.getName() + "(副本)");
        d.setScope(src.getScope());
        d.setDescription(src.getDescription());
        d.setStatus(src.getStatus() != null ? src.getStatus() : 1);
        datasetMapper.insert(d);

        List<HisEmrDatasetElement> srcElements = loadElements(src.getId());
        for (HisEmrDatasetElement s : srcElements) {
            HisEmrDatasetElement c = new HisEmrDatasetElement();
            c.setOrgId(d.getOrgId());
            c.setDatasetId(d.getId());
            c.setChapterKey(s.getChapterKey());
            c.setChapterName(s.getChapterName());
            c.setSectionKey(s.getSectionKey());
            c.setSectionName(s.getSectionName());
            c.setFieldKey(s.getFieldKey());
            c.setFieldName(s.getFieldName());
            c.setFieldType(s.getFieldType());
            c.setDictSource(s.getDictSource());
            c.setDefaultValue(s.getDefaultValue());
            c.setRequired(s.getRequired());
            c.setReadonly(s.getReadonly());
            c.setNoCopy(s.getNoCopy());
            c.setPrintHidden(s.getPrintHidden());
            c.setMaxLength(s.getMaxLength());
            c.setValidationRule(s.getValidationRule());
            c.setSortNo(s.getSortNo());
            elementMapper.insert(c);
        }
        log.info("克隆病历数据集: srcId={} -> id={}, code={}, 数据元{}条", sourceId, d.getId(), code, srcElements.size());
        return R.ok(datasetMapper.selectById(d.getId()));
    }

    /* ================= 内部实现 ================= */

    /** 新建数据元: 必填校验 + 同数据集 field_key 唯一 + 默认值填充 + 机构跟随数据集 */
    private HisEmrDatasetElement createElement(HisEmrDatasetElement in) {
        if (in.getDatasetId() == null) {
            throw new BizException(400, "所属数据集不能为空");
        }
        HisEmrDataset ds = datasetMapper.selectById(in.getDatasetId());
        if (ds == null) {
            throw new BizException(400, "所属数据集不存在");
        }
        String chapterKey = requireText(in.getChapterKey(), "章节key不能为空");
        String chapterName = requireText(in.getChapterName(), "章节名称不能为空");
        String fieldKey = requireText(in.getFieldKey(), "数据元key不能为空");
        String fieldName = requireText(in.getFieldName(), "数据元名称不能为空");
        ensureFieldKeyAvailable(in.getDatasetId(), fieldKey, null);

        HisEmrDatasetElement e = new HisEmrDatasetElement();
        e.setOrgId(ds.getOrgId());
        e.setDatasetId(in.getDatasetId());
        e.setChapterKey(chapterKey);
        e.setChapterName(chapterName);
        e.setSectionKey(trimToNull(in.getSectionKey()));
        e.setSectionName(trimToNull(in.getSectionName()));
        e.setFieldKey(fieldKey);
        e.setFieldName(fieldName);
        e.setFieldType(StringUtils.hasText(in.getFieldType()) ? in.getFieldType().trim() : "text");
        e.setDictSource(trimToNull(in.getDictSource()));
        e.setDefaultValue(in.getDefaultValue());
        e.setRequired(in.getRequired() != null ? in.getRequired() : 0);
        e.setReadonly(in.getReadonly() != null ? in.getReadonly() : 0);
        e.setNoCopy(in.getNoCopy() != null ? in.getNoCopy() : 0);
        e.setPrintHidden(in.getPrintHidden() != null ? in.getPrintHidden() : 0);
        e.setMaxLength(in.getMaxLength());
        e.setValidationRule(in.getValidationRule());
        e.setSortNo(in.getSortNo() != null ? in.getSortNo() : nextSortNo(in.getDatasetId()));
        elementMapper.insert(e);
        log.info("新增数据集数据元: datasetId={}, fieldKey={}", e.getDatasetId(), e.getFieldKey());
        return e;
    }

    /** 更新数据元: null=不修改; 允许迁移到其他数据集(重校验 field_key 唯一并跟随机构); 章节/名称类必填项非空校验 */
    private HisEmrDatasetElement updateElement(HisEmrDatasetElement in) {
        HisEmrDatasetElement exist = elementMapper.selectById(in.getId());
        if (exist == null) {
            throw new BizException(400, "数据元不存在");
        }
        Long targetDatasetId = in.getDatasetId() != null ? in.getDatasetId() : exist.getDatasetId();
        HisEmrDataset ds = datasetMapper.selectById(targetDatasetId);
        if (ds == null) {
            throw new BizException(400, "所属数据集不存在");
        }
        boolean datasetChanged = !targetDatasetId.equals(exist.getDatasetId());
        String fieldKey = StringUtils.hasText(in.getFieldKey()) ? in.getFieldKey().trim() : exist.getFieldKey();
        if (datasetChanged || !fieldKey.equals(exist.getFieldKey())) {
            ensureFieldKeyAvailable(targetDatasetId, fieldKey, exist.getId());
        }
        if (datasetChanged) {
            exist.setDatasetId(targetDatasetId);
            exist.setOrgId(ds.getOrgId());
        }
        exist.setFieldKey(fieldKey);
        if (in.getChapterKey() != null) {
            exist.setChapterKey(requireText(in.getChapterKey(), "章节key不能为空"));
        }
        if (in.getChapterName() != null) {
            exist.setChapterName(requireText(in.getChapterName(), "章节名称不能为空"));
        }
        if (in.getSectionKey() != null) {
            exist.setSectionKey(trimToNull(in.getSectionKey()));
        }
        if (in.getSectionName() != null) {
            exist.setSectionName(trimToNull(in.getSectionName()));
        }
        if (in.getFieldName() != null) {
            exist.setFieldName(requireText(in.getFieldName(), "数据元名称不能为空"));
        }
        if (StringUtils.hasText(in.getFieldType())) {
            exist.setFieldType(in.getFieldType().trim());
        }
        if (in.getDictSource() != null) {
            exist.setDictSource(trimToNull(in.getDictSource()));
        }
        if (in.getDefaultValue() != null) {
            exist.setDefaultValue(in.getDefaultValue());
        }
        if (in.getRequired() != null) {
            exist.setRequired(in.getRequired());
        }
        if (in.getReadonly() != null) {
            exist.setReadonly(in.getReadonly());
        }
        if (in.getNoCopy() != null) {
            exist.setNoCopy(in.getNoCopy());
        }
        if (in.getPrintHidden() != null) {
            exist.setPrintHidden(in.getPrintHidden());
        }
        if (in.getMaxLength() != null) {
            exist.setMaxLength(in.getMaxLength());
        }
        if (in.getValidationRule() != null) {
            exist.setValidationRule(in.getValidationRule());
        }
        if (in.getSortNo() != null) {
            exist.setSortNo(in.getSortNo());
        }
        elementMapper.updateById(exist);
        log.info("更新数据集数据元: id={}, fieldKey={}", exist.getId(), exist.getFieldKey());
        return exist;
    }

    /** 数据集元素(按 sort_no→id 升序; 树构建按此序取章节/小节首现顺序) */
    private List<HisEmrDatasetElement> loadElements(Long datasetId) {
        return elementMapper.selectList(Wrappers.<HisEmrDatasetElement>lambdaQuery()
                .eq(HisEmrDatasetElement::getDatasetId, datasetId)
                .orderByAsc(HisEmrDatasetElement::getSortNo)
                .orderByAsc(HisEmrDatasetElement::getId));
    }

    /** 组装 章节→小节→数据元 树: 同一 chapter_key 归一章, 小节内聚; 无小节的元素挂章节 elements */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildTree(List<HisEmrDatasetElement> elements) {
        Map<String, Map<String, Object>> chapterIdx = new LinkedHashMap<>();
        for (HisEmrDatasetElement e : elements) {
            String cKey = StringUtils.hasText(e.getChapterKey()) ? e.getChapterKey() : "__default__";
            Map<String, Object> chapter = chapterIdx.get(cKey);
            if (chapter == null) {
                chapter = new LinkedHashMap<>();
                chapter.put("chapterKey", e.getChapterKey());
                chapter.put("chapterName", e.getChapterName());
                chapter.put("elements", new ArrayList<HisEmrDatasetElement>());
                chapter.put("sections", new ArrayList<Map<String, Object>>());
                chapterIdx.put(cKey, chapter);
            }
            if (!StringUtils.hasText(e.getSectionKey()) && !StringUtils.hasText(e.getSectionName())) {
                ((List<HisEmrDatasetElement>) chapter.get("elements")).add(e);
                continue;
            }
            List<Map<String, Object>> sections = (List<Map<String, Object>>) chapter.get("sections");
            Map<String, Object> section = null;
            for (Map<String, Object> s : sections) {
                boolean same = StringUtils.hasText(e.getSectionKey())
                        ? e.getSectionKey().equals(s.get("sectionKey"))
                        : Objects.equals(e.getSectionName(), s.get("sectionName"));
                if (same) {
                    section = s;
                    break;
                }
            }
            if (section == null) {
                section = new LinkedHashMap<>();
                section.put("sectionKey", e.getSectionKey());
                section.put("sectionName", e.getSectionName());
                section.put("elements", new ArrayList<HisEmrDatasetElement>());
                sections.add(section);
            }
            ((List<HisEmrDatasetElement>) section.get("elements")).add(e);
        }
        return new ArrayList<>(chapterIdx.values());
    }

    /** 数据集编码租户内查重(逻辑删除行不参与) */
    private void ensureCodeAvailable(String code, Long excludeId) {
        if (codeExists(code, excludeId)) {
            throw new BizException(400, "数据集编码已存在: " + code);
        }
    }

    private boolean codeExists(String code, Long excludeId) {
        return !datasetMapper.selectList(Wrappers.<HisEmrDataset>lambdaQuery()
                .eq(HisEmrDataset::getCode, code)
                .ne(excludeId != null, HisEmrDataset::getId, excludeId)).isEmpty();
    }

    /** 数据元 field_key 同数据集内查重(排除自身) */
    private void ensureFieldKeyAvailable(Long datasetId, String fieldKey, Long excludeId) {
        boolean dup = !elementMapper.selectList(Wrappers.<HisEmrDatasetElement>lambdaQuery()
                .eq(HisEmrDatasetElement::getDatasetId, datasetId)
                .eq(HisEmrDatasetElement::getFieldKey, fieldKey)
                .ne(excludeId != null, HisEmrDatasetElement::getId, excludeId)).isEmpty();
        if (dup) {
            throw new BizException(400, "数据元key在数据集内已存在: " + fieldKey);
        }
    }

    /** 新数据元默认排序号: 当前最大 sort_no + 10 */
    private Integer nextSortNo(Long datasetId) {
        List<HisEmrDatasetElement> list = elementMapper.selectList(Wrappers.<HisEmrDatasetElement>lambdaQuery()
                .eq(HisEmrDatasetElement::getDatasetId, datasetId)
                .orderByDesc(HisEmrDatasetElement::getSortNo)
                .last("LIMIT 1"));
        int max = 0;
        if (!list.isEmpty() && list.get(0).getSortNo() != null) {
            max = list.get(0).getSortNo();
        }
        return max + 10;
    }

    private static String requireText(String v, String msg) {
        if (!StringUtils.hasText(v)) {
            throw new BizException(400, msg);
        }
        return v.trim();
    }

    private static String trimToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static Long toLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v == null) {
            return null;
        }
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v == null) {
            return null;
        }
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
