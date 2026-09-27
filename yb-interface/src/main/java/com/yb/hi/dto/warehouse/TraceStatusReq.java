package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.util.List;

/** 追溯码状态流转请求(退货/报废·调拨在途/回库): 按追溯码清单批量改状态 */
@Data
public class TraceStatusReq {

    /** 追溯码清单 */
    private List<String> traceCodes;
    /** 目标状态: 0回库 2已退货 3已报废/调拨在途 */
    private Integer status;
    /** 关联单据类型(return/transfer/scrap 等) */
    private String refBillType;
    /** 关联单据ID */
    private Long refBillId;
}
