package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * RIS报告结构化数据元(FINDINGS/CONCLUSION/TECHNIQUE 三段结构化控件定义)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_ris_report_element")
public class HisRisReportElement extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 所属模板 */
    private Long templateId;
    /** 数据元编码 */
    private String elementCode;
    /** 数据元名称 */
    private String elementName;
    /** 类型: TEXT/NUMBER/SELECT/MULTISELECT/RADIO/MEASUREMENT */
    private String elementType;
    /** 所属段: FINDINGS/CONCLUSION/TECHNIQUE */
    private String section;
    /** 单位 */
    private String valueUnit;
    /** 候选值JSON */
    private String valueOptions;
    /** 默认值 */
    private String defaultValue;
    /** 正常范围 */
    private String normalRange;
    /** 排序 */
    private Integer sortOrder;
    /** 是否必填 */
    private Integer required;
}
