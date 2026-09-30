package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 住院转科转床申请(转科/转床/加床三类, 申请→批准→执行闭环留痕)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_transfer")
public class HisInpTransfer extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 申请类型: 1转科 2转床 3加床 */
    private Integer transferType;
    /** 原病区ID(his_ward.id) */
    private Long fromWardId;
    /** 原床位ID(his_bed.id) */
    private Long fromBedId;
    /** 原科室ID(his_dept.id) */
    private Long fromDeptId;
    /** 目标病区ID(his_ward.id) */
    private Long toWardId;
    /** 目标床位ID(his_bed.id) */
    private Long toBedId;
    /** 目标科室ID(his_dept.id) */
    private Long toDeptId;
    /** 申请原因 */
    private String reason;
    /** 申请医生ID(his_staff.id) */
    private Long applyDoctorId;
    /** 审批医生ID(his_staff.id) */
    private Long approveDoctorId;
    /** 申请时间 */
    private LocalDateTime applyTime;
    /** 审批时间 */
    private LocalDateTime approveTime;
    /** 状态: 1申请 2批准 3拒绝 4已执行 5取消 */
    private Integer status;
}
