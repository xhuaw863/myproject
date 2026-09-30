package com.yb.hi.dto.community;

import lombok.Data;

import java.util.List;

/**
 * 三目录/疾病医保对照-人工或预览确认写入请求: catalog 指定目录(疾病对照载 dict_type), items 为 (院内条目, 标准字典行) 配对。
 */
@Data
public class CatalogMapApplyReq {
    /** 目录: drug-药品 cons-耗材 charge-医疗服务项目; 疾病对照(diag-map)复用本字段承载 dict_type(west/tcm/symp/oper) */
    private String catalog;
    /** 对照配对明细 */
    private List<Item> items;
    /** 变更对照确认标记: 已对照条目改为不同医保码时必须为 true(前端二次确认后传), 否则拒绝写入 */
    private Boolean force;
    /** 写入来源(仅疾病对照消费): auto-批量自动对照留痕用; 空/manual-人工; 三目录忽略 */
    private String src;

    @Data
    public static class Item {
        /** 院内目录条目 id */
        private Long itemId;
        /** 标准字典(医保目录)行 id */
        private Long stdId;
        /** 自动对照置信度(可选, 批量提交时回传落留痕; 人工对照不传) */
        private Double score;
    }
}
