package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.util.List;

/**
 * 建养护单请求。mntType: 1手动(items 逐行) 2自动(按 warehouseId/dosform/drugKeyword 从在库库存生行) 3模板(templateId 载入筛选条件生行)。
 */
@Data
public class MaintenanceCreateReq {

    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 药库ID */
    private Long warehouseId;
    /** 养护日期(yyyy-MM-dd) */
    private String mntDate;
    /** 建单方式: 1手动 2自动 3模板 */
    private Integer mntType;
    /** 来源模板ID(mntType=3) */
    private Long templateId;
    /** 自动/模板筛选: 剂型 */
    private String dosform;
    /** 自动/模板筛选: 储存条件 */
    private String storageCond;
    /** 自动/模板筛选: 药品名称/编码关键字 */
    private String drugKeyword;
    /** 备注 */
    private String remark;
    /** 手动明细(mntType=1) */
    private List<MaintenanceItemReq> items;
}
