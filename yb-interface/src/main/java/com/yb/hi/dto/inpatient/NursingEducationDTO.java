package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 护理健康宣教登记请求(落 his_nursing_education; 宣教人取当前登录职工, 宣教时间缺省当前)
 */
@Data
public class NursingEducationDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id, 缺省从就诊主表回填) */
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
    /** 宣教时间(缺省当前) */
    private LocalDateTime educationTime;
}
