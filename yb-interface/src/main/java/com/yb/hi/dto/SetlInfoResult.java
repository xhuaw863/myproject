package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 结算信息输出(节点: setlinfo)
 * 用于 2206/2207/2208/2303/2304/2305 结算类交易输出解析
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class SetlInfoResult {

    /** 就诊ID */
    private String mdtrtId;
    /** 结算ID */
    private String setlId;
    /** 人员编号 */
    private String psnNo;
    /** 人员姓名 */
    private String psnName;
    /** 险种类型 */
    private String insutype;
    /** 医疗类别 */
    private String medType;
    /** 结算时间 */
    private String setlTime;
    /** 医疗费总额 */
    private BigDecimal medfeeSumamt;
    /** 全自费金额 */
    private BigDecimal fulamtOwnpayAmt;
    /** 超限价自费费用 */
    private BigDecimal overlmtSelfpay;
    /** 先行自付金额 */
    private BigDecimal preselfpayAmt;
    /** 符合政策范围金额 */
    private BigDecimal inscpScpAmt;
    /** 实际支付起付线 */
    private BigDecimal actPayDedc;
    /** 基本医疗保险统筹基金支出 */
    private BigDecimal hifpPay;
    /** 基金支付总额 */
    private BigDecimal fundPaySumamt;
    /** 个人负担总金额 */
    private BigDecimal psnPartAmt;
    /** 个人账户支出 */
    private BigDecimal acctPay;
    /** 个人现金支出 */
    private BigDecimal psnCashPay;
    /** 医院负担金额 */
    private BigDecimal hospPartAmt;
    /** 余额 */
    private BigDecimal balc;
    /** 个人账户共济支付金额 */
    private BigDecimal acctMulaidPay;
    /** 医药机构结算ID */
    private String medinsSetlId;
    /** 清算经办机构 */
    private String clrOptins;
    /** 清算方式 */
    private String clrWay;
    /** 清算类别 */
    private String clrType;
}
