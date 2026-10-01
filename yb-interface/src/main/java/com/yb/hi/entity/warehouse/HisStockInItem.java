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
 * 入库明细(确认入库时按批次 upsert his_drug_stock)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_stock_in_item")
public class HisStockInItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 入库单ID */
    private Long stockInId;
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
    /** 生产厂家 */
    private String manufacturer;
    /** 数量 */
    private BigDecimal qty;
    /** 进价 */
    private BigDecimal costPrice;
    /** 零售价 */
    private BigDecimal retailPrice;
    /** 生产日期 */
    private LocalDate prodDate;
    /** 有效期 */
    private LocalDate expDate;
    /** 小计金额(数量*进价) */
    private BigDecimal amount;

    /* ---- 采购入库增强(批次B) ---- */
    /** 大包装数(多单位录入) */
    private BigDecimal packQty;
    /** 包装换算比快照(大包装→最小单位) */
    private Integer packRatio;
    /** 最小单位量=大包装数*包装比 */
    private BigDecimal minQty;
    /** 挂账进价(待核) */
    private BigDecimal purchasePrice;
}
