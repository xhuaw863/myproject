package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 护理量表评估提交请求(落 his_inp_nursing_record 量表扩展列, 评分达到阈值时触发护理计划)
 */
@Data
public class NursingAssessmentDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 量表编码(his_nursing_scale_def.scale_code) */
    private String scaleCode;
    /** 量表评分 */
    private BigDecimal scaleScore;
    /** 量表明细JSON(各维度得分) */
    private String scaleDetail;
    /** 评估护士ID(his_staff.id) */
    private Long nurseId;
    /** 记录时间 */
    private LocalDateTime recordTime;
}
