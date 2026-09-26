package com.yb.hi.dto.cashier;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 部分退费请求(按收费明细行退指定数量, 支持多次部分退)
 */
@Data
public class PartialRefundReq {

    /** 原收费单ID */
    private Long billId;
    /** 退费明细(逐行退数量) */
    private List<RefundItem> items;
    /** 退费原因 */
    private String reason;

    /** 单行退费明细 */
    @Data
    public static class RefundItem {
        /** 收费明细ID(his_charge_bill_item.id) */
        private Long billItemId;
        /** 本次退费数量 */
        private BigDecimal refundQty;
    }
}
