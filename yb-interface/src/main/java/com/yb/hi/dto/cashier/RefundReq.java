package com.yb.hi.dto.cashier;

import lombok.Data;

/**
 * 退费请求
 */
@Data
public class RefundReq {

    /** 原收费单ID */
    private Long billId;
    /** 退费原因 */
    private String reason;
}
