package com.yb.hi.dto.community;

import lombok.Data;

import java.util.List;

/**
 * 三目录医保对照-人工/预览确认写入请求: catalog 指定目录, items 为 (院内条目, 标准字典行) 配对。
 */
@Data
public class CatalogMapApplyReq {
    /** 目录: drug-药品 cons-耗材 charge-医疗服务项目 */
    private String catalog;
    /** 对照配对明细 */
    private List<Item> items;

    @Data
    public static class Item {
        /** 院内目录条目 id */
        private Long itemId;
        /** 标准字典(医保目录)行 id */
        private Long stdId;
    }
}
