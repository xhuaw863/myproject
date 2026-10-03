package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 发药明细行(批次价驱动定价改造三期 C2): 为整单粒度的 his_dispense 提供行级退药粒度。
 * 一条处方药品明细(同药多行按 prescription_item_id 区分)对应一行, billed_price/billed_amount 直引划价快照原价,
 * 退药按原价退保证"收退恒等不退不平"; returned_qty/returned_amount 累计已退量。
 * 唯一键: tenant_id + dispense_id + prescription_item_id
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_dispense_item")
public class HisDispenseItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 发药记录ID(his_dispense.id) */
    private Long dispenseId;
    /** 处方明细ID(his_prescription_item.id, 行级来源) */
    private Long prescriptionItemId;
    /** 医共体药品目录ID(his_drug_catalog.id) */
    private Long drugCatalogId;
    /** 药品编码 */
    private String drugCode;
    /** 药品名称 */
    private String drugName;
    /** 规格 */
    private String spec;
    /** 单位 */
    private String unit;
    /** 本行发药数量(最小单位) */
    private BigDecimal dispenseQty;
    /** 划价快照单价(原价, 退药计价依据) */
    private BigDecimal billedPrice;
    /** 划价快照金额(原价, 本行整退时直接引用保证不退不平) */
    private BigDecimal billedAmount;
    /** 本行累计已退数量 */
    private BigDecimal returnedQty;
    /** 本行累计已退金额 */
    private BigDecimal returnedAmount;
}
