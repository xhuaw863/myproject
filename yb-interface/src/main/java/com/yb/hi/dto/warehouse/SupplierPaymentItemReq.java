package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 付款单结算明细请求: 关联被结算的入库单; 方式3(部分分摊)须指定本次分摊额 paidAmount。
 */
@Data
public class SupplierPaymentItemReq {

    /** 被结算入库单ID(his_stock_in.id) */
    private Long stockInId;
    /** 本次分摊付款额(方式3必填; 方式1/2忽略由服务端计算) */
    private BigDecimal paidAmount;
}
