package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 处方点评自动规则(T2 阶段5-2)。
 * rule_type 供引擎分派: abx_under_level 抗菌越级 / abx_no_auth 无抗菌处方权 / abx_expiry 处方权到期
 * / duplicate_drug 同药重复 / long_abx_duration 长期抗菌疑似超疗程。
 * 命中后按 result_hint 给出建议结论、problem_type_hint 建议问题类型、score_deduct 扣分。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_rx_review_rule")
public class HisRxReviewRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则编码(唯一) */
    private String ruleCode;
    /** 规则名称 */
    private String ruleName;
    /** 规则类型(引擎分派键) */
    private String ruleType;
    /** 严重度: 1提示 2不规范 3不合理 */
    private Integer severity;
    /** 建议结论: 1合理 2不规范 3不合理 */
    private Integer resultHint;
    /** 建议问题类型编码 */
    private String problemTypeHint;
    /** 命中扣分值 */
    private Integer scoreDeduct;
    /** 启用: 1启用 0停用 */
    private Integer enabled;
    /** 规则参数JSON(如 {"days":14}) */
    private String params;
    /** 备注 */
    private String memo;
    /** 机构ID(空=全院级) */
    private Long orgId;
}
