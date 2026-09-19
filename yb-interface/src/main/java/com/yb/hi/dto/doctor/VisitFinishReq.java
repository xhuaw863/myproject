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
    /** 体格检查 */
    private String physicalExam;
    /** 处理意见 */
    private String treatmentOpinion;
    /** 诊断列表 */
    private List<HisDiagnosis> diagnoses;
    /** 是否上传医保就诊信息(2203), 默认true */
    private Boolean uploadYb;
}
