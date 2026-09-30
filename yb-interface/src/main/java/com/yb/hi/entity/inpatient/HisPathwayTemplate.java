package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 临床路径模板(病种入径标准: 适用诊断+科室+平均住院日+预估总费用, 版本化维护)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pathway_template")
public class HisPathwayTemplate extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
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
    /** 版本号 */
    private Integer version;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 路径描述 */
    private String description;
}
