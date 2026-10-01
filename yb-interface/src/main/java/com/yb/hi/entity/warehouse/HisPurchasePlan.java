package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 药品采购计划主表(单号 CH+yyyyMMdd+4位, 租户内唯一): 智能生成或手工编制, 走 提交/审批 流转, 已审计划可转采购订单。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_purchase_plan")
public class HisPurchasePlan extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 计划单号(CH+yyyyMMdd+4位序号) */
    private String planNo;
    /** 目标药库ID */
    private Long warehouseId;
    /** 供应商ID(空=多供应商, 转订单时指定) */
    private Long supplierId;
    /** 生成方式: auto智能/manual手工 */
    private String genType;
    /** 状态: 0草稿 1待审 2已审 3已驳回 9已转订单 -2作废 */
    private Integer status;
    /** 计划金额合计 */
    private BigDecimal totalAmount;
    /** 提交人 */
    private String submitBy;
    /** 提交时间 */
    private LocalDateTime submitTime;
    /** 审批人 */
    private String approveBy;
    /** 审批时间 */
    private LocalDateTime approveTime;
    /** 备注 */
    private String remark;
}
