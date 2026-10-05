package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检查申请单(门诊/住院/急诊/体检四源统一, 医保 4501 检查信息字段在申请侧落列)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_exam_request")
public class HisExamRequest extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 申请单号(JC+日期+序号) */
    private String requestNo;
    /** 来源: 1门诊/2住院/3急诊/4体检 */
    private Integer sourceType;
    /** 关联门诊医嘱(his_order.id) */
    private Long orderId;
    /** 关联住院医嘱(his_inp_order.id) */
    private Long inpOrderId;
    /** 患者ID */
    private Long patientId;
    /** 门诊就诊ID */
    private Long visitId;
    /** 住院就诊ID */
    private Long inpVisitId;
    /** 就医流水号(医保4501.mdtrt_sn) */
    private String mdtrtSn;
    /** 医保就诊ID(医保4501.mdtrt_id) */
    private String mdtrtId;
    /** 医保人员编号(医保4501.psn_no) */
    private String psnNo;
    /** 收费项目ID */
    private Long chargeItemId;
    /** 收费项目编码(院内) */
    private String chargeItemCode;
    /** 收费项目名称(院内) */
    private String chargeItemName;
    /** 医保检查项目代码 */
    private String examItemCode;
    /** 医保检查项目名称 */
    private String examItemName;
    /** 院内检查项目代码 */
    private String inhospExamItemCode;
    /** 院内检查项目名称 */
    private String inhospExamItemName;
    /** 检查类型: XRAY/CT/MRI/US/DSA/ENDO */
    private String examType;
    /** 医保检查类别代码 */
    private String examTypeCode;
    /** 医保检查类别名称(WS/T 102-1998) */
    private String examTypeName;
    /** 影像检查类型(医保字典: 1X线/2CT/3MRI/4US/5ECT) */
    private String imgExamType;
    /** DICOM Modality */
    private String modality;
    /** 检查部位(自由文本) */
    private String bodyPart;
    /** 部位编码(WS/T 364.8-2023) */
    private String bodyPartCode;
    /** 部位数 */
    private Integer siteCount;
    /** 造影方式: NONE/ORAL/IV/BOTH */
    private String contrastMode;
    /** 临床诊断 */
    private String clinicalDiagnosis;
    /** 检查目的 */
    private String examPurpose;
    /** 简要病史 */
    private String clinicalHistory;
    /** 是否急诊 */
    private Integer isUrgent;
    /** 是否隔离患者 */
    private Integer isIsolation;
    /** 过敏史(造影剂) */
    private String allergyInfo;
    /** 是否妊娠 */
    private Integer pregnantFlag;
    /** 申请医生ID */
    private Long applyDoctorId;
    /** 申请医生代码(医保4501.bilg_dr_codg) */
    private String applyDoctorCode;
    /** 申请医生姓名 */
    private String applyDoctorName;
    /** 申请科室ID */
    private Long applyDeptId;
    /** 申请科室代码(医保4501.appy_dept_code) */
    private String applyDeptCode;
    /** 申请科室名称 */
    private String applyDeptName;
    /** 申请机构名称(医共体) */
    private String applyOrgName;
    /** 申请时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime applyTime;
    /** 执行科室ID */
    private Long targetDeptId;
    /** 执行科室代码(医保4501.exam_dept_code) */
    private String targetDeptCode;
    /** 执行科室名称 */
    private String targetDeptName;
    /** 执行机构名称(医共体) */
    private String exeOrgName;
    /** 住院科室代码 */
    private String iptDeptCode;
    /** 住院科室名称 */
    private String iptDeptName;
    /** 预约检查时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime scheduledTime;
    /** 分配设备ID */
    private Long deviceId;
    /** 分配技师ID */
    private Long technicianId;
    /** 优先级(1最高-9最低) */
    private Integer priority;
    /** 状态: 0待预约/1已预约/2已登记/3检查中/4已完成/5已报告/6已审核/7已取消 */
    private Integer status;
    /** 取消原因 */
    private String cancelReason;
    /** 缴费标志: 0未缴费/1已缴费 */
    private Integer paidFlag;
    /** 检查费用 */
    private BigDecimal examCharge;
    /** 医保上报: 0未上报/1已上报/2失败 */
    private Integer ybUploadStatus;
    /** 医保上报时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime ybUploadTime;
    /** 有效标志(医保4501.vali_flag) */
    private String valiFlag;
    /** 申请备注 */
    private String notes;
}
