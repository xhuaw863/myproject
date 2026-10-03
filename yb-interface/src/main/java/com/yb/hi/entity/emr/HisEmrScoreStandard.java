package com.yb.hi.entity.emr;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 病历质控评分标准(病历P5a): 按卫健委电子病历应用水平(五级)建立五类分类评分标准
 * (时效/完整/逻辑/规范/内涵), weight 权重 × base_score 基准分, eval_expression 为 SpEL/JSON 表达式
 * 供评分引擎解析打分。种子(20条)见 DemoDataInitializer.seedScoreStandards; 表由 DictSchemaMigration 启动期幂等建出。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列。
 */
@Data
@TableName("his_emr_score_standard")
public class HisEmrScoreStandard implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 标准编码(租户内唯一) */
    private String standardCode;
    /** 标准名称 */
    private String standardName;
    /** 病历类型(null=全类型) */
    private Integer recordType;
    /** 分类: 时效/完整/逻辑/规范/内涵 */
    private String category;
    /** 子类 */
    private String subCategory;
    /** 基准分 */
    private BigDecimal baseScore;
    /** 权重 */
    private BigDecimal weight;
    /** 标准说明 */
    private String description;
    /** SpEL/JSON表达式 */
    private String evalExpression;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;
    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    /** 更新人 */
    @TableField(fill = FieldFill.UPDATE)
    private String updateBy;
    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    /** 逻辑删除: 0正常 1删除 */
    @TableLogic
    private Integer deleted;
}

