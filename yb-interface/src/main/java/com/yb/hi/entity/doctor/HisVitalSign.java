package com.yb.hi.entity.doctor;

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
 * 生命体征(支持设备采集/手工补录; 血糖/血酮作趋势图数据源)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_vital_sign")
public class HisVitalSign extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID(his_visit.id) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 收缩压(mmHg) */
    private Integer systolic;
    /** 舒张压(mmHg) */
    private Integer diastolic;
    /** 脉搏(次/分) */
    private Integer pulse;
    /** 体温(℃) */
    private BigDecimal temperature;
    /** 呼吸(次/分) */
    private Integer respiration;
    /** 血糖(mmol/L) */
    private BigDecimal bloodGlucose;
    /** 血酮(mmol/L) */
    private BigDecimal bloodKetone;
    /** 体重(kg) */
    private BigDecimal weight;
    /** 身高(cm) */
    private BigDecimal height;
    /** 来源: 设备/手工 */
    private String source;
    /** 测量时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime measTime;
    /** 录入人ID */
    private Long recorderId;
}
