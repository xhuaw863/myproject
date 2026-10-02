package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 手术医嘱模板套用请求(P2b): 指定模板ID批量开嘱, 阶段缺省取模板 surgery_phase, 支持代开留痕。
 */
@Data
public class SurgeryTemplateDTO {

    /** 医嘱模板ID(his_order_template.id, apply_scene=2) */
    private Long templateId;
    /** 手术医嘱阶段: 1术前 2术中 3术后(模板未内置阶段时回落此值) */
    private Integer orderPhase;
    /** 代开目标医生ID(his_staff.id, 可空) */
    private Long proxyDoctorId;
    /** 代开原因(可空) */
    private String proxyReason;
}
