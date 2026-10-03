package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 住院预交金操作请求(缴纳/退还)
 */
@Data
public class InpDepositDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 金额 */
    private BigDecimal amount;
    /** 支付方式(his_pay_method_dict.code 规范码; 历史数字码 1现金/2微信/3支付宝/4银行卡 经字典 legacy_codes 归一) */
    private String payType;
    /** 方向: 1缴纳 2退还 */
    private Integer direction;
    /** 备注 */
    private String remark;
}
