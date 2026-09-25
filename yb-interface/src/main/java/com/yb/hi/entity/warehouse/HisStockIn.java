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
}
