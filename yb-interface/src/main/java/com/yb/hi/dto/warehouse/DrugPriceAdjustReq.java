package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 药品调价单请求: 预览/建单入参(明细=待调价药品+新进/零售价); 生效按已存草稿单执行 */
@Data
public class DrugPriceAdjustReq {

    /** 机构ID(空=全医共体调价) */
    private Long orgId;
    /** 范围: ALL/DRUG(默认 DRUG) */
    private String scope;
    /** 调价域: CATALOG/WAREHOUSE/PHARMACY/ALL(默认 ALL=向后兼容) */
    private String priceDomain;
    /** 药库域目标库位(priceDomain=WAREHOUSE 必填) */
    private Long targetWarehouseId;
    /** 药房域目标药房(priceDomain=PHARMACY 必填) */
    private Long targetPharmacyId;
    /** 是否到生效日自动生效: 1/0(默认 0=仅手动) */
    private Integer autoEffect;
    /** 生效日期 */
    private LocalDate effectiveDate;
    /** 调价原因 */
    private String reason;
    /** 备注 */
    private String remark;
    /** 调价明细 */
    private List<Item> items;

    /** 单条调价明细: 指定药品目录 + 新进/零售价(为空表示该字段不调) */
    @Data
    public static class Item {
        private Long drugCatalogId;
        private BigDecimal newPurchase;
        private BigDecimal newRetail;
    }
}
