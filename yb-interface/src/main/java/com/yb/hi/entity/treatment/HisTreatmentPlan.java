package com.yb.hi.entity.treatment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 治疗计划(疗程医嘱: 总次数/频次/起止日期)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_treatment_plan")
public class HisTreatmentPlan extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 治疗计划号(ZL+日期+序号) */
    private String planNo;
    /** 就诊ID(his_visit.id) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 开单医师ID(his_staff.id) */
    private Long doctorId;
    /** 医嘱单ID(his_order.id) */
    private Long orderId;
    /** 收费项目编码(his_charge_item.item_code) */
    private String itemCode;
    /** 治疗项目名称 */
    private String itemName;
    /** 治疗类别: physiotherapy理疗/rehab康复/tcm中医传统 */
    private String category;
    /** 总次数(疗程) */
    private Integer totalSessions;
    /** 已完成次数 */
    private Integer completedSessions;
    /** 频次(如每日1次/隔日1次) */
    private String frequency;
    /** 开始日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;
    /** 失效日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expireDate;
    /** 状态: 0执行中 1已完成 2已终止 */
    private Integer status;
    /** 终止原因 */
    private String terminateReason;
    /** 备注 */
    private String remark;
}
