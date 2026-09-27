package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 库存调拨明细(批次随行调拨, 守恒) */
@Data
@TableName("his_transfer_item")
public class HisTransferItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调拨单ID */
    private Long transferId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码(快照) */
    private String drugCode;
    /** 药品名称(快照) */
    private String drugName;
    /** 规格(快照) */
    private String spec;
    /** 批次号(随行调拨) */
    private String batchNo;
    /** 调拨数量 */
    private BigDecimal qty;
    /** 进价(快照) */
    private BigDecimal costPrice;
    /** 零售价(快照) */
    private BigDecimal retailPrice;
    /** 小计金额 */
    private BigDecimal amount;
}
