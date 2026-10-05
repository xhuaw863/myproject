package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.inpatient.HisEmrRefIndex;
import com.yb.hi.mapper.inpatient.EmrRefIndexMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 病历模板引用反查索引服务(高级版): 保存模板时解析 Tiptap document, 抽取该模板引用的
 * 片段(fragmentId)/数据元(fieldKey)/宏(macroCode)/图示(title) 落 his_emr_ref_index,
 * 支撑"某组件被哪些模板引用"的影响分析。
 *
 * 约定: rebuild 采用先逻辑删除该模板旧索引再插入新集的幂等口径; 解析失败不阻断保存(仅告警)。
 * tenant_id 由租户插件注入; orgId 由调用方传入(模板归属机构)。
 */
@Slf4j
@Service
public class EmrRefIndexService {

    private final EmrRefIndexMapper refIndexMapper;

    public EmrRefIndexService(EmrRefIndexMapper refIndexMapper) {
        this.refIndexMapper = refIndexMapper;
    }

    /** 重算某模板的引用索引(document 为空则清空其索引)。 */
    public void rebuild(Long templateId, Long orgId, String documentJson) {
        if (templateId == null) {
            return;
        }
        // 先逻辑删除旧行(不物理删, 保持与全库软删口径一致)
        refIndexMapper.delete(Wrappers.<HisEmrRefIndex>lambdaQuery()
                .eq(HisEmrRefIndex::getSourceTemplateId, templateId));
        Set<String[]> refs = new LinkedHashSet<>();
        try {
            if (StringUtils.hasText(documentJson)) {
                JSONObject doc = JSON.parseObject(documentJson.trim());
                if (doc != null) {
                    collect(doc, refs);
                }
            }
        } catch (Exception e) {
            log.warn("模板[{}]引用索引重算跳过(document 解析失败): {}", templateId, e.getMessage());
            return;
        }
        for (String[] r : refs) {
            HisEmrRefIndex row = new HisEmrRefIndex();
            row.setOrgId(orgId);
            row.setSourceTemplateId(templateId);
            row.setRefType(r[0]);
            row.setRefKey(r[1]);
            refIndexMapper.insert(row);
        }
    }

    /** 反查引用了指定组件的模板ID列表(refType: fragment/drawing/element/macro)。 */
    public List<Long> findSourceTemplateIds(String refType, String refKey) {
        if (!StringUtils.hasText(refType) || !StringUtils.hasText(refKey)) {
            return new ArrayList<>();
        }
        List<HisEmrRefIndex> rows = refIndexMapper.selectList(Wrappers.<HisEmrRefIndex>lambdaQuery()
                .select(HisEmrRefIndex::getSourceTemplateId)
                .eq(HisEmrRefIndex::getRefType, refType)
                .eq(HisEmrRefIndex::getRefKey, refKey.trim()));
        Set<Long> ids = new LinkedHashSet<>();
        for (HisEmrRefIndex r : rows) {
            if (r.getSourceTemplateId() != null) {
                ids.add(r.getSourceTemplateId());
            }
        }
        return new ArrayList<>(ids);
    }

    /** 递归遍历 Tiptap JSON 节点, 收集 {refType, refKey} 去重集。 */
    private void collect(JSONObject node, Set<String[]> out) {
        if (node == null) {
            return;
        }
        String type = node.getString("type");
        JSONObject attrs = node.getJSONObject("attrs");
        if (type != null && attrs != null) {
            String key = null;
            switch (type) {
                case "emrFragment": key = attrs.getString("fragmentId"); break;
                case "emrField":    key = attrs.getString("fieldKey"); break;
                case "emrMacro":    key = attrs.getString("macroCode"); break;
                case "emrDrawing":  key = attrs.getString("title"); break;
                default: break;
            }
            if (key != null && StringUtils.hasText(key)) {
                out.add(new String[]{refTypeOf(type), key.trim()});
            }
        }
        JSONArray content = node.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                collect(content.getJSONObject(i), out);
            }
        }
    }

    private String refTypeOf(String nodeType) {
        switch (nodeType) {
            case "emrFragment": return "fragment";
            case "emrField":    return "element";
            case "emrMacro":    return "macro";
            case "emrDrawing":  return "drawing";
            default:            return nodeType;
        }
    }
}
