package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 护理健康宣教(P4c): 入院/疾病/用药/饮食/运动/出院知识宣教记录, 含宣教方式(口头/书面/视频/演示)与效果评价
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_education")
public class HisNursingEducation extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 知识分类:admission/disease/medication/diet/exercise/discharge/other */
    private String knowledgeCategory;
    /** 宣教标题 */
    private String title;
    /** 宣教内容 */
    private String content;
    /** 方式:verbal/written/video/demo */
    private String educationMethod;
    /** 评价:understood/partially/not_understood */
    private String evaluationResult;
    /** 宣教人ID */
    private Long educatorId;
    /** 宣教人 */
    private String educatorName;
    /** 宣教时间 */
    private LocalDateTime educationTime;
}
