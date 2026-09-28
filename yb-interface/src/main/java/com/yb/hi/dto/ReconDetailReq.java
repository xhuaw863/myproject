package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【3202】医药机构费用结算对明细账(节点: data, 单行)
 * 规范表198(严格按字段字义与顺序, 不增不减):
 * setl_optins/file_qury_no/stmt_begndate/stmt_enddate/
 * medfee_sumamt/fund_pay_sumamt/cash_payamt/fixmedins_setl_cnt/clr_type/refd_setl_flag/exp_content
 * 注意: refd_setl_flag 在本交易为 3 位(与 3201 的 6 位不同, 规范原文如此)。
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class ReconDetailReq {

    /** 结算经办机构(6位, 必填) */
    private String setlOptins;
    /** 文件查询号(30位, 必填, 9101 上传明细文件后返回) */
    private String fileQuryNo;
    /** 对账开始日期(yyyy-MM-dd, 必填) */
    private String stmtBegndate;
    /** 对账结束日期(yyyy-MM-dd, 必填) */
    private String stmtEnddate;
    /** 医疗费总额(16,2, 必填, 空传"0") */
    private String medfeeSumamt;
    /** 基金支付总额(16,2, 必填, 空传"0") */
    private String fundPaySumamt;
    /** 现金支付金额(16,2, 必填, 空传"0") */
    private String cashPayamt;
    /** 定点医药机构结算笔数(10, 必填, 空传"0") */
    private String fixmedinsSetlCnt;
    /** 清算类别(6位, 必填) */
    private String clrType;
    /** 退费结算标志(3位, 必填) */
    private String refdSetlFlag;
    /** 字段扩展(4000) */
    private String expContent;
}
