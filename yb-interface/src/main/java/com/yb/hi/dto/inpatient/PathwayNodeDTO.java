package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 临床路径节点创建请求
 */
@Data
public class PathwayNodeDTO {

    /** 模板ID(his_pathway_template.id) */
    private Long templateId;
    /** 第X天 */
    private Integer dayNo;
    /** 节点名称 */
    private String nodeName;
    /** 节点描述 */
    private String nodeDesc;
    /** 排序号 */
    private Integer sortNo;
}
