package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 住院诊断录入请求
 */
@Data
public class InpDiagnosisDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 诊断类型: 1入院诊断 2补充诊断 3术后诊断 4出院诊断 */
    private Integer diagType;
    /** 诊断编码(ICD-10) */
    private String diagCode;
    /** 诊断名称 */
    private String diagName;
    /** 是否主诊断: 1是 0否 */
    private Integer isMain;
    /** 入院病情: 1危急 2严重 3一般 4不适用(病案首页) */
    private Integer admitCondition;
    /** 是否并发症/合并症: 1是 0否(病案首页) */
    private Integer complicationFlag;
}
