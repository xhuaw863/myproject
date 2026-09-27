package com.yb.hi.entity.medtech;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 危急值规则(检验项目阈值判定, 低于下限/高于上限即危急)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_critical_rule")
public class HisCriticalRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 项目编码 */
    private String itemCode;
    /** 项目名称 */
    private String itemName;
    /** 危急低阈值(低于即危急) */
    private BigDecimal lowThreshold;
    /** 危急高阈值(高于即危急) */
    private BigDecimal highThreshold;
    /** 患者类型: adult成人/child儿童(空=通用) */
    private String patientType;
    /** 是否启用: 1启用 0停用 */
    private Integer isActive;
}
