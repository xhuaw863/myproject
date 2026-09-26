package com.yb.hi.dto.cashier;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 支付明细项(收费混合支付的单个支付方式)
 */
@Data
public class PaymentItem {

    /** 支付方式: CASH/WECHAT/ALIPAY/CARD/INSURANCE/FREE */
    private String payMethod;
    /** 支付金额 */
    private BigDecimal amount;
    /** 支付流水号(可选) */
    private String payRef;
}
