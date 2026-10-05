package com.yb.hi.dto.ris;

import lombok.Data;

/**
 * RIS 结构化数据元定义(保存/回显, 落 his_ris_report_element)
 */
@Data
public class RisElementDTO {

    /** 数据元ID(编辑时传) */
    private Long id;
    /** 所属模板ID(随模板保存时可空, 由服务端回填) */
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
    /** 候选值JSON(如 ["正常","增大","缩小"]) */
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
