package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 手术申请创建请求
 */
@Data
public class SurgeryDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 手术编码ICD-9-CM-3 */
    private String surgeryCode;
    /** 手术名称 */
    private String surgeryName;
    /** 手术级别: 1一级 2二级 3三级 4四级 */
    private Integer surgeryLevel;
    /** 主刀医师ID(his_staff.id) */
    private Long surgeonId;
    /** 一助ID(his_staff.id) */
    private Long firstAssistantId;
    /** 二助ID(his_staff.id) */
    private Long secondAssistantId;
    /** 麻醉医师ID(his_staff.id) */
    private Long anesthesiologistId;
    /** 麻醉护士ID(his_staff.id) */
    private Long anesthesiaNurseId;
    /** 器械护士ID(his_staff.id) */
    private Long instrumentNurseId;
    /** 巡回护士ID(his_staff.id) */
    private Long circulatingNurseId;
    /** 手术科室ID(his_dept.id) */
    private Long deptId;
    /** ASA分级: 1-5 */
    private Integer asaGrade;
    /** 切口类型: 1清洁 2清洁污染 3污染 4感染 */
    private Integer incisionType;
}
