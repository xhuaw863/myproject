package com.yb.hi.dto.doctor;

import lombok.Data;

/**
 * 开具住院证请求: 就诊ID + 拟收治科室 + 入院诊断/病情/目的 + 紧急程度
 * (患者/医师/时间/机构由服务从就诊记录自动补全)
 */
@Data
public class AdmissionCertReq {

    /** 就诊ID */
    private Long visitId;
    /** 拟收治科室ID */
    private Long admitDeptId;
    /** 拟收治科室名称 */
    private String admitDeptName;
    /** 入院诊断 */
    private String admitDiagnosis;
    /** 病情摘要 */
    private String conditionSummary;
    /** 入院目的 */
    private String admitPurpose;
    /** 紧急程度: 1-普通 2-急 3-危急 */
    private Integer urgency;
}
