package com.yb.hi.entity.treatment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 治疗设备(机构级台账, 治疗执行可绑定设备编码)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_treatment_equipment")
public class HisTreatmentEquipment extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 设备编码 */
    private String equipCode;
    /** 设备名称 */
    private String equipName;
    /** 设备类型(理疗/康复/中医传统) */
    private String equipType;
    /** 归属科室ID(his_dept.id) */
    private Long deptId;
    /** 状态: 1正常 2维修 0停用 */
    private Integer status;
}
