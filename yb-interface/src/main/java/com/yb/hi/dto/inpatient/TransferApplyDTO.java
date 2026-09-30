package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 住院转科/转床/加床申请请求(原病区床位由就诊在院信息带出)
 */
@Data
public class TransferApplyDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 申请类型: 1转科 2转床 3加床 */
    private Integer transferType;
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
}
