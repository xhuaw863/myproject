package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 护理告知书/同意书(P4c): 告知内容 + 患者/家属手写签名图片(base64) + 签名人关系与见证人留痕, 三态(待签/已签/已撤回)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_consent")
public class HisNursingConsent extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 告知类型:admission/surgery/anesthesia/blood/special_drug/invasive/fall_risk/other */
    private String consentType;
    /** 告知书名称 */
    private String consentName;
    /** 告知内容 */
    private String content;
    /** 患者签名图片 */
    private String patientSignatureBase64;
    /** 家属签名图片 */
    private String familySignatureBase64;
    /** 签名人姓名 */
    private String signerName;
    /** 与患者关系 */
    private String signerRelation;
    /** 签名时间 */
    private LocalDateTime signTime;
    /** 见证人 */
    private String witnessName;
    /** 0待签 1已签 2已撤回 */
    private Integer status;
}
