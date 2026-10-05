package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 疾病报卡明细(P1 慢性病报告卡): 类型专有异构字段以 form_data(JSON) 承载,
 * 冗余少量需检索/上报的 typed 列(病种/ICD/危险等级/分期/部位)。与主表 1:1, 经 report_id 关联。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_disease_report_detail")
public class HisDiseaseReportDetail extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 报卡主表ID(his_disease_report.id) */
    private Long reportId;
    /** 该卡型完整键值表单(JSON 文本; 服务层序列化前端 form 得到) */
    private String formData;
    /** 病种编码(精障6病/ICD-O-3部位/ICD-10锚点) */
    private String diseaseCode;
    /** 病种名称 */
    private String diseaseName;
    /** ICD编码(肿瘤ICD-O-3 / 精障F码) */
    private String icdCode;
    /** 严重精神障碍危险等级:0-5 */
    private Integer riskLevel;
    /** 肿瘤临床分期(TNM/分期) */
    private String stage;
    /** 肿瘤部位(ICD-O-3解剖学部位名) */
    private String tumorSite;
}
