package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 护理管道记录(P4c): 管道全生命周期(置管/巡视评估/更换/拔管), body_part_svg_data 承载人体图标注, risk_level 分级巡视
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_pipe")
public class HisNursingPipe extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 管道类型:central_venous/urinary/nasogastric/chest_tube/drain/tracheostomy/picc/other */
    private String pipeType;
    /** 管道名称 */
    private String pipeName;
    /** 置管时间 */
    private LocalDateTime insertTime;
    /** 置管部位 */
    private String insertSite;
    /** 人体图SVG标注数据 */
    private String bodyPartSvgData;
    /** 风险等级:1低 2中 3高 */
    private Integer riskLevel;
    /** 预计拔管日期 */
    private LocalDate expectedRemoveDate;
    /** 实际拔管时间 */
    private LocalDateTime actualRemoveTime;
    /** 状态:1在管 2已拔 3意外脱出 */
    private Integer status;
    /** 最后评估时间 */
    private LocalDateTime lastAssessTime;
    /** 最后更换时间 */
    private LocalDateTime lastReplaceTime;
    /** 备注 */
    private String note;
}
