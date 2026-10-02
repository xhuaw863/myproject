package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 住院医嘱模板/套餐(个人/科室/全院三级, 单条/套餐两类, items 为医嘱项 JSON 数组)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_order_template")
public class HisOrderTemplate extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板名称 */
    private String templateName;
    /** 模板级别: 1个人 2科室 3全院 */
    private Integer templateType;
    /** 范围类型: 1单条 2套餐 */
    private Integer scopeType;
    /** 科室ID(his_dept.id, 科室级模板) */
    private Long deptId;
    /** 医生ID(his_staff.id, 个人级模板) */
    private Long doctorId;
    /** 医嘱项JSON数组 */
    private String items;
    /** 适用病种编码 */
    private String diseaseCode;
    /** 适用场景: 1普通住院 2手术医嘱(P2b) */
    private Integer applyScene;
    /** 手术模板目标阶段: 1术前 2术中 3术后(apply_scene=2 时有值) */
    private Integer surgeryPhase;
    /** 使用次数 */
    private Integer usageCount;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
