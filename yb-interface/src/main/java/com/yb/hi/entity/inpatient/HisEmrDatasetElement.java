package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历数据集数据元: 数据集内单个数据元定义(章节/小节归属 + 类型/字典来源/默认值/校验/
 * 防复制/打印隐藏等书写属性)。同一数据集内 field_key 唯一(跨数据集可复用标准键)。
 * 表由 DictSchemaMigration 启动期幂等建出。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_dataset_element")
public class HisEmrDatasetElement extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 所属数据集ID(his_emr_dataset.id) */
    private Long datasetId;
    /** 章节key */
    private String chapterKey;
    /** 章节名称 */
    private String chapterName;
    /** 小节key(可空, 空=直接挂章节下) */
    private String sectionKey;
    /** 小节名称 */
    private String sectionName;
    /** 数据元key(同数据集内唯一) */
    private String fieldKey;
    /** 数据元名称 */
    private String fieldName;
    /** 类型: text/number/date/datetime/select/multiselect/checkbox/dict/textarea */
    private String fieldType;
    /** 字典来源(dict_type 或自定义值域编码) */
    private String dictSource;
    /** 默认值 */
    private String defaultValue;
    /** 是否必填: 1是 0否 */
    private Integer required;
    /** 是否只读: 1是 0否 */
    private Integer readonly;
    /** 防复制标志: 1禁止 0允许 */
    private Integer noCopy;
    /** 打印隐藏: 1隐藏 0显示 */
    private Integer printHidden;
    /** 最大长度 */
    private Integer maxLength;
    /** 校验规则(正则或表达式) */
    private String validationRule;
    /** 排序号 */
    private Integer sortNo;
}
