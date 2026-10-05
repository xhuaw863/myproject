package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

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
    /** 报卡类型: 1法定传染病 2慢性病 3其他(旧口径, 保留兼容) */
    private Integer reportType;
    /** 报卡大类: 1传染病 2严重精神障碍 3恶性肿瘤 4高血压 5糖尿病 9其他(NULL回退reportType语义) */
    private Integer reportCategory;
    /** 卡片编号(国标口径) */
    private String cardNo;
    /** 报卡类别: 1初次报告 2订正报告 */
    private Integer reportForm;
    /** 订正报告指向被订正原卡 cardNo */
    private String correctPrevNo;
    /** 退卡原因 */
    private String returnReason;
    /** 发病/首次症状时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime onsetDate;
    /** 诊断时间(肿瘤/精障到小时) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime diagTime;
    /** 死亡日期(如适用) */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate deathDate;
    /** 患者区是否由档案自动带出:1是 0否 */
    private Integer autoFilled;
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
    /** 患者姓名(create从自动带出冗余落列, 供审核列表检索展示) */
    private String patientName;
    /** 患者有效证件号(create从自动带出冗余落列, 供审核列表检索展示) */
    private String patientIdcard;

    /** 报卡明细(类型专有字段, 落 his_disease_report_detail; 非表列) */
    @TableField(exist = false)
    private HisDiseaseReportDetail detail;
    /** 前端提交的键值表单(服务层序列化进 detail.formData; 非表列) */
    @TableField(exist = false)
    private Map<String, Object> form;
}
