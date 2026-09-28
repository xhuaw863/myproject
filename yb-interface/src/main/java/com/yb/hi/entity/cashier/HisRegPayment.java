package com.yb.hi.entity.cashier;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 挂号收费流水(P1-18): 挂号/退号逐笔正负流水, 日结挂号费与全渠道支付分项的记账依据。
 * 方向: 1挂号收款(正) / -1退号退款(负); pay_method 全渠道口径(现金/微信/支付宝/银行卡/医保/减免)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_reg_payment")
public class HisRegPayment extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 挂号记录ID(his_registration.id) */
    private Long registrationId;
    /** 挂号单号 */
    private String regNo;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 流水方向: 1挂号收款 -1退号退款 */
    private Integer direction;
    /** 金额(正数, 方向由 direction 表达) */
    private BigDecimal amount;
    /** 支付方式全渠道: CASH/WECHAT/ALIPAY/CARD/INSURANCE/FREE */
    private String payMethod;
    /** 医保就诊ID(挂号医保结算时落 mdtrt_id, 对账依据) */
    private String mdtrtId;
    /** 业务时间(挂号时间或退号时间) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime bizTime;
    /** 操作人 */
    private String operator;
}
