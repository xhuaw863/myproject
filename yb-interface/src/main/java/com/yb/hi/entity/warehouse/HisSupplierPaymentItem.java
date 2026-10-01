package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 供应商付款单明细: 关联被结算的入库单与本次分摊付款额(快照入库应付额/结算前已付额)。
 * (沿用请领/调拨/验收明细惯例: 不映射审计列, 主表统一软删)
 */
@Data
@TableName("his_supplier_payment_item")
public class HisSupplierPaymentItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 付款单ID */
    private Long paymentId;
    /** 被结算入库单ID */
    private Long stockInId;
    /** 入库单号(快照) */
    private String stockInNo;
    /** 入库应付额(快照) */
    private BigDecimal inAmount;
    /** 结算前已付额(快照) */
    private BigDecimal paidBefore;
    /** 本次分摊付款额 */
    private BigDecimal paidAmount;
}
