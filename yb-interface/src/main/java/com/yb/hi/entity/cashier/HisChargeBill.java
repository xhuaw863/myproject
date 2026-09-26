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
 * 收费单(处方/项目合并收费, 医保结算四分: 自付/基金/现金/个账)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_charge_bill")
public class HisChargeBill extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 收费单号(SF+日期+序号=收费单, TF+日期+序号=退费单) */
    private String billNo;
    /** 就诊ID */
    private Long visitId;
    /** 挂号ID */
    private Long registrationId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 单据类型: 1门诊收费 2门诊退费 */
    private Integer billType;
    /** 总金额 */
    private BigDecimal totalAmount;
    /** 自付金额 */
    private BigDecimal selfPay;
    /** 基金支付 */
    private BigDecimal fundPay;
    /** 现金支付 */
    private BigDecimal cashPay;
    /** 个账支付 */
    private BigDecimal acctPay;
    /** 医保结算ID */
    private String setlId;
    /** 主要支付方式: CASH/WECHAT/ALIPAY/CARD/INSURANCE/FREE */
    private String payMethod;
    /** 退费关联原单ID(退费单指向原收费单) */
    private Long originBillId;
    /** 发票号(开票后回填) */
    private String invoiceNo;
    /** 状态: 0待收费 1已收费 2已退费 */
    private Integer status;
    /** 收费员 */
    private String chargeBy;
    /** 收费时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime chargeTime;
    /** 备注(退费单记录原单号与退费原因) */
    private String remark;
}
