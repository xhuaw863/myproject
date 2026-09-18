package com.yb.hi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 结算记录(本地留存门诊/住院结算结果)
 */
@Data
@TableName("setl_record")
public class SetlRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 结算ID */
    private String setlId;
    /** 就诊ID */
    private String mdtrtId;
    /** 人员编号 */
    private String psnNo;
    /** 人员姓名 */
    private String psnName;
    /** 险种类型 */
    private String insutype;
    /** 医疗类别 */
    private String medType;
    /** 业务类型: outpatient-门诊, inpatient-住院 */
    private String bizType;
    /** 交易编号 2207/2304等 */
    private String infno;
    /** 结算时间 */
    private String setlTime;
    /** 医疗费总额 */
    private BigDecimal medfeeSumamt;
    /** 基金支付总额 */
    private BigDecimal fundPaySumamt;
    /** 个人负担总金额 */
    private BigDecimal psnPartAmt;
    /** 个人账户支出 */
    private BigDecimal acctPay;
    /** 个人现金支出 */
    private BigDecimal psnCashPay;
    /** 状态: 1-已结算 0-已撤销 */
    private String status;
    /** 结算信息原始JSON */
    private String setlinfoJson;
    /** 创建时间 */
    private String crteTime;
}
