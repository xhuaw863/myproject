package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.util.List;

/**
 * 创建出库单请求(草稿)
 */
@Data
public class StockOutReq {

    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 药库ID(his_warehouse_def.id, 空则不限库/默认库) */
    private Long warehouseId;
    /** 出库类型: 1处方发药 2报损 3盘亏 4调拨出 */
    private Integer outType;
    /** 关联单据ID */
    private Long refId;
    /** 关联单据号 */
    private String refNo;
    /** 备注 */
    private String remark;
    /** 出库明细 */
    private List<StockOutItemReq> items;
}
