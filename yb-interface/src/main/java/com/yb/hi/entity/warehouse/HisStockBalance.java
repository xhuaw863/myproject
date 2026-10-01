package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 未验收药品出库平账记录: 出库批次来源入库单尚未财务验收(accept_status=0)时,
 * 按 实际出库结转进价 与 原挂账进价 之差产生冲抵记录(进价差=(实际-原)*数量), 供财务对账。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_stock_balance")
public class HisStockBalance extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 药库ID */
    private Long warehouseId;
    /** 触发出库单ID */
    private Long stockOutId;
    /** 触发出库明细ID */
    private Long stockOutItemId;
    /** 来源入库单ID(未验收) */
    private Long stockInId;
    /** 来源入库明细ID */
    private Long stockInItemId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品名称(快照) */
    private String drugName;
    /** 批号(快照) */
    private String batchNo;
    /** 原挂账进价 */
    private BigDecimal origInPrice;
    /** 实际出库结转进价 */
    private BigDecimal actualInPrice;
    /** 冲抵数量 */
    private BigDecimal qty;
    /** 进价差=(实际-原)*数量 */
    private BigDecimal diffAmount;
    /** 平账日期 */
    private LocalDate balanceDate;
    /** 备注 */
    private String remark;
}
