package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 患者入径启动请求
 */
@Data
public class PathwayStartDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 模板ID(his_pathway_template.id) */
    private Long templateId;
}
