package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 病区交接班请求
 */
@Data
public class InpShiftDTO {

    /** 病区ID(his_ward.id) */
    private Long wardId;
    /** 班次: 1白班 2小夜 3大夜 */
    private Integer shiftType;
    /** 交班内容(JSON) */
    private String content;
    /** SBAR-情景(Situation): 患者当前状况(主诉/诊断/病情变化) */
    private String sbarSituation;
    /** SBAR-背景(Background): 病史摘要/近期治疗经过 */
    private String sbarBackground;
    /** SBAR-评估(Assessment): 生命体征/护理评估结论 */
    private String sbarAssessment;
    /** SBAR-建议(Recommendation): 关注要点/护理重点/注意事项 */
    private String sbarRecommendation;
}
