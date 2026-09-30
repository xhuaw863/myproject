package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 护理计划模板请求
 */
@Data
public class NursingPlanDTO {

    /** 计划名称 */
    private String planName;
    /** 触发量表编码(his_nursing_scale_def.scale_code) */
    private String triggerScaleCode;
    /** 触发分值区间 */
    private String triggerScoreRange;
    /** 护理诊断 */
    private String nursingDiagnosis;
    /** 护理目标 */
    private String nursingGoal;
    /** 护理措施JSON数组 */
    private String interventions;
    /** 评价标准 */
    private String evaluationCriteria;
    /** 科室ID(his_dept.id) */
    private Long deptId;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
