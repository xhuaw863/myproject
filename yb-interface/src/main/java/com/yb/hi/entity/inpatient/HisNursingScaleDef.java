package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 护理评估量表定义(入院评估/专科/风险三类, 维度定义与分数→风险映射 JSON, 按频次驱动评估任务)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_scale_def")
public class HisNursingScaleDef extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 量表编码 */
    private String scaleCode;
    /** 量表名称 */
    private String scaleName;
    /** 量表类型: 1入院评估 2专科 3风险 */
    private Integer scaleType;
    /** 维度定义JSON */
    private String dimensions;
    /** 分数→风险映射JSON */
    private String scoreInterpretation;
    /** 必评频次说明 */
    private String requiredFrequency;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
