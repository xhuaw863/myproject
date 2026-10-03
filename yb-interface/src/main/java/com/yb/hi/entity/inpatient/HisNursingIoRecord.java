package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 护理出入量记录(逐条出入量: 输液/口服/鼻饲等入量与尿量/引流/胃肠减压/呕吐/大便等出量, 供24小时出入量汇总与体温单直读)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_io_record")
public class HisNursingIoRecord extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 记录时间 */
    private LocalDateTime recordTime;
    /** 类型:1入量 2出量 */
    private Integer ioType;
    /** 项目名称 */
    private String itemName;
    /** 项目分类:infusion输液/oral口服/urine尿量/drain引流/gastric胃肠减压/vomit呕吐/stool大便/blood失血/other其他 */
    private String itemCategory;
    /** 量(ml) */
    private Integer volumeMl;
    /** 途径 */
    private String route;
    /** 备注 */
    private String note;
}
