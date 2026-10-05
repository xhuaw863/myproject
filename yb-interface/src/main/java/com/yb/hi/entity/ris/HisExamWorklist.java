package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * DICOM Worklist条目(Accession/StudyInstanceUID/MPPS 与影像设备对接)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_exam_worklist")
public class HisExamWorklist extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 关联申请单 */
    private Long requestId;
    /** 检查号(Accession Number) */
    private String accessionNo;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 患者ID号 */
    private String patientIdNo;
    /** DICOM性别: M/F/O */
    private String gender;
    /** DICOM日期YYYYMMDD */
    private String birthDate;
    /** DICOM Modality */
    private String modality;
    /** 目标设备AE Title */
    private String deviceAeTitle;
    /** Scheduled Station AE */
    private String scheduledStation;
    /** DICOM日期 */
    private String scheduledDate;
    /** DICOM时间 */
    private String scheduledTime;
    /** Study Instance UID */
    private String studyUid;
    /** 检查部位 */
    private String bodyPart;
    /** 检查描述 */
    private String procedureDesc;
    /** 申请医生 */
    private String referringPhysician;
    /** 申请科室 */
    private String requestingDept;
    /** 状态: 0待检查/1检查中/2已完成/3已取消 */
    private Integer status;
    /** MPPS状态 */
    private String mppsStatus;
}
