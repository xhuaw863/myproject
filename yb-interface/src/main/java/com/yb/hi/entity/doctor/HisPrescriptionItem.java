package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 处方明细表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_prescription_item")
public class HisPrescriptionItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 处方ID */
    private Long prescriptionId;
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
    /** 单次剂量 */
    private String dosage;
    /** 剂量单位 */
    private String dosageUnit;
    /** 用法 */
    private String usageMethod;
    /** 频次 */
    private String frequency;
    /** 给药途径 */
    private String administration;
    /** 用药组号 */
    private String groupNo;
    /** 用药天数 */
    private Integer days;
    /** 医保目录编码 */
    private String medListCodg;
}
