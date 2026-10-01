package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 疾病报卡(法定传染病/慢病等): 下达诊断时提示报卡, 与 his_diagnosis 关联留痕
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_disease_report")
public class HisDiseaseReport extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID(his_visit.id) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 诊断代码 */
    private String diagCode;
    /** 诊断名称 */
    private String diagName;
    /** 报卡类型: 1法定传染病 2慢性病 3其他 */
    private Integer reportType;
    /** 报卡编号 */
    private String reportNo;
    /** 报卡状态: 0待报 1已报 2已审核 */
    private Integer reportStatus;
    /** 报卡内容摘要 */
    private String reportContent;
    /** 报告时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reportTime;
    /** 报告人姓名 */
    private String reporter;
}
