package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 病历模板批量操作请求(三个批量端点共用, 按接口各取所需字段):
 * - batch/updateElementAttr: templateIds + fieldKey + attrs
 * - batch/replaceSection:    templateIds + sectionKey + newContent
 * - batch/replaceHeader:     templateIds + newHeader
 */
@Data
public class EmrTemplateBatchDTO {

    /** 目标模板ID列表 */
    private List<Long> templateIds;
    /** 数据元key(updateElementAttr, 对应 emrField.attrs.fieldKey) */
    private String fieldKey;
    /** 数据元属性键值(updateElementAttr, 逐键覆盖文档节点与fields定义两处存储) */
    private Map<String, Object> attrs;
    /** 章节key(replaceSection, 对应 emrSection.attrs.key) */
    private String sectionKey;
    /** 章节新内容(replaceSection: JSON节点数组/单节点JSON对象/纯文本; 空串=清空章节内容) */
    private String newContent;
    /** 页眉新内容(replaceHeader, 空串=清空页眉文本) */
    private String newHeader;
}
