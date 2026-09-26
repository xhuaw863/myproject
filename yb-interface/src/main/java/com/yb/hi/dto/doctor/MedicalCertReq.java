package com.yb.hi.dto.doctor;

import lombok.Data;

/**
 * 开具诊断证明请求: 就诊ID + 证明类型 + 诊断/内容 + 病假天数
 * (患者/医师/时间/机构由服务从就诊记录自动补全)
 */
@Data
public class MedicalCertReq {

    /** 就诊ID */
    private Long visitId;
    /** 证明类型: 1-诊断证明 2-病假条 3-转诊证明 */
    private Integer certType;
    /** 诊断(为空时取就诊已有诊断) */
    private String diagnosis;
    /** 证明内容 */
    private String certContent;
    /** 建议病假天数 */
    private Integer sickLeaveDays;
    /** 备注 */
    private String remark;
}
