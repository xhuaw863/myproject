package com.yb.hi.dto.warehouse;

import lombok.Data;

/**
 * 库房月结请求: 按 (机构, 药库, 期间, 记账标准) 汇总财务账+实物账。
 * acctStandard: 1进价 3零售价(批发价2留待后补)。
 */
@Data
public class MonthEndReq {

    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 药库ID(空=全院口径) */
    private Long warehouseId;
    /** 本期起始日(yyyy-MM-dd; 非首次须=上次月结终止+1) */
    private String periodStart;
    /** 本期终止日(yyyy-MM-dd) */
    private String periodEnd;
    /** 记账标准: 1进价 3零售价 */
    private Integer acctStandard;
    /** 备注 */
    private String remark;
}
