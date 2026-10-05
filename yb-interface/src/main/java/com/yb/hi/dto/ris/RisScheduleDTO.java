package com.yb.hi.dto.ris;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 检查排程请求(单时段保存/预约, 或按周模板批量生成排程号源)
 */
@Data
public class RisScheduleDTO {

    /** 设备ID(单时段保存/预约必填) */
    private Long deviceId;
    /** 排程日期(单时段保存/预约) */
    private LocalDate scheduleDate;
    /** 时段: 08:00-08:30 */
    private String timeSlot;
    /** 开始时间 */
    private LocalTime slotStart;
    /** 结束时间 */
    private LocalTime slotEnd;
    /** 时段时长(分钟) */
    private Integer slotDuration;
    /** 最大检查人数 */
    private Integer maxPatients;
    /** 关联申请单ID(预约占位时回填) */
    private Long requestId;
    /** 状态: 1可预约/2已满/3停诊/4临时加号 */
    private Integer status;
    /** 值班技师 */
    private Long technicianId;
    /** 周模板ID(批量生成时使用) */
    private Long tplId;
    /** 批量生成起始日期 */
    private LocalDate startDate;
    /** 批量生成截止日期 */
    private LocalDate endDate;
}
