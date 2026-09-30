package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 护理计划实例请求(模板触发/手工创建通用, 评价时回写评价字段)
 */
@Data
public class NursingPlanInstanceDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 护理计划模板ID(his_nursing_plan_template.id) */
    private Long templateId;
    /** 触发量表记录ID(his_inp_nursing_record.id) */
    private Long scaleRecordId;
    /** 护理诊断 */
    private String nursingDiagnosis;
    /** 护理目标 */
    private String nursingGoal;
    /** 计划措施JSON */
    private String plannedInterventions;
    /** 实际措施JSON */
    private String actualInterventions;
    /** 开始时间 */
    private LocalDateTime startTime;
    /** 评价时间 */
    private LocalDateTime evaluationTime;
    /** 评价结果 */
    private String evaluationResult;
    /** 状态: 1执行中 2已评价 3已关闭 */
    private Integer status;
    /** 责任护士ID(his_staff.id) */
    private Long nurseId;
}
