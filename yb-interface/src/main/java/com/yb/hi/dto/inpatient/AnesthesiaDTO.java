package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 麻醉记录创建/更新请求
 */
@Data
public class AnesthesiaDTO {

    /** 手术ID(his_surgery.id) */
    private Long surgeryId;
    /** 麻醉类型: 1全麻 2局麻 3椎管内 4神经阻滞 5复合 6其他 */
    private Integer anesthesiaType;
    /** 具体麻醉方式 */
    private String anesthesiaMethod;
    /** 术前评估JSON */
    private String preAssessment;
    /** 术后评估JSON */
    private String postAssessment;
}
