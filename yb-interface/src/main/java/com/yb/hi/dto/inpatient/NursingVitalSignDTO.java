package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 护理生命体征录入请求(落 his_nursing_vital_sign 结构化列, 服务端自动计算 MEWS 评分与异常预警)
 */
@Data
public class NursingVitalSignDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id, 缺省从就诊主表回填) */
    private Long patientId;
    /** 测量时间(缺省当前) */
    private LocalDateTime recordTime;
    /** 体温(℃) */
    private BigDecimal temperature;
    /** 体温类型:1口温 2腋温 3肛温 4耳温 */
    private Integer tempType;
    /** 脉搏(次/分) */
    private Integer pulse;
    /** 心率(次/分) */
    private Integer heartRate;
    /** 呼吸(次/分) */
    private Integer respiration;
    /** 收缩压(mmHg) */
    private Integer systolicBp;
    /** 舒张压(mmHg) */
    private Integer diastolicBp;
    /** 血氧饱和度(%) */
    private Integer spo2;
    /** 血糖(mmol/L) */
    private BigDecimal bloodGlucose;
    /** 疼痛评分(0-10) */
    private Integer painScore;
    /** 意识状态(alert/voice/pain/unresponsive 或中文: 清醒/嗜睡/模糊/昏迷) */
    private String consciousness;
    /** 体重(kg) */
    private BigDecimal weight;
    /** 身高(cm) */
    private BigDecimal height;
    /** GCS评分(3-15) */
    private Integer gcsScore;
    /** 大便次数 */
    private Integer stoolCount;
    /** 尿量(ml) */
    private Integer urineMl;
    /** 引流量(ml) */
    private Integer drainMl;
    /** 备注 */
    private String note;
}
