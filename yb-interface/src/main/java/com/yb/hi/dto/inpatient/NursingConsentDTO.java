package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 护理告知书/同意书创建请求(落 his_nursing_consent, 创建后 status=0 待签, 签名/撤回走独立端点)
 */
@Data
public class NursingConsentDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id, 缺省从就诊主表回填) */
    private Long patientId;
    /** 告知类型:admission/surgery/anesthesia/blood/special_drug/invasive/fall_risk/other */
    private String consentType;
    /** 告知书名称 */
    private String consentName;
    /** 告知内容 */
    private String content;
    /** 见证人 */
    private String witnessName;
}
