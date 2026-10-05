package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * RIS质控规则(完整性/时效性/一致性/术语四类, 检查时点 ON_SAVE/ON_SUBMIT/ON_REVIEW/BATCH)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_ris_qc_rule")
public class HisRisQcRule extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 规则编码 */
    private String ruleCode;
    /** 规则名称 */
    private String ruleName;
    /** 类型: COMPLETENESS/TIMELINESS/CONSISTENCY/TERMINOLOGY */
    private String ruleType;
    /** 适用科室类型 */
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
