package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.time.LocalDate;

/**
 * 手术排程请求
 */
@Data
public class SurgeryScheduleDTO {

    /** 手术日期 */
    private LocalDate scheduleDate;
    /** 手术时间段 */
    private String scheduleTime;
    /** 手术间号 */
    private String roomNo;
}
