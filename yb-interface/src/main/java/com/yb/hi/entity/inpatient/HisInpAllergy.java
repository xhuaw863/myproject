package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 住院患者过敏记录(就诊级, 药物/食物/环境/其他四类, 驱动开嘱过敏拦截与腕带提示)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_allergy")
public class HisInpAllergy extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
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
