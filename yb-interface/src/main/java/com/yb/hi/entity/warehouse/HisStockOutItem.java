package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 出库明细(记录扣减的库存批次, 先进先出可追溯)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_stock_out_item")
public class HisStockOutItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 出库单ID */
    private Long stockOutId;
    /** 库存批次ID(未指定批次出库时, 确认FIFO扣减后回填) */
    private Long drugStockId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码 */
    private String drugCode;
    /** 药品名称 */
    private String drugName;
    /** 规格 */
    private String spec;
    /** 批次号 */
    private String batchNo;
    /** 数量 */
    private BigDecimal qty;
    /** 进价 */
    private BigDecimal costPrice;
    /** 零售价 */
    private BigDecimal retailPrice;
    /** 小计金额(数量*零售价) */
    private BigDecimal amount;
}
