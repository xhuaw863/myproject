package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 病历宏变量定义请求
 */
@Data
public class EmrMacroDTO {

    /** 宏变量编码 */
    private String macroCode;
    /** 宏变量名称 */
    private String macroName;
    /** 数据来源: 1患者 2就诊 3诊断 4医嘱 5检验 6体征 */
    private Integer dataSource;
    /** 来源字段 */
    private String sourceField;
    /** 格式化模式 */
    private String formatPattern;
    /** 说明 */
    private String description;
}
