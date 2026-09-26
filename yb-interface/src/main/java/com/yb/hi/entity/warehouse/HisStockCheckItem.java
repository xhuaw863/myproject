package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 盘点单明细(逐批次实盘记录; diffQty=actualQty-systemQty, 正=盘盈 负=盘亏)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_stock_check_item")
public class HisStockCheckItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 盘点单ID(his_stock_check.id) */
    private Long stockCheckId;
    /** 库存批次ID(his_drug_stock.id) */
    private Long drugStockId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品名称(快照) */
    private String drugName;
    /** 规格(快照) */
    private String spec;
    /** 批次号(快照) */
    private String batchNo;
    /** 系统账面数量(生成盘点单时快照) */
    private BigDecimal systemQty;
    /** 实盘数量 */
    private BigDecimal actualQty;
    /** 差异数量(实盘-账面; 正=盘盈 负=盘亏) */
    private BigDecimal diffQty;
    /** 进价(快照, 用于盘盈/盘亏金额计算) */
    private BigDecimal costPrice;
    /** 备注 */
    private String remark;
}
