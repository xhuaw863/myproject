package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入库单主表(单号租户内唯一)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_stock_in")
public class HisStockIn extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 药库ID(his_warehouse_def.id) */
    private Long warehouseId;
    /** 入库单号(RK+yyyyMMdd+4位序号) */
    private String inNo;
    /** 入库类型: 1采购 2退药回库 3盘盈 4调拨入 */
    private Integer inType;
    /** 供应商 */
    private String supplier;
    /** 供应商联系方式 */
    private String supplierContact;
    /** 总金额 */
    private BigDecimal totalAmount;
    /** 状态: 0草稿 1已确认 2已作废 */
    private Integer status;
    /** 确认人 */
    private String confirmBy;
    /** 确认时间 */
    private LocalDateTime confirmTime;
    /** 备注 */
    private String remark;

    /* ---- 采购入库增强(批次B) ---- */
    /** 购入方式: 1正常 2挂账 3票未到(仅单据) */
    private Integer purchaseMode;
    /** 来源采购订单ID(his_purchase_order.id) */
    private Long purchaseOrderId;
    /** 发票号 */
    private String invoiceNo;
    /** 发票日期 */
    private LocalDate invoiceDate;
    /** 定向出库目标库ID(确认入库后自动调拨至该库) */
    private Long targetWarehouseId;
    /** 财务验收: 0未验收 1已验收 */
    private Integer acceptStatus;
    /** 是否已冲红: 1是 0否 */
    private Integer reversedFlag;
    /** 红字冲账单指向的原入库单ID */
    private Long redOfId;
    /** 供应商ID(his_supplier.id, 新单与 supplier 文本双写) */
    private Long supplierId;
    /* ---- 付款回写(批次C) ---- */
    /** 已付金额 */
    private BigDecimal paidAmount;
    /** 付款状态: 0未付 1部分 2已付 */
    private Integer paidStatus;
}
