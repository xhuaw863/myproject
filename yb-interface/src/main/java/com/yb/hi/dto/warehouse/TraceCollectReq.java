package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.util.List;

/** 追溯码采集请求(入库/在库录入): 指定库位+药品+批次, 录入扫描到的追溯码清单(或按 qty 自动生成占位码) */
@Data
public class TraceCollectReq {

    /** 机构ID(空取当前登录机构) */
    private Long orgId;
    /** 库位ID(药库或药房库存位 his_warehouse_def.id) */
    private Long locationId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 批次号 */
    private String batchNo;
    /** 关联入库单ID(可空) */
    private Long stockInId;
    /** 扫描录入的追溯码清单(优先使用) */
    private List<String> codes;
    /** codes 为空时: 自动生成占位码的数量(便于无扫码器时批量建码) */
    private Integer autoGenerateQty;
}
