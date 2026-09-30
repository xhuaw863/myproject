package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 病历质控规则请求
 */
@Data
public class EmrQualityRuleDTO {

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
