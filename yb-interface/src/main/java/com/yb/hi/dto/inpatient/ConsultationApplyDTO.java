package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 住院会诊申请请求(申请方填写, 受理/完成由受邀方链路回写)
 */
@Data
public class ConsultationApplyDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 会诊类型: 1普通 2急会诊 3MDT */
    private Integer consultType;
    /** 受邀科室ID(his_dept.id) */
    private Long targetDeptId;
    /** 受邀医师ID(his_staff.id) */
    private Long targetDoctorId;
    /** 申请理由 */
    private String applyReason;
    /** 紧急程度: 1普通 2急 3特急 */
    private Integer urgencyLevel;
}
