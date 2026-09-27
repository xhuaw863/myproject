package com.yb.hi.entity.treatment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 治疗执行记录(逐次留痕: 序次/治疗师/设备/时长/参数/反应)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_treatment_exec")
public class HisTreatmentExec extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 治疗执行单号(ZX+日期+序号) */
    private String execNo;
    /** 治疗计划ID(his_treatment_plan.id) */
    private Long planId;
    /** 医嘱单ID(his_order.id) */
    private Long orderId;
    /** 医嘱明细ID(his_order_item.id) */
    private Long orderItemId;
    /** 患者ID */
    private Long patientId;
    /** 疗程内序次(第几次) */
    private Integer sessionIndex;
    /** 执行日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate execDate;
    /** 治疗师ID(his_staff.id) */
    private Long execTherapistId;
    /** 设备编码(his_treatment_equipment.equip_code) */
    private String equipmentCode;
    /** 治疗时长(分钟) */
    private Integer durationMin;
    /** 治疗参数(JSON) */
    private String parameters;
    /** 患者反应 */
    private String patientResponse;
    /** 患者签到时间(非空=已签到) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime checkinTime;
    /** 取消原因 */
    private String cancelReason;
    /** 执行状态: 0待执行 1执行中 2已完成 3已取消 */
    private Integer execStatus;
}
