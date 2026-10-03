package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 护理交班报告(P4c): 病区×班次(day/evening/night)×日期一份, content JSON 承载交班正文, 危重/入院/转科/手术/在科人数随报告固化
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_shift_report")
public class HisNursingShiftReport extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 病区ID */
    private Long wardId;
    /** 班次:day/evening/night */
    private String shiftType;
    /** 交班日期 */
    private LocalDate shiftDate;
    /** 报告内容JSON */
    private String content;
    /** 危重人数 */
    private Integer criticalCount;
    /** 新入院人数 */
    private Integer newAdmitCount;
    /** 转入人数 */
    private Integer transferInCount;
    /** 转出人数 */
    private Integer transferOutCount;
    /** 手术人数 */
    private Integer surgeryCount;
    /** 在科总人数 */
    private Integer totalPatients;
    /** 交班人ID */
    private Long reporterId;
    /** 交班人 */
    private String reporterName;
    /** 接班人ID */
    private Long receiverId;
    /** 接班人 */
    private String receiverName;
    /** 0草稿 1已交班 2已接班 */
    private Integer status;
}
