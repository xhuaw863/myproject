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
 * 支付明细(收费单多方式混合支付逐笔记录; 无 orgId, 机构归属随收费单)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_payment_detail")
public class HisPaymentDetail extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收费单ID(his_charge_bill.id) */
    private Long billId;
    /** 支付方式: CASH-现金 / WECHAT-微信 / ALIPAY-支付宝 / CARD-银行卡 / INSURANCE-医保 / FREE-免费 */
    private String payMethod;
    /** 支付金额 */
    private BigDecimal amount;
    /** 支付流水号(第三方支付/医保结算单号) */
    private String payRef;
    /** 支付时间 */
    private LocalDateTime payTime;
}
