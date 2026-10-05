package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 检查排程(设备×日期×时段号源簿, booked_count 预约占位)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_exam_schedule")
public class HisExamSchedule extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 设备ID */
    private Long deviceId;
    /** 排程日期 */
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
    /** 已预约数 */
    private Integer bookedCount;
    /** 关联申请单ID */
    private Long requestId;
    /** 状态: 1可预约/2已满/3停诊/4临时加号 */
    private Integer status;
    /** 值班技师 */
    private Long technicianId;
}
