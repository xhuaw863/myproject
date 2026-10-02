package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新生儿建档(P2d 产科分娩一体化): 关联母亲住院就诊与可选分娩手术, 建档后生成新生儿患者(baby_patient_id)
 * 与新生儿住院就诊(baby_inp_visit_id), 医嘱复用住院医嘱链(his_inp_order)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_newborn")
public class HisNewborn extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 母亲住院就诊ID(his_inp_visit.id) */
    private Long motherInpVisitId;
    /** 分娩手术ID(his_surgery.id, 剖宫产可关联, 可空) */
    private Long surgeryId;
    /** 新生儿建档患者ID(his_patient.id) */
    private Long babyPatientId;
    /** 新生儿住院就诊ID(his_inp_visit.id) */
    private Long babyInpVisitId;
    /** 新生儿姓名 */
    private String babyName;
    /** 性别: 1男 2女 */
    private Integer babySex;
    /** 出生时间 */
    private LocalDateTime birthTime;
    /** Apgar 1分钟评分 */
    @TableField("apgar_1")
    private Integer apgar1;
    /** Apgar 5分钟评分 */
    @TableField("apgar_5")
    private Integer apgar5;
    /** Apgar 10分钟评分 */
    @TableField("apgar_10")
    private Integer apgar10;
    /** 出生体重(克) */
    private Integer weightG;
    /** 身长(厘米) */
    private BigDecimal heightCm;
    /** 分娩方式: 1顺产 2剖宫产 3产钳 4臀助 5其他 */
    private Integer birthType;
    /** 状态: 1在绑 2已转科 3已出院 */
    private Integer status;
    /** 备注 */
    private String remark;
}
