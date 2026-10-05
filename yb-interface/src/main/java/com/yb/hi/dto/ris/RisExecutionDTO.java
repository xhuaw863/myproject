package com.yb.hi.dto.ris;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检查执行记录请求(技师工作站到检/开始/完成登记, 落 his_exam_execution)
 */
@Data
public class RisExecutionDTO {

    /** 关联申请单ID */
    private Long requestId;
    /** 关联Worklist ID */
    private Long worklistId;
    /** 检查号(空则从Worklist/申请单回填) */
    private String accessionNo;
    /** 实际使用设备ID */
    private Long deviceId;
    /** 操作技师ID(缺省当前登录) */
    private Long technicianId;
    /** 技师姓名 */
    private String technicianName;
    /** 到检登记时间 */
    private LocalDateTime checkInTime;
    /** 检查开始时间 */
    private LocalDateTime examStartTime;
    /** 检查完成时间 */
    private LocalDateTime examEndTime;
    /** 曝光次数 */
    private Integer exposureCount;
    /** 图像数量 */
    private Integer imageCount;
    /** 造影剂名称 */
    private String contrastAgent;
    /** 造影剂剂量 */
    private String contrastDose;
    /** 给药途径 */
    private String contrastRoute;
    /** CT剂量DLP(mGy*cm) */
    private BigDecimal doseDlp;
    /** CT剂量CTDIvol(mGy) */
    private BigDecimal doseCtdi;
    /** DR/DSA剂量DAP(Gy*cm2) */
    private BigDecimal doseDap;
    /** 管电压 */
    private String kvp;
    /** 毫安秒 */
    private String mas;
    /** 体位 */
    private String patientPosition;
    /** DICOM Study Instance UID */
    private String studyUid;
    /** Series数量 */
    private Integer seriesCount;
    /** 检查备注(技师) */
    private String notes;
    /** 状态: 0未开始/1检查中/2已完成/3中断 */
    private Integer status;
}
