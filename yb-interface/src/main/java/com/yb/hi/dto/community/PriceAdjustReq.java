package com.yb.hi.dto.community;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 医共体目录调价请求(三目录统一): 调价文号 + 生效日期必录, 写留痕并同步目录当前价。
 */
@Data
public class PriceAdjustReq {
    /** 目录类型: charge/drug/cons */
    private String catalogType;
    /** 目录记录ID */
    private Long catalogId;
    /** 调价字段: charge=price_l1/price_l2/price_l3; drug=purchase_price/retail_price; cons=purchase_price/charge_price */
    private String priceField;
    /** 新价 */
    private BigDecimal newPrice;
    /** 收费项目价格档次:1/2/3(仅 charge 用, 与 priceField 对应) */
    private Integer orgLevel;
    /** 调价文号(必录) */
    private String adjustDocNo;
    /** 生效日期(必录) */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effDate;
    /** 调价原因 */
    private String reason;
}
