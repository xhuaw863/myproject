package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历模板引用反查索引(his_emr_ref_index): 保存模板时按 document 重算该模板引用的
 * 片段/图示/数据元(fieldKey)/宏(macroCode), 支撑"某组件被哪些模板引用"的影响分析。
 * 表由 DictSchemaMigration 启动期幂等建出; tenant_id 由租户插件注入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_ref_index")
public class HisEmrRefIndex extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 引用源模板ID */
    private Long sourceTemplateId;
    /** 引用物类型:fragment/drawing/element/macro */
    private String refType;
    /** 引用键(fragmentId/drawingCode/fieldKey/macroCode) */
    private String refKey;
}
