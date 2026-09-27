package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 药品请领单主表(药房→药库): 药房向来源药库请领药品, 审批发货=对来源药库出库, 确认收货=对药房库存位入库。
 * 状态机: 0草稿 1待审核 2已发货 3已收货 -1已驳回 -2已作废。单号 QL+yyyyMMdd+4位(租户内唯一)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_requisition")
public class HisRequisition extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 请领单号 */
    private String reqNo;
    /** 请领药房ID(his_pharmacy_def.id) */
    private Long pharmacyId;
    /** 发货来源药库ID(his_warehouse_def.id) */
    private Long toWarehouseId;
    /** 状态: 0草稿 1待审核 2已发货 3已收货 -1已驳回 -2已作废 */
    private Integer status;
    /** 申请人 */
    private String applyBy;
    /** 申请时间 */
    private LocalDateTime applyTime;
    /** 审核人 */
    private String approveBy;
    /** 审核时间 */
    private LocalDateTime approveTime;
    /** 收货人 */
    private String receiveBy;
    /** 收货时间 */
    private LocalDateTime receiveTime;
    /** 发货出库单ID(his_stock_out.id) */
    private Long stockOutId;
    /** 收货入库单ID(his_stock_in.id) */
    private Long stockInId;
    /** 请领金额合计 */
    private BigDecimal totalAmount;
    /** 备注 */
    private String remark;
}
