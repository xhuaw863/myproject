package com.yb.hi.dto.community;

import lombok.Data;

import java.util.List;

/**
 * 机构目录选用请求(L3): 单条(catalogId)或批量(ids)启停某类目录在本机构的开展状态。
 */
@Data
public class OrgCatalogSaveReq {
    /** 目录类型: charge/drug/cons/usage/freq */
    private String catalogType;
    /** 单条目录ID */
    private Long catalogId;
    /** 批量目录ID */
    private List<Long> ids;
    /** 是否开展: 1启用 0停用 */
    private Integer enabled;
}
