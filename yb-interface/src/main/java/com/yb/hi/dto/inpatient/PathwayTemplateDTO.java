package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 临床路径模板创建/编辑请求
 */
@Data
public class PathwayTemplateDTO {

    /** 路径编码 */
    private String pathwayCode;
    /** 路径名称 */
    private String pathwayName;
    /** 适用诊断编码ICD-10 */
    private String diseaseCode;
    /** 适用诊断名称 */
    private String diseaseName;
    /** 适用科室ID(his_dept.id) */
    private Long deptId;
    /** 平均住院日 */
    private Integer avgLength;
    /** 预估总费用 */
    private BigDecimal totalCost;
    /** 路径描述 */
    private String description;
}
