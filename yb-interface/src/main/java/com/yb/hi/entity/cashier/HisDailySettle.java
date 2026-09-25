package com.yb.hi.entity.cashier;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 门诊日结(收费员按日汇总: 收费/退费笔数金额 + 现金/基金/个账净额合计, 机构+日期唯一)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_daily_settle")
public class HisDailySettle extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 结算日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate settleDate;
    /** 操作人 */
    private String operator;
    /** 收费笔数 */
    private Integer totalCount;
    /** 收费总金额 */
    private BigDecimal totalAmount;
    /** 退费笔数 */
    private Integer refundCount;
    /** 退费金额 */
    private BigDecimal refundAmount;
    /** 现金合计(收费-退费净额) */
    private BigDecimal cashTotal;
    /** 基金合计(收费-退费净额) */
    private BigDecimal fundTotal;
    /** 个账合计(收费-退费净额) */
    private BigDecimal acctTotal;
    /** 状态: 0未日结 1已日结 */
    private Integer status;
    /** 日结时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime settleTime;
}
