package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 由数据集生成 Tiptap 模板请求
 */
@Data
public class EmrTemplateFromDatasetDTO {

    /** 数据集ID(his_emr_dataset.id) */
    private Long datasetId;
    /** 模板名称 */
    private String name;
    /** 模板层级: 0全院 1科室 2个人(缺省0, 归属与守卫同模板创建) */
    private Integer scopeLevel;
}
