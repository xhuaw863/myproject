package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 护理评估量表定义请求
 */
@Data
public class NursingScaleDefDTO {

    /** 量表编码 */
    private String scaleCode;
    /** 量表名称 */
    private String scaleName;
    /** 量表类型: 1入院评估 2专科 3风险 */
    private Integer scaleType;
    /** 维度定义JSON */
    private String dimensions;
    /** 分数→风险映射JSON */
    private String scoreInterpretation;
    /** 必评频次说明 */
    private String requiredFrequency;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
