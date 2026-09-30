package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 手术耗材(高值耗材逐台登记: 批号/供应商可追溯, 与手术费用明细互为补充)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_material")
public class HisSurgeryMaterial extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 手术ID(his_surgery.id) */
    private Long surgeryId;
    /** 耗材名称 */
    private String materialName;
    /** 耗材编码 */
    private String materialCode;
    /** 规格 */
    private String spec;
    /** 批号 */
    private String batchNo;
    /** 数量 */
    private BigDecimal quantity;
    /** 单价 */
    private BigDecimal unitPrice;
    /** 金额 */
    private BigDecimal amount;
    /** 供应商 */
    private String supplier;
}
