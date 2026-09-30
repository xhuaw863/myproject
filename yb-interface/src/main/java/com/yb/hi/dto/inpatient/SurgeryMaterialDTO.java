package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 手术耗材登记请求
 */
@Data
public class SurgeryMaterialDTO {

    /** 手术ID(his_surgery.id) */
    private Long surgeryId;
    /** 耗材名称 */
    private String materialName;
    /** 耗材编码 */
    private String materialCode;
    /** 规格 */
    private String spec;
    /** 批号 */
    private String batchNo;
    /** 数量 */
    private BigDecimal quantity;
    /** 单价 */
    private BigDecimal unitPrice;
    /** 金额 */
    private BigDecimal amount;
    /** 供应商 */
    private String supplier;
}
