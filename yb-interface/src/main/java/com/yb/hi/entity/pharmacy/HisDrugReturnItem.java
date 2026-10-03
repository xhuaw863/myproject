package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 退药明细行(批次价驱动定价改造三期 C2): 一次退药申请的具体行退量记录。
 * 审批通过据本行回库并累加 his_dispense_item.returned_qty; return_amount 按发药行的划价原价计算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_drug_return_item")
public class HisDrugReturnItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 退药记录ID(his_drug_return.id) */
    private Long returnId;
    /** 发药明细行ID(his_dispense_item.id) */
    private Long dispenseItemId;
    /** 医共体药品目录ID */
    private Long drugCatalogId;
    /** 药品编码 */
    private String drugCode;
    /** 药品名称 */
    private String drugName;
    /** 规格 */
    private String spec;
    /** 单位 */
    private String unit;
    /** 本次该行退药数量 */
    private BigDecimal returnQty;
    /** 本次该行退药金额(按划价原价) */
    private BigDecimal returnAmount;
}
