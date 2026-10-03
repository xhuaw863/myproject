package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历NLG生成模板: 按章节配置"结构化字段→叙述文本"的自然语言生成规则。
 * - templateText: 含占位符 {fieldKey} 的模板文本(占位符定义参与生成的字段集与模板语序);
 * - sortRules: 输出语序(JSON数组, 如 ["location","nature","symptom","duration"]; 缺省按占位符首现序);
 * - connectors: 相邻字段连接词JSON {"default":"兜底连接词","rules":[{"after":"前字段","before":"后字段","word":"连接词"}]},
 *   规则命中优先于模板字面量; 仅当前后字段均有值时连接词才生效(字段为空连同连接词一并跳过);
 * - scope: 适用范围 0全部 1住院 2门诊; enabled: 1启用 0停用。
 * 表由 DictSchemaMigration 启动期幂等建出。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_nlg_template")
public class HisEmrNlgTemplate extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 适用范围: 0全部 1住院 2门诊 */
    private Integer scope;
    /** 病历章节key(如 chief_complaint) */
    private String sectionKey;
    /** 章节名称 */
    private String sectionName;
    /** 生成模板(含占位符如{field_key}) */
    private String templateText;
    /** 连接词配置JSON(相邻字段间连接词/条件连接) */
    private String connectors;
    /** 语序规则JSON(输出字段顺序数组) */
    private String sortRules;
    /** 是否启用: 1启用 0停用 */
    private Integer enabled;
}
