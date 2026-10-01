package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 药品采购计划明细: 快照当前库存/高低储/上月出入库/发药量与ABC类别, 落建议采购数量。
 * (沿用请领/调拨明细惯例: 不映射审计列, 主表统一软删)
 */
@Data
@TableName("his_purchase_plan_item")
public class HisPurchasePlanItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 计划单ID */
    private Long planId;
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
    /** 药品大类(快照, 筛选/分类) */
    private String majorClass;
    /** 当前库存(快照) */
    private BigDecimal curStock;
    /** 低储标准(快照) */
    private BigDecimal loQty;
    /** 高储标准(快照) */
    private BigDecimal hiQty;
    /** 上月入库量(快照) */
    private BigDecimal lastMonthIn;
    /** 上月出库量(快照) */
    private BigDecimal lastMonthOut;
    /** 药房发药量(快照) */
    private BigDecimal dispenseQty;
    /** ABC类别 A/B/C */
    private String abcClass;
    /** 建议采购数量 */
    private BigDecimal qtySuggest;
    /** 预估进价 */
    private BigDecimal price;
    /** 预估金额 */
    private BigDecimal amount;
}
