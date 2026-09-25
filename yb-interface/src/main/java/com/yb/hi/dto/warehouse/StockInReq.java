package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.util.List;

/**
 * 创建入库单请求(草稿)
 */
@Data
public class StockInReq {

    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 入库类型: 1采购 2退药回库 3盘盈 4调拨入 */
    private Integer inType;
    /** 供应商 */
    private String supplier;
    /** 供应商联系方式 */
    private String supplierContact;
    /** 备注 */
    private String remark;
    /** 入库明细 */
    private List<StockInItemReq> items;
}
