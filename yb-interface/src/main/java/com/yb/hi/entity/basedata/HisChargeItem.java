package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 收费项目(本院统一目录: 药品/诊疗/耗材)
 * med_list_codg 为与医保目录的对照编码
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_charge_item")
public class HisChargeItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 院内收费项目编码 */
    private String itemCode;
    /** 院内项目名称 */
    private String itemName;
    /** 项目大类: 药品/诊疗/耗材/其他 */
    private String itemType;
    /** 细分类别 */
    private String itemCat;
    /** 规格 */
    private String spec;
    /** 单位 */
    private String unit;
    /** 单价 */
    private BigDecimal price;
    /** 医保医疗目录编码(对照) */
    private String medListCodg;
    /** 医保机构目录编码(对照) */
    private String medinsListCodg;
    /** 医疗收费项目类别: 01-药品 02-诊疗 03-耗材 */
    private String medChrgitmType;
    /** 收费项目等级: 01-甲 02-乙 03-丙 */
    private String chrgitmLv;
    /** 自付比例(0-1) */
    private BigDecimal selfpayProp;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
    /** 备注 */
    private String memo;
}
