package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 药品养护明细行请求(手动建单逐行录入; 自动/模板建单由服务端生成后可再编辑)。
 */
@Data
public class MaintenanceItemReq {

    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码 */
    private String drugCode;
    /** 药品名称 */
    private String drugName;
    /** 规格 */
    private String spec;
    /** 批次号 */
    private String batchNo;
    /** 生产厂家 */
    private String manufacturer;
    /** 剂型 */
    private String dosform;
    /** 储存条件 */
    private String storageCond;
    /** 在库数量 */
    private BigDecimal qty;
    /** 有效期(yyyy-MM-dd) */
    private String expDate;
    /** 养护措施 */
    private String measure;
    /** 养护结果: 1合格 2异常 */
    private Integer result;
    /** 养护人 */
    private String handler;
    /** 结论/异常描述 */
    private String conclusion;
    /** 备注 */
    private String remark;
}
