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
 * 手术费用明细(手术费/麻醉费/监测费/耗材/药品六类, 自动计时分钟计价与手动记账双轨)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_fee")
public class HisSurgeryFee extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
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
    /** 记账时间 */
    private LocalDateTime chargeTime;
    /** 自动计时: 1自动 0手动 */
    private Integer autoFlag;
    /** 计时分钟数 */
    private Integer durationMinutes;
    /** 操作员ID(his_staff.id) */
    private Long operatorId;
    /** 状态: 1正常 2退费 */
    private Integer status;
}
