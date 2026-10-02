package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 手术一体化专属字段(手麻P4b): 与 his_surgery 1:1 旁挂, 按 module_type 承载 DSA/内镜/产科的专属采集项,
 * 不动主表列。create/schedule module_type∈{2,3,4} 时 upsert, detail 回带。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_module_ext")
public class HisSurgeryModuleExt extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 手术ID(his_surgery.id) */
    private Long surgeryId;
    /** 一体化模块: 1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗 */
    private Integer moduleType;
    /** DSA造影设备 */
    private String dsaEquipment;
    /** DSA对比剂 */
    private String dsaContrast;
    /** DSA辐射剂量(mGy) */
    private BigDecimal dsaRadiationDose;
    /** 内镜镜种 */
    private String endoScopeType;
    /** 内镜活检数 */
    private Integer endoBiopsyCnt;
    /** 产科孕周 */
    private String obstGestationalWeek;
    /** 分娩方式: 1顺产 2剖宫产 3产钳 */
    private Integer obstBirthType;
    /** 备注 */
    private String remark;
}
