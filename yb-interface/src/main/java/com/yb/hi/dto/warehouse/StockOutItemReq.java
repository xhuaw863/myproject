package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 出库单明细行请求
 */
@Data
public class StockOutItemReq {

    /**
     * 库存批次ID: 指定批次出库(校验归属机构后带出药品/批次/价格信息);
     * 为空则按药品出库, 确认时按有效期 FIFO 自动扣减并回填批次。
     */
    private Long drugStockId;
    /** 药品目录ID(未指定批次时必填) */
    private Long drugCatalogId;
    /** 药品编码(未指定批次时必填) */
    private String drugCode;
    /** 药品名称(未指定批次时必填) */
    private String drugName;
    /** 规格 */
    private String spec;
    /** 批次号(未指定批次出库时由确认回填) */
    private String batchNo;
    /** 数量(>0) */
    private BigDecimal qty;
    /** 进价(未指定批次时由确认回填) */
    private BigDecimal costPrice;
    /** 零售价(未指定批次时由确认回填) */
    private BigDecimal retailPrice;
}
