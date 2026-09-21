package com.yb.hi.dto.community;

import lombok.Data;

import java.util.List;

/**
 * 三目录医保对照-批量自动对照请求: 对未对照(或指定)条目跑匹配器, 达阈值者写入。
 * dryRun=true 仅返回预览不写库。
 */
@Data
public class CatalogMapAutoReq {
    /** 目录: drug-药品 cons-耗材 charge-医疗服务项目 */
    private String catalog;
    /** 限定条目 id 子集(空=全部未对照) */
    private List<Long> itemIds;
    /** 自动写入置信度阈值(空=默认 0.95) */
    private Double threshold;
    /** 是否仅预览不写库 */
    private Boolean dryRun;
}
