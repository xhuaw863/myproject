package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 住院病历书写请求
 */
@Data
public class InpMedRecordDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 记录类型: 1入院记录 2首次病程 3日常病程 4查房记录 5术前小结 6手术记录 7术后病程 8出院小结 9死亡记录 */
    private Integer recordType;
    /** 标题 */
    private String title;
    /** 内容(JSON) */
    private String content;
}
