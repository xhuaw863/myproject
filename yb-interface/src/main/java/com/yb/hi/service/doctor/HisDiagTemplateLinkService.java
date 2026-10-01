package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisDiagTemplateLink;
import com.yb.hi.entity.doctor.HisMedicalTemplate;
import com.yb.hi.mapper.doctor.HisDiagTemplateLinkMapper;
import com.yb.hi.mapper.doctor.HisMedicalTemplateMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断→医嘱/处方模板关联服务(OP-B): 按 diagCode(+科室/通用) 命中映射, 并带出模板名称与内容供前端调入。
 */
@Service
public class HisDiagTemplateLinkService {

    private final HisDiagTemplateLinkMapper linkMapper;
    private final HisMedicalTemplateMapper templateMapper;

    public HisDiagTemplateLinkService(HisDiagTemplateLinkMapper linkMapper, HisMedicalTemplateMapper templateMapper) {
        this.linkMapper = linkMapper;
        this.templateMapper = templateMapper;
    }

    /**
     * 查询诊断关联模板: deptId 命中的科室级 + 通用(dept_id 为空)映射; 关联模板表补 name/content。
     */
    public List<Map<String, Object>> listByDiag(String diagCode, Long deptId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (diagCode == null || diagCode.isEmpty()) {
            return result;
        }
        List<HisDiagTemplateLink> links = linkMapper.selectList(Wrappers.<HisDiagTemplateLink>lambdaQuery()
                .eq(HisDiagTemplateLink::getDiagCode, diagCode)
                .and(w -> w.isNull(HisDiagTemplateLink::getDeptId)
                        .or(deptId != null, q -> q.eq(HisDiagTemplateLink::getDeptId, deptId))));
        for (HisDiagTemplateLink link : links) {
            HisMedicalTemplate tpl = link.getTemplateId() == null ? null : templateMapper.selectById(link.getTemplateId());
            if (tpl == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("linkId", link.getId());
            m.put("templateId", tpl.getId());
            m.put("templateType", link.getTemplateType() != null ? link.getTemplateType() : tpl.getTemplateType());
            m.put("templateName", tpl.getName());
            m.put("content", tpl.getContent());
            result.add(m);
        }
        return result;
    }
}
