package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 护理生命体征记录(结构化单次测量: 体温/脉搏/血压/血氧/血糖/疼痛/GCS/MEWS预警分级/出入量, 供体温单趋势图与预警看板直读)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_vital_sign")
public class HisNursingVitalSign extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 测量时间 */
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
    /** 意识状态 */
    private String consciousness;
    /** 体重(kg) */
    private BigDecimal weight;
    /** 身高(cm) */
    private BigDecimal height;
    /** GCS评分(3-15) */
    private Integer gcsScore;
    /** MEWS评分 */
    private Integer mewsScore;
    /** MEWS预警:0绿 1黄 2橙 3红 */
    private Integer mewsLevel;
    /** 大便次数 */
    private Integer stoolCount;
    /** 尿量(ml) */
    private Integer urineMl;
    /** 引流量(ml) */
    private Integer drainMl;
    /** 备注 */
    private String note;
}
