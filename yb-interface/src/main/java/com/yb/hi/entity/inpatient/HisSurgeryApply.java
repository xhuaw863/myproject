package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 手术申请单(医生申请→病区护士复核(可退回)→待安排→已安排→已完成, 可作废; 支持住院/门诊/日间三类就诊)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_apply")
public class HisSurgeryApply extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 申请单号(SQ+yyyyMMdd+序号) */
    private String applyNo;
    /** 就诊类型: 1住院 2门诊 3日间 */
    private Integer visitType;
    /** 住院就诊ID(visit_type=1) */
    private Long inpVisitId;
    /** 门诊就诊ID(his_visit.id, visit_type=2/3) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名快照 */
    private String patientName;
    /** 性别快照 */
    private String gender;
    /** 年龄快照 */
    private String age;
    /** 病历号/住院号快照 */
    private String medicalNo;
    /** 床号快照 */
    private String bedNo;
    /** 联系电话 */
    private String phone;
    /** 申请科室ID */
    private Long applyDeptId;
    /** 申请科室名称 */
    private String applyDeptName;
    /** 手术编码ICD-9-CM-3 */
    private String surgeryCode;
    /** 手术名称 */
    private String surgeryName;
    /** 手术级别: 1-4 */
    private Integer surgeryLevel;
    /** 拟麻醉方式: 1全麻 2局麻 3椎管内 4神经阻滞 5复合 6其他 */
    private Integer anesthesiaType;
    /** 拟主刀医师ID(his_staff.id) */
    private Long surgeonId;
    /** 拟主刀医师姓名 */
    private String surgeonName;
    /** 医生期望手术时间(精确到分) */
    private LocalDateTime expectTime;
    /** 手术时限: 1择期 2限期 3急诊 */
    private Integer deadlineType;
    /** 术前诊断 */
    private String preOpDiag;
    /** 手术经过/病情简介 */
    private String applyReason;
    /** 特殊要求(体位/特殊耗材等) */
    private String specialReq;
    /** 申请医师ID(his_staff.id) */
    private Long applyById;
    /** 申请医师姓名 */
    private String applyByName;
    /** 申请时间 */
    private LocalDateTime applyTime;
    /** 状态: 1待复核 2已复核待安排 3已退回 4已安排 5已完成 6已作废 */
    private Integer status;
    /** 复核护士 */
    private String reconfirmBy;
    /** 复核时间 */
    private LocalDateTime reconfirmTime;
    /** 退回原因 */
    private String rejectReason;
    /** 作废原因 */
    private String cancelReason;
    /** 手术ID(his_surgery.id, 安排后回填) */
    private Long surgeryId;
}
