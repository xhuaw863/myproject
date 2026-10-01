package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 供应商付款单主表(单号 FK+yyyyMMdd+4位, 租户内唯一): 按供应商对已验收入库单做付款结算。
 * 付款方式 pay_method: 1全额(所选入库单一次付清) 2输入总额(按输入额顺序分摊) 3部分分摊(逐单手工分摊);
 * 确认付款后按明细回写来源入库单 his_stock_in.paid_amount/paid_status。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_supplier_payment")
public class HisSupplierPayment extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 付款单号(FK+yyyyMMdd+4位序号) */
    private String payNo;
    /** 供应商ID(his_supplier.id) */
    private Long supplierId;
    /** 付款日期 */
    private LocalDate payDate;
    /** 付款方式: 1全额 2输入总额 3部分分摊 */
    private Integer payMethod;
    /** 本次付款总额 */
    private BigDecimal amount;
    /** 支付渠道/方式备注 */
    private String payChannel;
    /** 状态: 0草稿 1已确认 */
    private Integer status;
    /** 确认人 */
    private String confirmBy;
    /** 确认时间 */
    private LocalDateTime confirmTime;
    /** 备注 */
    private String remark;
}
