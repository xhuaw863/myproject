package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 结算/预结算 输入(节点: data)
 * 用于 2206门诊预结算 / 2207门诊结算 / 2303住院预结算 / 2304住院结算
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class SettlementReq {

    /** 人员编号 */
    private String psnNo;
    /** 就诊凭证类型 */
    private String mdtrtCertType;
    /** 就诊凭证编号 */
    private String mdtrtCertNo;
    /** 医疗类别 */
    private String medType;
    /** 医疗费总额 */
    private BigDecimal medfeeSumamt;
    /** 个人结算方式 */
    private String psnSetlway;
    /** 就诊ID */
    private String mdtrtId;
    /** 收费批次号 */
    private String chrgBchno;
    /** 个人账户使用标志 */
    private String acctUsedFlag;
    /** 险种类型 */
    private String insutype;
    /** 发票号(正式结算) */
    private String invono;
    /** 全自费金额 */
    private BigDecimal fulamtOwnpayAmt;
    /** 超限价金额 */
    private BigDecimal overlmtSelfpay;
    /** 先行自付金额 */
    private BigDecimal preselfpayAmt;
    /** 符合政策范围金额 */
    private BigDecimal inscpScpAmt;
    /** 公立医院改革标志 */
    private String pubHospRfomFlag;
    /** 字段扩展 */
    private String expContent;
}
