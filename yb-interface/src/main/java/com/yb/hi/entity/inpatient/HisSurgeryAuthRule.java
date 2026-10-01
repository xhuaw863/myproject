package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 手术操作权限规则(两种模式: 按手术等级/按服务项目自定义分类, 控制可申请手术的主刀人员范围)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_auth_rule")
public class HisSurgeryAuthRule extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 规则名称 */
    private String ruleName;
    /** 规则类型: 1按手术等级 2按自定义分类 */
    private Integer ruleType;
    /** 等级模式: 受限最低手术级别(≥该级需权限校验) */
    private Integer surgeryLevel;
    /** 自定义模式: 手术编码/名称逗号清单 */
    private String surgeryCodes;
    /** 允许主刀的职工ID清单(his_staff.id, 逗号分隔) */
    private String allowStaffIds;
    /** 备注 */
    private String remark;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
