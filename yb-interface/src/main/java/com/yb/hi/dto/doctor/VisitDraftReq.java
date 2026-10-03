package com.yb.hi.dto.doctor;

import lombok.Data;

/**
 * 病历草稿保存请求: 接诊过程中暂存病历字段与医保扩展字段, 不改变就诊状态
 */
@Data
public class VisitDraftReq {

    /** 就诊ID */
    private Long visitId;
    /* ---------- 病历字段 ---------- */
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
    /* ---------- 结构化病历(Phase B 并入 EMR 引擎, 可空则保持 SOAP 纯文本模式) ---------- */
    /** 结构化病历内容 JSON(his_emr_template scope=2 fields 取值, 按 fieldKey 存值) */
    private String structure;
    /** 结构化病历模板ID(his_emr_template.id) */
    private Long emrTemplateId;
    /** Tiptap JSON 文档(明文, 由服务端 AES-GCM 加密落 his_visit.content); 非空且为 doc 文档时启用双轨, structure 仍由服务端派生维护 */
    private String content;
    /* ---------- 医保字段(2203 mdtrtinfo) ---------- */
    /** 病种类型代码 */
    private String diseTypeCode;
    /** 计划生育手术类别 */
    private String birctrlType;
    /** 计划生育手术或生育日期 yyyy-MM-dd */
    private String birctrlMatnDate;
    /* ---------- 复诊预约(随访) ---------- */
    /** 复诊日期 yyyy-MM-dd */
    private String followupDate;
    /** 复诊备注 */
    private String followupNote;
}
