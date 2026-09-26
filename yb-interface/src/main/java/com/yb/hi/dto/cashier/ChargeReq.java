package com.yb.hi.dto.cashier;

import lombok.Data;

import java.util.List;

/**
 * 收费请求
 */
@Data
public class ChargeReq {

    /** 就诊ID */
    private Long visitId;
    /** 收费机构ID(空=当前登录用户机构) */
    private Long orgId;
    /** 支付方式: yb-医保 / self-自费 */
    private String payType;
    /**
     * 混合支付明细(可选): 医保单拆自付部分、自费单拆全额。
     * 空=老接口兼容(cashPay=selfPay 全现金, 不落支付明细);
     * 非空时合计金额必须等于应付金额, payMethod 存金额最大的方式。
     */
    private List<PaymentItem> payments;
}
