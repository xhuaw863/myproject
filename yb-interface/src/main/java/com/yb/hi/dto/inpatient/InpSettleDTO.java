package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 住院结算请求
 */
@Data
public class InpSettleDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 结算类型: 1出院结算 2中途结算 3退费 */
    private Integer settleType;
}
