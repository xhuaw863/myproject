package com.yb.hi.dto.ris;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RIS 远程会诊/双阅请求(发起与答复共用, 落 his_ris_consult)
 */
@Data
public class RisConsultDTO {

    /** 原报告ID(his_exam_report.id) */
    private Long reportId;
    /** 发起医生ID(缺省当前登录) */
    private Long requestDoctorId;
    /** 会诊原因 */
    private String requestReason;
    /** 会诊类型: 1双阅/2远程会诊/3科内讨论 */
    private Integer consultType;
    /** 会诊医生ID(答复时回填) */
    private Long consultDoctorId;
    /** 会诊机构ID(远程会诊) */
    private Long consultOrgId;
    /** 会诊意见 */
    private String consultOpinion;
    /** 会诊时间 */
    private LocalDateTime consultTime;
    /** 同意标志: 0不同意/1同意(答复时回填) */
    private Integer agreeFlag;
    /** 状态: 0待会诊/1已完成/2已取消 */
    private Integer status;
}
