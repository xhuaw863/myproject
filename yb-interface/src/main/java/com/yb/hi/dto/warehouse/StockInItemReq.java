package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 入库单明细行请求
 */
@Data
public class StockInItemReq {

    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码 */
    private String drugCode;
    /** 药品名称 */
    private String drugName;
    /** 规格 */
    private String spec;
    /** 批次号 */
    private String batchNo;
    /** 生产厂家 */
    private String manufacturer;
    /** 数量(>0) */
    private BigDecimal qty;
    /** 进价 */
    private BigDecimal costPrice;
    /** 零售价 */
    private BigDecimal retailPrice;
    /** 生产日期(yyyy-MM-dd) */
    private String prodDate;
    /** 有效期(yyyy-MM-dd) */
    private String expDate;
    /** 小计金额(空则按 数量*进价 计算) */
    private BigDecimal amount;
}
