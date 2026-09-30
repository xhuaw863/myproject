package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 手术费用记账请求(自动计时/手动记账)
 */
@Data
public class SurgeryFeeDTO {

    /** 手术ID(his_surgery.id) */
    private Long surgeryId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 收费项目ID */
    private Long chargeItemId;
    /** 项目名称 */
    private String itemName;
    /** 项目编码 */
    private String itemCode;
    /** 费用分类: 1手术费 2麻醉费 3监测费 4耗材费 5药品费 6其他 */
    private Integer feeCategory;
    /** 数量 */
    private BigDecimal quantity;
    /** 单价 */
    private BigDecimal unitPrice;
    /** 金额 */
    private BigDecimal amount;
    /** 自动计时: 1自动 0手动 */
    private Integer autoFlag;
    /** 计时分钟数 */
    private Integer durationMinutes;
}
