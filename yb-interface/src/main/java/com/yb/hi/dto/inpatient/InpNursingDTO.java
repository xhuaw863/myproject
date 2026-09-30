package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 住院护理记录请求
 */
@Data
public class InpNursingDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 记录类型: 1体温单 2护理评估 3护理计划 4护理措施 5护理总结 */
    private Integer recordType;
    /** 内容(JSON) */
    private String content;
}
