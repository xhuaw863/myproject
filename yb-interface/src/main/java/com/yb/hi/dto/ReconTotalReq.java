package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【3201】医药机构费用结算对总账(节点: data, 单行)
 * 规范表196(严格按字段字义与顺序, 不增不减):
 * insutype/clr_type/setl_optins/stmt_begndate/stmt_enddate/
 * medfee_sumamt/fund_pay_sumamt/acct_pay/fixmedins_setl_cnt/refd_setl_flag/exp_content
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class ReconTotalReq {

    /** 险种类型(表: 6位, 必填, 代码标识Y) */
    private String insutype;
    /** 清算类别(6位, 必填) */
    private String clrType;
    /** 结算经办机构(6位, 必填) */
    private String setlOptins;
    /** 对账开始日期(yyyy-MM-dd, 必填) */
    private String stmtBegndate;
    /** 对账结束日期(yyyy-MM-dd, 必填) */
    private String stmtEnddate;
    /** 医疗费总额(16,2, 必填, 空传"0") */
    private String medfeeSumamt;
    /** 基金支付总额(16,2, 必填, 空传"0") */
    private String fundPaySumamt;
    /** 个人账户支付金额(16,2, 必填, 空传"0") */
    private String acctPay;
    /** 定点医药机构结算笔数(10, 必填, 空传"0") */
    private String fixmedinsSetlCnt;
    /** 退费结算标志(6位, 必填) */
    private String refdSetlFlag;
    /** 字段扩展(4000) */
    private String expContent;
}
