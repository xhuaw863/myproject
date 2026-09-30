package com.yb.hi.dto.inpatient;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 住院报表查询条件(床位/费用/科室/住院日/DRG五类报表通用)
 */
@Data
public class ReportQueryDTO {

    /** 报表类型: 1床位 2费用 3科室 4住院日 5DRG */
    private Integer reportType;
    /** 开始日期 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;
    /** 结束日期 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
    /** 科室ID(his_dept.id) */
    private Long deptId;
    /** 病区ID(his_ward.id) */
    private Long wardId;
}
