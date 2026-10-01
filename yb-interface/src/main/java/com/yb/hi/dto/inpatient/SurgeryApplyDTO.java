package com.yb.hi.dto.inpatient;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 手术申请单创建/编辑请求
 */
@Data
public class SurgeryApplyDTO {

    /** 就诊类型: 1住院 2门诊 3日间 */
    private Integer visitType;
    /** 住院就诊ID(visit_type=1) */
    private Long inpVisitId;
    /** 门诊就诊ID(visit_type=2/3) */
    private Long visitId;
    /** 手术编码ICD-9-CM-3 */
    private String surgeryCode;
    /** 手术名称 */
    private String surgeryName;
    /** 手术级别: 1-4 */
    private Integer surgeryLevel;
    /** 拟麻醉方式: 1全麻 2局麻 3椎管内 4神经阻滞 5复合 6其他 */
    private Integer anesthesiaType;
    /** 拟主刀医师ID(his_staff.id) */
    private Long surgeonId;
    /** 医生期望手术时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expectTime;
    /** 手术时限: 1择期 2限期 3急诊 */
    private Integer deadlineType;
    /** 术前诊断 */
    private String preOpDiag;
    /** 手术经过/病情简介 */
    private String applyReason;
    /** 特殊要求 */
    private String specialReq;
}
