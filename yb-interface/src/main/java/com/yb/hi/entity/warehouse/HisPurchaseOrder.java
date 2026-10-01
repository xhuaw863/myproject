package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 药品采购订单主表(单号 CO+yyyyMMdd+4位, 租户内唯一): 由已审计划转生或手工建, 明细继承计划明细;
 * uploadStatus 为集采/统采上传占位字段(0未上传 9已上传Mock)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_purchase_order")
public class HisPurchaseOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 订单号(CO+yyyyMMdd+4位序号) */
    private String orderNo;
    /** 来源计划单ID */
    private Long planId;
    /** 收货药库ID */
    private Long warehouseId;
    /** 供应商ID */
    private Long supplierId;
    /** 状态: 0草稿 1已下单 2部分到货 3已完成 -2作废 */
    private Integer status;
    /** 订单金额合计 */
    private BigDecimal totalAmount;
    /** 集采/统采上传: 0未上传 9已上传(Mock) */
    private Integer uploadStatus;
    /** 上传时间 */
    private LocalDateTime uploadTime;
    /** 上传回执(Mock) */
    private String uploadReceipt;
    /** 备注 */
    private String remark;
}
