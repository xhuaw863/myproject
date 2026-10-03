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
    /** 内容(JSON; 富文本轨为 Tiptap 文档明文, 服务端加密落库, P4a-5) */
    private String content;
    /** 护理文书模板ID(his_nursing_template.id, 富文本轨 P4a-5) */
    private Long templateId;
    /** 结构化字段扁平JSON(fieldKey→值, 富文本轨; 缺省由 content 服务端派生) */
    private String structure;
}
