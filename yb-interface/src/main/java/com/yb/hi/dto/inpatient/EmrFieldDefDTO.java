package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 病历结构化模板字段定义(作为 his_emr_template.fields JSON 数组元素)
 */
@Data
public class EmrFieldDefDTO {

    /** 字段编码 */
    private String fieldCode;
    /** 字段名称 */
    private String fieldName;
    /** 字段类型: text/textarea/select/date/datetime/number/radio/checkbox */
    private String fieldType;
    /** 是否必填: 1是 0否 */
    private Integer required;
    /** 选项(单选/多选/下拉用, JSON或逗号分隔) */
    private String options;
    /** 默认值(可填宏变量占位符) */
    private String defaultValue;
    /** 占位提示 */
    private String placeholder;
    /** 排序号 */
    private Integer sortNo;
}
