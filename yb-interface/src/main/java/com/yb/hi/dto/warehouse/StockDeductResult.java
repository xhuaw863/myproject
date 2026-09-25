package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 库存扣减结果(FIFO 逐批次): 供出库明细落库与药房发药追溯使用
 */
@Data
public class StockDeductResult {

    /** 库存批次ID */
    private Long stockId;
    /** 批次号 */
    private String batchNo;
    /** 本批次扣减数量 */
    private BigDecimal deductQty;
    /** 进价 */
    private BigDecimal costPrice;
    /** 零售价 */
    private BigDecimal retailPrice;

    public StockDeductResult() {
    }

    public StockDeductResult(Long stockId, String batchNo, BigDecimal deductQty,
                             BigDecimal costPrice, BigDecimal retailPrice) {
        this.stockId = stockId;
        this.batchNo = batchNo;
        this.deductQty = deductQty;
        this.costPrice = costPrice;
        this.retailPrice = retailPrice;
    }
}
