package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 绿色通道信用额度(急危重症先诊疗后付费台账)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_green_channel_credit")
public class HisGreenChannelCredit extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 开通时就诊ID */
    private Long visitId;
    /** 信用额度(元) */
    private BigDecimal creditLimit;
    /** 已使用额度(元) */
    private BigDecimal usedAmount;
    /** 开通原因(急危重症/证件缺失等) */
    private String reason;
    /** 状态: 1-启用 0-关闭 */
    private Integer status;
    /** 机构ID */
    private Long orgId;
}
