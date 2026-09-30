package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 护理计划模板(按量表分值区间触发, 护理诊断/目标/措施/评价标准结构化)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_plan_template")
public class HisNursingPlanTemplate extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
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
