package com.yb.hi.dto.community;

import lombok.Data;

/**
 * 修改医保对照生效时间请求(人工纠偏/补录历史生效时点)
 */
@Data
public class CatalogMapEffReq {

    /** 目录类型: charge/drug/cons */
    private String catalog;
    /** 院内条目ID */
    private Long itemId;
    /** 生效时间: yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd */
    private String effTime;
}
