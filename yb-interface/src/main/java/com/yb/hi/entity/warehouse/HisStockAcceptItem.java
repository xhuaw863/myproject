package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 财务验收单明细: 按来源入库单/批次快照药品与数量金额。
 * (沿用请领/调拨明细惯例: 不映射审计列, 主表统一软删)
 */
@Data
@TableName("his_stock_accept_item")
public class HisStockAcceptItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 验收单ID */
    private Long acceptId;
    /** 来源入库单ID */
    private Long stockInId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码(快照) */
    private String drugCode;
    /** 药品名称(快照) */
    private String drugName;
    /** 规格(快照) */
    private String spec;
    /** 批号(快照) */
    private String batchNo;
    /** 生产企业(快照) */
    private String manufacturer;
    /** 验收数量 */
    private BigDecimal qty;
    /** 进价 */
    private BigDecimal costPrice;
    /** 金额 */
    private BigDecimal amount;
}
