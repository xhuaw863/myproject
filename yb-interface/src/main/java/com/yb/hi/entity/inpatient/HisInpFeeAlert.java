package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 住院费用预警记录(日限额/总限额/预交金不足/大额费用四类, 放行/拦截处置留痕)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_fee_alert")
public class HisInpFeeAlert extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
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
