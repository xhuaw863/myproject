package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalTime;

/**
 * 检查排程周模板(按周几定义设备排班段, 批量生成具体日期排程)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_exam_schedule_tpl")
public class HisExamScheduleTpl extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 设备ID */
    private Long deviceId;
    /** 周几(1-7) */
    private Integer dayOfWeek;
    /** 开始时间 */
    private LocalTime slotStart;
    /** 结束时间 */
    private LocalTime slotEnd;
    /** 时段时长(分钟) */
    private Integer slotDuration;
    /** 最大检查人数 */
    private Integer maxPatients;
    /** 默认技师 */
    private Long technicianId;
    /** 是否启用 */
    private Integer enabled;
}
