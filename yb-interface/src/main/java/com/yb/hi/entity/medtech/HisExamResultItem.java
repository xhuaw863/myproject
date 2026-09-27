package com.yb.hi.entity.medtech;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 检验结果明细项(逐项结果与参考范围比对)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_exam_result_item")
public class HisExamResultItem extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 报告ID(his_exam_report.id) */
    private Long reportId;
    /** 项目编码 */
    private String itemCode;
    /** 项目名称 */
    private String itemName;
    /** 结果值 */
    private String resultValue;
    /** 结果单位 */
    private String resultUnit;
    /** 参考范围下限 */
    private BigDecimal refRangeLow;
    /** 参考范围上限 */
    private BigDecimal refRangeHigh;
    /** 异常标志: 0正常 1偏高 2偏低 3危急值 */
    private Integer abnormalFlag;
    /** 备注 */
    private String remark;
}
