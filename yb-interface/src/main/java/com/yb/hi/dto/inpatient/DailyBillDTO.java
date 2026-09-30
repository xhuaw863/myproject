package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 住院每日费用清单(生成请求/展示通用)
 */
@Data
public class DailyBillDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 清单日期 */
    private LocalDate billDate;
    /** 费用项JSON数组 */
    private String items;
    /** 当日费用合计 */
    private BigDecimal totalAmount;
    /** 在院累计费用 */
    private BigDecimal cumulativeAmount;
    /** 预交金余额 */
    private BigDecimal depositBalance;
    /** 生成时间 */
    private LocalDateTime generatedTime;
    /** 是否已打印: 1是 0否 */
    private Integer printedFlag;
    /** 打印时间 */
    private LocalDateTime printTime;
}
