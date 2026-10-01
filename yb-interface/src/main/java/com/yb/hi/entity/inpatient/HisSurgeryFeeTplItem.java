package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 手术费用模板明细(项目名/类别/数量/单价, 一键导入时逐行复制为手术费用记录)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_fee_tpl_item")
public class HisSurgeryFeeTplItem extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 模板ID(his_surgery_fee_tpl.id) */
    private Long tplId;
    /** 收费项目ID */
    private Long chargeItemId;
    /** 项目名称 */
    private String itemName;
    /** 项目编码 */
    private String itemCode;
    /** 费用分类: 1手术费 2麻醉费 3监测费 4耗材费 5药品费 6其他 */
    private Integer feeCategory;
    /** 数量 */
    private BigDecimal quantity;
    /** 单价 */
    private BigDecimal unitPrice;
    /** 金额 */
    private BigDecimal amount;
}
