package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.time.LocalDate;

/**
 * 住院费用查询请求
 */
@Data
public class InpChargeQueryDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 记账日期起 */
    private LocalDate startDate;
    /** 记账日期止 */
    private LocalDate endDate;
    /** 费用类别: 1西药 2中药 3检查 4检验 5治疗 6护理 7材料 8床位 9其他 */
    private Integer feeType;
    /** 页码(从1起) */
    private Integer page;
    /** 每页行数(默认20) */
    private Integer size;
}
