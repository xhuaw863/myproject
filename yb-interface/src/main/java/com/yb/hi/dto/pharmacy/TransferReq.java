package com.yb.hi.dto.pharmacy;

import lombok.Data;

/**
 * 处方改派发药药房请求(三期): 已收费未发药处方因库存不足改派到有库存的药房
 */
@Data
public class TransferReq {

    /** 处方ID */
    private Long prescriptionId;
    /** 改派目标药房ID(his_pharmacy_def.id) */
    private Long toPharmacyId;
}
