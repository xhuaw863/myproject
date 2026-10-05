package com.yb.hi.dto.ris;

import lombok.Data;

/**
 * DICOM Worklist 条目创建/同步请求(申请单→Worklist 下发, 落 his_exam_worklist)
 */
@Data
public class RisWorklistDTO {

    /** 关联申请单ID(his_exam_request.id) */
    private Long requestId;
    /** 检查号(Accession Number, 空则服务端生成) */
    private String accessionNo;
    /** 患者ID(空则从申请单回填) */
    private Long patientId;
    /** DICOM Modality: CR/CT/MR/US/XA/ES */
    private String modality;
    /** 目标设备AE Title */
    private String deviceAeTitle;
    /** Scheduled Station AE */
    private String scheduledStation;
    /** DICOM检查日期(yyyyMMdd) */
    private String scheduledDate;
    /** DICOM检查时间(HHmmss) */
    private String scheduledTime;
    /** Study Instance UID(PACS 回传或服务端预生成) */
    private String studyUid;
    /** 检查部位 */
    private String bodyPart;
    /** 检查描述 */
    private String procedureDesc;
    /** 状态: 0待检查/1检查中/2已完成/3已取消 */
    private Integer status;
    /** MPPS状态(设备回传) */
    private String mppsStatus;
}
