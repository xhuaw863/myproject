package com.yb.hi.entity.cashier;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 收费明细(来源处方明细/检查单明细, 冗余医保编码/名称/自付比例快照)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_charge_bill_item")
public class HisChargeBillItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收费单ID */
    private Long billId;
    /** 项目类型: 1药品 2检查 3治疗 4材料 */
    private Integer itemType;
    /** 来源类型: prescription_item/order_item */
    private String refType;
    /** 来源明细ID */
    private Long refId;
    /** 项目编码(院内) */
    private String itemCode;
    /** 项目名称 */
    private String itemName;
    /** 规格 */
    private String spec;
    /** 数量 */
    private BigDecimal qty;
    /** 已退数量(部分退费追踪, 默认0) */
    private BigDecimal refundedQty;
    /** 单价 */
    private BigDecimal price;
    /** 金额 */
    private BigDecimal amount;
    /** 医保目录编码(对照) */
    private String medListCodg;
    /** 医保目录名称(快照) */
    private String medListName;
    /** 自付比例(0-1) */
    private BigDecimal ratio;
}
