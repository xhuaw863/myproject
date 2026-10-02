package com.yb.hi.dto.inpatient;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 手术排程请求(手麻P0扩展: 申请安排/急诊直排/改排复用, 含团队与手术基本信息)
 */
@Data
public class SurgeryScheduleDTO {

    /** 手术日期 */
    private LocalDate scheduleDate;
    /** 手术时间段(HH:mm-HH:mm, 精确到分钟) */
    private String scheduleTime;
    /** 手术间号 */
    private String roomNo;
    /* ---------- 手麻P0扩展字段 ---------- */
    /** 就诊类型: 1住院 2门诊 3日间(急诊直排用) */
    private Integer visitType;
    /** 住院就诊ID(visit_type=1) */
    private Long inpVisitId;
    /** 门诊就诊ID(visit_type=2/3) */
    private Long visitId;
    /** 手术编码(急诊直排用) */
    private String surgeryCode;
    /** 手术名称(急诊直排用) */
    private String surgeryName;
    /** 手术级别(急诊直排用) */
    private Integer surgeryLevel;
    /** 手术时限: 1择期 2限期 3急诊 */
    private Integer deadlineType;
    /** 一体化模块: 1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗(缺省1) */
    private Integer moduleType;
    /** 拟麻醉方式 */
    private Integer anesthesiaType;
    /** 主刀医师ID */
    private Long surgeonId;
    /** 一助ID */
    private Long firstAssistantId;
    /** 二助ID */
    private Long secondAssistantId;
    /** 麻醉医师ID */
    private Long anesthesiologistId;
    /** 麻醉护士ID */
    private Long anesthesiaNurseId;
    /** 器械护士ID */
    private Long instrumentNurseId;
    /** 巡回护士ID */
    private Long circulatingNurseId;
    /** 患者期望时间(急诊直排留档) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expectTime;
    /** 术前诊断(急诊直排留档) */
    private String preOpDiag;
}
