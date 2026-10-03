package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 护理管道置管请求(落 his_nursing_pipe, 置管后 status=1 在管, 拔管/脱出走独立端点)
 */
@Data
public class NursingPipeDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id, 缺省从就诊主表回填) */
    private Long patientId;
    /** 管道类型:central_venous/urinary/nasogastric/chest_tube/drain/tracheostomy/picc/other */
    private String pipeType;
    /** 管道名称 */
    private String pipeName;
    /** 置管时间(缺省当前) */
    private LocalDateTime insertTime;
    /** 置管部位 */
    private String insertSite;
    /** 人体图SVG标注数据 */
    private String bodyPartSvgData;
    /** 风险等级:1低 2中 3高 */
    private Integer riskLevel;
    /** 预计拔管日期 */
    private LocalDate expectedRemoveDate;
    /** 备注 */
    private String note;
}
