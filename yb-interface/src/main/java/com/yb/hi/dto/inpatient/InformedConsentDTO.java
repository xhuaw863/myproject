package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 住院知情同意书请求
 */
@Data
public class InformedConsentDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 同意书类型: 1手术 2麻醉 3输血 4特殊检查 5特殊治疗 6自费 7病危 */
    private Integer consentType;
    /** 同意书标题 */
    private String title;
    /** 打印模板ID(his_print_template.id) */
    private Long templateId;
    /** 同意书内容JSON */
    private String content;
    /** 谈话医师ID(his_staff.id) */
    private Long doctorId;
    /** 状态: 1待签 2已签 3已撤销 */
    private Integer status;
}
