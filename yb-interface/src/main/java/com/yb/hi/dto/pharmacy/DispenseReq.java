package com.yb.hi.dto.pharmacy;

import lombok.Data;

/**
 * 执行发药请求
 */
@Data
public class DispenseReq {

    /** 处方ID */
    private Long prescriptionId;
    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 药房ID(his_pharmacy_def.id, 空则不发药房归属) */
    private Long pharmacyId;
    /** 核对人(双签, 空则未核对) */
    private String checkBy;
    /** 备注 */
    private String remark;
}
