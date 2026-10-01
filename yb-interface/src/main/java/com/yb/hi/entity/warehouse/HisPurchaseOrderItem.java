package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 药品采购订单明细: 继承计划明细的药品快照与数量, qtyReceived 记录已到货入库累计量。
 * (沿用请领/调拨明细惯例: 不映射审计列, 主表统一软删)
 */
@Data
@TableName("his_purchase_order_item")
public class HisPurchaseOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单ID */
    private Long orderId;
    /** 来源计划明细ID */
    private Long planItemId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码(快照) */
    private String drugCode;
    /** 药品名称(快照) */
    private String drugName;
    /** 规格(快照) */
    private String spec;
    /** 生产企业(快照) */
    private String manufacturer;
    /** 订购数量 */
    private BigDecimal qty;
    /** 已到货入库数量 */
    private BigDecimal qtyReceived;
    /** 进价 */
    private BigDecimal price;
    /** 金额 */
    private BigDecimal amount;
}
