package com.yb.hi.dto.pharmacy;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退药申请请求
 * 三期 C2: items 为空/缺省 -> 整方退(兼容旧口径, 退全部可退行或无行记录时回落整单);
 *          items 给定 -> 行级部分退, 按 dispenseItemId 定位发药行, 逐行按 returnQty 退(不超该行剩余可退量)。
 */
@Data
public class DrugReturnReq {

    /** 发药记录ID */
    private Long dispenseId;
    /** 退药原因 */
    private String reason;
    /** 行级退药明细(为空=整方退; 非空=行级部分退) */
    private List<Item> items;

    /** 单行退药: 定位发药明细行 + 本次退药数量 */
    @Data
    public static class Item {
        /** 发药明细行ID(his_dispense_item.id) */
        private Long dispenseItemId;
        /** 本次该行退药数量(须 >0 且 <= 该行剩余可退量) */
        private BigDecimal returnQty;
    }
}
