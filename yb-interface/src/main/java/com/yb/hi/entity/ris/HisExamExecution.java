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
 * 检查执行记录(技师操作/曝光/图像数/造影剂/剂量: CT 的 DLP/CTDIvol, DR/DSA 的 DAP)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_exam_execution")
public class HisExamExecution extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 关联申请单 */
    private Long requestId;
    /** 关联Worklist */
    private Long worklistId;
    /** 检查号 */
    private String accessionNo;
    /** 实际使用设备 */
    private Long deviceId;
    /** 操作技师ID */
    private Long technicianId;
    /** 技师姓名 */
    private String technicianName;
    /** 到检登记时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime checkInTime;
    /** 检查开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime examStartTime;
    /** 检查完成时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
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
