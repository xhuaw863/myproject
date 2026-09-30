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
 * 住院报表快照(床位/费用/科室/住院日/DRG五类报表按日落盘, data 为 JSON)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_report_snapshot")
public class HisInpReportSnapshot extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 报表类型: 1床位 2费用 3科室 4住院日 5DRG */
    private Integer reportType;
    /** 报表日期 */
    private LocalDate reportDate;
    /** 科室ID(his_dept.id) */
    private Long deptId;
    /** 病区ID(his_ward.id) */
    private Long wardId;
    /** 报表数据JSON */
    private String data;
    /** 生成时间 */
    private LocalDateTime generatedTime;
}
