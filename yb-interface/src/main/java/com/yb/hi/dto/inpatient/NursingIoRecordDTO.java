package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 护理出入量录入请求(落 his_nursing_io_record 逐条入量/出量行, 供 24 小时出入量汇总与体温单直读)
 */
@Data
public class NursingIoRecordDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id, 缺省从就诊主表回填) */
    private Long patientId;
    /** 记录时间(缺省当前) */
    private LocalDateTime recordTime;
    /** 类型:1入量 2出量 */
    private Integer ioType;
    /** 项目名称(如 静脉输液/口服温水/尿量/胃肠减压引流) */
    private String itemName;
    /** 项目分类:infusion输液/oral口服/urine尿量/drain引流/gastric胃肠减压/vomit呕吐/stool大便/blood失血/other其他 */
    private String itemCategory;
    /** 量(ml) */
    private Integer volumeMl;
    /** 途径(如 静脉/口服/鼻饲/导尿) */
    private String route;
    /** 备注 */
    private String note;
}
