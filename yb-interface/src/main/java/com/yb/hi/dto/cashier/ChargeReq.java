package com.yb.hi.dto.cashier;

import lombok.Data;

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
}
