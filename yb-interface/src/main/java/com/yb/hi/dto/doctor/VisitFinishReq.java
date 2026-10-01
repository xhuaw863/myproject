package com.yb.hi.dto.doctor;

import com.yb.hi.entity.doctor.HisDiagnosis;
import lombok.Data;

import java.util.List;

/**
 * 完成接诊请求: 病历字段(SOAP来源) + 诊断列表 + 是否上传医保2203
 */
@Data
public class VisitFinishReq {

    /** 就诊ID */
    private Long visitId;
    /** 主诉 */
    private String chiefComplaint;
    /** 现病史 */
    private String presentIllness;
    /** 既往史 */
    private String pastHistory;
    /** 过敏史 */
    private String allergyHistory;
    /** 体格检查 */
    private String physicalExam;
    /** 辅助检查 */
    private String auxExam;
    /** 处理意见 */
    private String treatmentOpinion;
    /** 病种类型代码(2203 mdtrtinfo.dise_type_code) */
    private String diseTypeCode;
    /** 计划生育手术类别(2203 mdtrtinfo.birctrl_type) */
    private String birctrlType;
    /** 计划生育手术或生育日期 yyyy-MM-dd(2203 mdtrtinfo.birctrl_matn_date) */
    private String birctrlMatnDate;
    /** 诊断列表 */
    private List<HisDiagnosis> diagnoses;
    /** 是否上传医保就诊信息(2203), 默认true */
    private Boolean uploadYb;

    /* ===== 方案 B 收敛: 结构化病历正文(单一真源), 完成时据此派生 S/O/A/P 与医保主诉 ===== */
    /** 结构化病历 JSON(fieldKey→值); 非空表示本次以结构化正文完成, 优先于上方 SOAP 文本列 */
    private String structure;
    /** 结构化病历模板ID(scope=2) */
    private Long emrTemplateId;

    /* ===== OP-A 诊后去向 ===== */
    /** 诊后去向: 1-离院 2-转科 3-转留观 4-转院 */
    private Integer disposition;
    /** 转科目标科室ID */
    private Long dispositionDeptId;
    /** 去向备注 */
    private String dispositionNote;
}
