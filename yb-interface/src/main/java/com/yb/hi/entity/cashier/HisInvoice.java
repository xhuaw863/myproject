package com.yb.hi.entity.cashier;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发票(收费开票记录; 作废/红冲均指向原发票, 不物理删除)
 * 唯一键: tenant_id + org_id + invoice_no
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_invoice")
public class HisInvoice extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 发票号(前缀+号池序号) */
    private String invoiceNo;
    /** 发票号池ID(his_invoice_pool.id) */
    private Long poolId;
    /** 关联收费单ID(his_charge_bill.id) */
    private Long billId;
    /** 发票类型: NORMAL-正常 / VOID-作废 / RED-红冲 */
    private String invoiceType;
    /** 开票金额 */
    private BigDecimal amount;
    /** 患者姓名 */
    private String patientName;
    /** 状态: 1正常 2已作废 3已红冲 */
    private Integer status;
    /** 作废/红冲原因 */
    private String voidReason;
    /** 作废/红冲操作人 */
    private String voidBy;
    /** 作废/红冲时间 */
    private LocalDateTime voidTime;
    /** 原发票ID(作废/红冲时指向原发票) */
    private Long originalInvoiceId;
}
