package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 住院会诊记录(普通/急会诊/MDT三类, 申请→受理→完成/拒绝/取消状态机)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_consultation")
public class HisInpConsultation extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
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
