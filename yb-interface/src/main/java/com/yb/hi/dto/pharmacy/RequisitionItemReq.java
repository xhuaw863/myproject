package com.yb.hi.dto.pharmacy;

import lombok.Data;

import java.math.BigDecimal;

/** 药品请领明细行(药房发起请领时提交) */
@Data
public class RequisitionItemReq {

    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码 */
    private String drugCode;
    /** 药品名称 */
    private String drugName;
    /** 规格 */
    private String spec;
    /** 请领数量(>0) */
    private BigDecimal qtyApply;
    /** 零售价(快照, 空则服务端按目录回填) */
    private BigDecimal retailPrice;
}
