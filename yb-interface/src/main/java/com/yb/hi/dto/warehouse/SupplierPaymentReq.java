package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建供应商付款单请求(草稿)。付款方式:
 * 1全额-对所选入库单一次付清(服务端按各单未结额自动分摊);
 * 2输入总额-按 amount 顺序分摊到所选入库单(不超各单未结额);
 * 3部分分摊-逐单手工指定 items[].paidAmount。
 */
@Data
public class SupplierPaymentReq {

    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 供应商ID(his_supplier.id) */
    private Long supplierId;
    /** 付款日期(yyyy-MM-dd) */
    private String payDate;
    /** 付款方式: 1全额 2输入总额 3部分分摊 */
    private Integer payMethod;
    /** 本次付款总额(方式2必填; 方式1/3由服务端按明细汇总) */
    private BigDecimal amount;
    /** 支付渠道/方式备注 */
    private String payChannel;
    /** 备注 */
    private String remark;
    /** 结算明细(方式1/2仅需 stockInId; 方式3须带 paidAmount) */
    private List<SupplierPaymentItemReq> items;
}
