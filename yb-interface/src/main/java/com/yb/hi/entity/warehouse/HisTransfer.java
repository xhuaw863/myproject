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
 * 库存调拨单主表(药库/药房库存位之间配对出入库): 调出=对调出库位 out_type=4 出库(指定批次), 调入=对调入库位 in_type=4 入库(保留同批次效期)。
 * 状态机: 0草稿 1待调出确认 2已调出 3已调入 -2已作废。单号 DB+yyyyMMdd+4位(租户内唯一)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_transfer")
public class HisTransfer extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID(调拨限同机构) */
    private Long orgId;
    /** 调拨单号 */
    private String transferNo;
    /** 调出库位ID(药库或药房库存位 his_warehouse_def.id) */
    private Long fromLocationId;
    /** 调入库位ID */
    private Long toLocationId;
    /** 调拨类型: WH2PHARMACY/PHARMACY2PHARMACY/WH2WH */
    private String kind;
    /** 状态: 0草稿 1待调出确认 2已调出 3已调入 -2已作废 */
    private Integer status;
    /** 调出人 */
    private String shipBy;
    /** 调出时间 */
    private LocalDateTime shipTime;
    /** 调入人 */
    private String receiveBy;
    /** 调入时间 */
    private LocalDateTime receiveTime;
    /** 调出出库单ID */
    private Long stockOutId;
    /** 调入入库单ID */
    private Long stockInId;
    /** 调拨金额合计 */
    private BigDecimal totalAmount;
    /** 备注 */
    private String remark;
}
