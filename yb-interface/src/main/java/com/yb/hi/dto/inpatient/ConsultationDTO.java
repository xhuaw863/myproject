package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 住院会诊记录(全字段, 创建/查询通用)
 */
@Data
public class ConsultationDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 会诊类型: 1普通 2急会诊 3MDT */
    private Integer consultType;
    /** 申请科室ID(his_dept.id) */
    private Long applyDeptId;
    /** 申请医师ID(his_staff.id) */
    private Long applyDoctorId;
    /** 受邀科室ID(his_dept.id) */
    private Long targetDeptId;
    /** 受邀医师ID(his_staff.id) */
    private Long targetDoctorId;
    /** 申请理由 */
    private String applyReason;
    /** 会诊意见 */
    private String consultOpinion;
    /** 申请时间 */
    private LocalDateTime applyTime;
    /** 受理时间 */
    private LocalDateTime responseTime;
    /** 会诊完成时间 */
    private LocalDateTime consultTime;
    /** 状态: 1申请 2受理 3完成 4拒绝 5取消 */
    private Integer status;
    /** 紧急程度: 1普通 2急 3特急 */
    private Integer urgencyLevel;
}
