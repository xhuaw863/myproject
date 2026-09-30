package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 住院患者过敏记录请求
 */
@Data
public class AllergyDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 过敏类型: 1药物 2食物 3环境 4其他 */
    private Integer allergyType;
    /** 过敏原名称 */
    private String allergenName;
    /** 过敏原编码 */
    private String allergenCode;
    /** 严重程度: 1轻 2中 3重 */
    private Integer severity;
    /** 过敏反应描述 */
    private String reactionDesc;
    /** 记录时间 */
    private LocalDateTime recordTime;
    /** 记录医生ID(his_staff.id) */
    private Long doctorId;
    /** 状态: 1有效 0已失效 */
    private Integer status;
}
