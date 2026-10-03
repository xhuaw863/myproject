package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 护理临床事件手动登记请求(落 his_nursing_clinical_event; 入院/出院/转入转出等状态变更可由服务端自动触发)
 */
@Data
public class NursingClinicalEventDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id, 缺省从就诊主表回填) */
    private Long patientId;
    /** 事件类型:admission/discharge/death/surgery/transfer_in/transfer_out/delivery/resuscitation */
    private String eventType;
    /** 事件时间(缺省当前) */
    private LocalDateTime eventTime;
    /** 事件描述 */
    private String eventDesc;
    /** 触发源类型:order/visit_status/manual(手动登记固定 manual) */
    private String sourceType;
    /** 触发源ID */
    private Long sourceId;
}
