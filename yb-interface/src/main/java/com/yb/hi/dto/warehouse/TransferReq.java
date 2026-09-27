package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 创建库存调拨单请求(指定批次随行调拨) */
@Data
public class TransferReq {

    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 调出库位ID(药库或药房库存位) */
    private Long fromLocationId;
    /** 调入库位ID */
    private Long toLocationId;
    /** 是否直接提交待调出(true=待调出确认, false=草稿) */
    private boolean submit;
    /** 备注 */
    private String remark;
    /** 调拨明细 */
    private List<TransferItemReq> items;

    /** 调拨明细行 */
    @Data
    public static class TransferItemReq {
        /** 药品目录ID */
        private Long drugCatalogId;
        /** 药品编码 */
        private String drugCode;
        /** 药品名称 */
        private String drugName;
        /** 规格 */
        private String spec;
        /** 批次号(指定批次随行调拨) */
        private String batchNo;
        /** 调拨数量(>0) */
        private BigDecimal qty;
    }
}
