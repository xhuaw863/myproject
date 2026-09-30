package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 住院转科/转床请求
 */
@Data
public class InpTransferDTO {

    /** 目标病区ID(his_ward.id) */
    private Long targetWardId;
    /** 目标床位ID(his_bed.id) */
    private Long targetBedId;
    /** 目标科室ID(his_dept.id) */
    private Long targetDeptId;
    /** 目标主治医生ID(his_staff.id) */
    private Long targetDoctorId;
    /** 转科/转床原因 */
    private String reason;
}
