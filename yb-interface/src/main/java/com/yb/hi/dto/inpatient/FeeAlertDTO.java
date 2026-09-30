package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 住院费用预警请求/记录(预警触发落库, 处置时回写处置字段)
 */
@Data
public class FeeAlertDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 预警类型: 1日限额 2总限额 3预交金不足 4大额费用 */
    private Integer alertType;
    /** 触发费用明细ID(his_inp_charge_detail.id) */
    private Long chargeDetailId;
    /** 预警时金额 */
    private BigDecimal alertAmount;
    /** 阈值金额 */
    private BigDecimal thresholdAmount;
    /** 处理人ID(his_staff.id) */
    private Long handlerId;
    /** 处理时间 */
    private LocalDateTime handleTime;
    /** 处理结果: 1放行 2拦截 */
    private Integer handleResult;
    /** 放行/超标原因 */
    private String overrideReason;
}
