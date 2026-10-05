package com.yb.hi.dto.ris;

import lombok.Data;

/**
 * RIS 质控规则保存请求(落 his_ris_qc_rule)
 */
@Data
public class RisQcRuleDTO {

    /** 规则ID(编辑时传) */
    private Long id;
    /** 规则编码(空则服务端生成) */
    private String ruleCode;
    /** 规则名称 */
    private String ruleName;
    /** 类型: COMPLETENESS/TIMELINESS/CONSISTENCY/TERMINOLOGY */
    private String ruleType;
    /** 适用科室类型(空=通用) */
    private String deptType;
    /** 检查时点: ON_SAVE/ON_SUBMIT/ON_REVIEW/BATCH */
    private String checkPoint;
    /** 规则表达式JSON */
    private String ruleExpression;
    /** 严重度: 1警告/2阻断 */
    private Integer severity;
    /** 扣分 */
    private Integer scoreDeduction;
    /** 提示消息 */
    private String message;
    /** 是否启用 */
    private Integer enabled;
}
