package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 护理计划实例(患者级计划执行与评价, 计划/实际措施双留痕)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_plan_instance")
public class HisNursingPlanInstance extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
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
