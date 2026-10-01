package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.util.List;

/**
 * 创建入库单请求(草稿)
 */
@Data
public class StockInReq {

    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 药库ID(his_warehouse_def.id, 空则不限库/默认库) */
    private Long warehouseId;
    /** 入库类型: 1采购 2退药回库 3盘盈 4调拨入 */
    private Integer inType;
    /** 供应商 */
    private String supplier;
    /** 供应商联系方式 */
    private String supplierContact;
    /** 备注 */
    private String remark;
    /* ---- 采购入库增强(批次B) ---- */
    /** 购入方式: 1正常 2挂账 3票未到(仅单据); 空按1 */
    private Integer purchaseMode;
    /** 来源采购订单ID */
    private Long purchaseOrderId;
    /** 发票号 */
    private String invoiceNo;
    /** 发票日期(yyyy-MM-dd) */
    private String invoiceDate;
    /** 定向出库目标库ID(确认入库后自动调拨至该库) */
    private Long targetWarehouseId;
    /** 供应商ID(his_supplier.id) */
    private Long supplierId;
    /** 入库明细 */
    private List<StockInItemReq> items;
}
