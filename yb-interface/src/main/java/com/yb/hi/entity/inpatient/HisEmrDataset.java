package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历数据集: 章节/小节/数据元三级结构定义(数据集层), 供病历模板挂载(his_emr_template.dataset_id)
 * 与结构化书写取元; 数据元明细见 his_emr_dataset_element(HisEmrDatasetElement)。
 * 表由 DictSchemaMigration 启动期幂等建出。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_dataset")
public class HisEmrDataset extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 数据集编码 */
    private String code;
    /** 数据集名称 */
    private String name;
    /** 适用范围: 0全部 1住院 2门诊 3护理 */
    private Integer scope;
    /** 描述 */
    private String description;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
