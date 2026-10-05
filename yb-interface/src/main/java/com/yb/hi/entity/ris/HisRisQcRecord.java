package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * RIS质控评分记录(报告×规则通过与否留痕, 供报告质控评分汇总)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_ris_qc_record")
public class HisRisQcRecord extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 报告ID */
    private Long reportId;
    /** 规则ID */
    private Long ruleId;
    /** 检查时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime checkTime;
    /** 是否通过: 0不通过/1通过 */
    private Integer passed;
    /** 检查详情 */
    private String detail;
}
