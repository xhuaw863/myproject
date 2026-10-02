package com.yb.hi.dto.inpatient;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 手麻报表查询条件(P2a): 六类统计通用, 日期区间缺省最近30天。
 */
@Data
public class SurgeryReportQueryDTO {

    /** 开始日期(按手术排期日 schedule_date 过滤) */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;
    /** 结束日期 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
    /** 科室ID(his_dept.id) */
    private Long deptId;
    /** 主刀医师ID(his_staff.id) */
    private Long surgeonId;
    /** 一体化模块: 1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗 */
    private Integer moduleType;
}
