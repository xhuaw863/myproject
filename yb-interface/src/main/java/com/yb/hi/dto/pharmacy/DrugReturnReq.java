package com.yb.hi.dto.pharmacy;

import lombok.Data;

/**
 * 退药申请请求
 */
@Data
public class DrugReturnReq {

    /** 发药记录ID */
    private Long dispenseId;
    /** 退药原因 */
    private String reason;
}
