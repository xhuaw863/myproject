package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病历可靠电子签名(Phase D, SM2): 对病历 content+structure 规范化摘要做 SM2 签名并落库,
 * 支撑住院三级(住院医师/主治/主任)与门诊(医师)签名链、验签可对抗篡改、第三方 CA/TSA 预留。
 *
 * 归属: scope 1住院(定位 record_id, 附 visit_id) / 2门诊(定位 visit_id)。重签同环节旧行 valid 置 0。
 * digest=SM3(content+structure) hex; sig_value=SM2 对 digest 的签名(hex); cert_sn 取机构 sys_org.sign_no 占位。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_signature")
public class HisEmrSignature extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 适用范围: 1住院 2门诊 */
    private Integer scope;
    /** 住院病历ID(his_inp_medical_record.id; 门诊为空) */
    private Long recordId;
    /** 就诊ID(住院 his_inp_visit.id / 门诊 his_visit.id) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 签名环节: author/resident/attending/director(住院) doctor(门诊) */
    private String stage;
    /** 签名人(his_staff.id) */
    private Long signerId;
    /** 签名人姓名 */
    private String signerName;
    /** SM3摘要hex(content+structure规范化) */
    private String digest;
    /** SM2签名值(hex) */
    private String sigValue;
    /** 证书/签章编号(sys_org.sign_no 占位) */
    private String certSn;
    /** 签名提供者标识: sm2(内置国密)/ca/tsa */
    private String provider;
    /** 签名图URL(his_staff.sign_img_url 或手写签名落盘URL) */
    private String signImg;
    /** 签名时间 */
    private LocalDateTime signTime;
    /** 是否当前有效: 1有效 0已被重签取代 */
    private Integer valid;
}
