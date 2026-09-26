package com.yb.hi.dto.doctor;

import lombok.Data;

/**
 * 发起会诊请求: 就诊ID + 受邀会诊科室 + 会诊目的/病情摘要 + 紧急程度 + 期望时间
 * (患者/申请科室/申请医师/机构由服务从就诊记录自动补全)
 */
@Data
public class ConsultReq {

    /** 就诊ID */
    private Long visitId;
    /** 受邀会诊科室ID */
    private Long consultDeptId;
    /** 受邀会诊科室名称 */
    private String consultDeptName;
    /** 会诊目的 */
    private String consultPurpose;
    /** 病情摘要 */
    private String conditionSummary;
    /** 紧急程度: 1-普通 2-急 3-紧急 */
    private Integer urgency;
    /** 期望会诊时间(兼容 yyyy-MM-dd HH:mm:ss / ISO yyyy-MM-ddTHH:mm:ss / 仅日期) */
    private String expectedTime;
}
