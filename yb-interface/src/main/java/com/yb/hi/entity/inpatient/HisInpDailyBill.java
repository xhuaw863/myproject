package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 住院每日费用清单(就诊×日期一份, 费用项 JSON + 当日/累计/预交金余额三金额, 打印留痕)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_daily_bill")
public class HisInpDailyBill extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
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
