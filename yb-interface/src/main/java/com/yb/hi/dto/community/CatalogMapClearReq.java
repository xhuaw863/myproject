package com.yb.hi.dto.community;

import lombok.Data;

import java.util.List;

/**
 * 三目录医保对照-清除对照请求: 将指定院内条目的医保对照码置空。
 */
@Data
public class CatalogMapClearReq {
    /** 目录: drug-药品 cons-耗材 charge-医疗服务项目 */
    private String catalog;
    /** 待清除的院内条目 id 列表 */
    private List<Long> itemIds;
}
