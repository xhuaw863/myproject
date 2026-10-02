package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;

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
    /** 一体化模块: 1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗(缺省1) */
    private Integer moduleType;
    /* ---------- 手麻P4b: 一体化专属字段(module_type 2/3/4 采集, 旁挂 his_surgery_module_ext) ---------- */
    /** DSA: 设备/机房 */
    private String dsaEquipment;
    /** DSA: 对比剂 */
    private String dsaContrast;
    /** DSA: 辐射剂量 */
    private BigDecimal dsaRadiationDose;
    /** 内镜: 镜种 */
    private String endoScopeType;
    /** 内镜: 活检数 */
    private Integer endoBiopsyCnt;
    /** 产科: 孕周 */
    private String obstGestationalWeek;
    /** 产科: 分娩方式 */
    private Integer obstBirthType;
}
