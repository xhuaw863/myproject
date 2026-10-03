package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * CDSS 临床决策支持规则: condition_expr 条件JSON 命中后按 severity 分级提示(info/warning/block)。
 * 五类规则: drug_conflict(药物冲突)/dose_alert(剂量预警)/repeat_exam(重复检查)/critical_value(危急值)/guideline(指南推荐)。
 * applicable_depts 为适用科室ID(或编码)列表JSON(null/空数组=全院适用); action_message 支持 {fieldKey} 占位符, 评估时插值。
 * 表由 DictSchemaMigration 启动期幂等建出(含 rule_code 列)。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_cdss_rule")
public class HisCdssRule extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID(写入时当前机构; 规则读为租户共享主数据, 机构仅留痕归属) */
    private Long orgId;
    /** 规则编码(种子/程序化识别; 租户内唯一, 可空=按名称识别) */
    private String ruleCode;
    /** 规则名称 */
    private String name;
    /** 类型: drug_conflict/dose_alert/repeat_exam/critical_value/guideline */
    private String ruleType;
    /** 条件表达式JSON: {field,op,value} 单条件或 {and:[...]}/{or:[...]} 组合 */
    private String conditionExpr;
    /** 提示消息(支持 {fieldKey} 占位符, 评估时以命中字段值插值) */
    private String actionMessage;
    /** 严重程度: info信息 / warning警示放行 / block阻断保存签署 */
    private String severity;
    /** 知识来源 */
    private String knowledgeSource;
    /** 适用科室ID(或编码)列表JSON; null/空数组=全院适用 */
    private String applicableDepts;
    /** 是否启用: 1启用 0停用 */
    private Integer enabled;
    /** 排序号 */
    private Integer sortNo;
}
