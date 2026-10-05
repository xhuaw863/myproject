package com.yb.hi.dto.ris;

import lombok.Data;

/**
 * 检查申请单创建/保存请求(门诊/住院医生站开单载荷, 落 his_exam_request)
 */
@Data
public class RisRequestDTO {

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
    /** 就医流水号(医保4501.mdtrt_sn, 缺省从就诊回填) */
    private String mdtrtSn;
    /** 医保就诊ID(医保4501.mdtrt_id, 缺省从就诊回填) */
    private String mdtrtId;
    /** 医保人员编号(医保4501.psn_no, 缺省从患者回填) */
    private String psnNo;
    /** 收费项目ID(his_charge_item.id) */
    private Long chargeItemId;
    /** 收费项目编码(院内, 缺省从收费项目回填) */
    private String chargeItemCode;
    /** 收费项目名称(院内, 缺省从收费项目回填) */
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
    /** 申请医生ID(his_staff.id, 缺省当前登录) */
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
    /** 执行科室ID(his_dept.id) */
    private Long targetDeptId;
    /** 执行科室代码(医保4501.exam_dept_code) */
    private String targetDeptCode;
    /** 执行科室名称 */
    private String targetDeptName;
    /** 优先级(1最高-9最低) */
    private Integer priority;
    /** 申请备注 */
    private String notes;
}
