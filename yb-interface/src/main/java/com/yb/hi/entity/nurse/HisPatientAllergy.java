package com.yb.hi.entity.nurse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 患者过敏记录(手工登记/皮试阳性/医生站录入多来源汇聚)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_patient_allergy")
public class HisPatientAllergy extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 过敏原类型: drug药品/food食物/other其他 */
    private String allergenType;
    /** 过敏原名称 */
    private String allergenName;
    /** 过敏原编码(药品为目录编码) */
    private String allergenCode;
    /** 严重程度: mild轻度/moderate中度/severe重度 */
    private String severity;
    /** 来源: manual手工/skin_test皮试/doctor医生站 */
    private String source;
    /** 来源记录ID(如his_skin_test.id) */
    private Long sourceId;
    /** 登记时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime recordTime;
    /** 登记人ID(his_staff.id) */
    private Long recordBy;
    /** 是否有效: 1有效 0已失效 */
    private Integer isActive;
}
