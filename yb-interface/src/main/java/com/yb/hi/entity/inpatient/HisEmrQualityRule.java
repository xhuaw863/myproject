package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 病历质控规则(完整性/时限性/逻辑性/规范性四类, 扣分+严重度分级)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_quality_rule")
public class HisEmrQualityRule extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 规则编码 */
    private String ruleCode;
    /** 规则名称 */
    private String ruleName;
    /** 适用记录类型(his_inp_medical_record.record_type) */
    private Integer recordType;
    /** 规则类型: 1完整性 2时限性 3逻辑性 4规范性 */
    private Integer ruleType;
    /** 检查条件JSON */
    private String ruleConfig;
    /** 扣分分值 */
    private BigDecimal deductScore;
    /** 严重程度: 1警告 2扣分 3一票否决 */
    private Integer severity;
    /** 规则说明 */
    private String description;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
