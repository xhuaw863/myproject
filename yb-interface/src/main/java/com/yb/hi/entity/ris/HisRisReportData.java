package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * RIS报告结构化数据值(报告×数据元键值: 文本/数值/编码三载体)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_ris_report_data")
public class HisRisReportData extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 报告ID(his_exam_report.id) */
    private Long reportId;
    /** 数据元ID */
    private Long elementId;
    /** 数据元编码 */
    private String elementCode;
    /** 文本值 */
    private String valueText;
    /** 数值 */
    private BigDecimal valueNumber;
    /** 编码值 */
    private String valueCode;
}
