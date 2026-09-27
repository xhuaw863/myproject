package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 药品请领明细: 请领/审核(发货)/实收 三段数量, 发货时按 FIFO 回填扣减批次。
 */
@Data
@TableName("his_requisition_item")
public class HisRequisitionItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 请领单ID */
    private Long requisitionId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码(快照) */
    private String drugCode;
    /** 药品名称(快照) */
    private String drugName;
    /** 规格(快照) */
    private String spec;
    /** 批次号(发货回填, 空=请领时不指定) */
    private String batchNo;
    /** 请领数量 */
    private BigDecimal qtyApply;
    /** 审核(发货)数量 */
    private BigDecimal qtyApproved;
    /** 实收数量 */
    private BigDecimal qtyReceived;
    /** 零售价(快照) */
    private BigDecimal retailPrice;
    /** 小计金额 */
    private BigDecimal amount;
    /** 备注 */
    private String remark;
}
