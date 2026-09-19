package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 检查/检验/治疗单明细表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_order_item")
public class HisOrderItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 单据ID */
    private Long orderId;
    /** 收费项目ID(his_charge_item) */
    private Long itemId;
    /** 院内项目编码 */
    private String itemCode;
    /** 项目名称 */
    private String itemName;
    /** 规格 */
    private String spec;
    /** 单位 */
    private String unit;
    /** 单价 */
    private BigDecimal price;
    /** 数量 */
    private BigDecimal quantity;
    /** 金额 */
    private BigDecimal amount;
    /** 医保目录编码 */
    private String medListCodg;
    /** 执行科室 */
    private String execDept;
}
