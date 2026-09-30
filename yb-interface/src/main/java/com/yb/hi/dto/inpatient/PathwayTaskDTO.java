package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 临床路径节点任务(医嘱模板)创建请求
 */
@Data
public class PathwayTaskDTO {

    /** 节点ID(his_pathway_node.id) */
    private Long nodeId;
    /** 模板ID(his_pathway_template.id) */
    private Long templateId;
    /** 任务类型: 1医嘱 2护理 3检查 4检验 5宣教 */
    private Integer taskType;
    /** 收费项目ID */
    private Long chargeItemId;
    /** 药品ID(药品目录) */
    private Long drugId;
    /** 医嘱类型: 1长期 2临时 */
    private Integer orderType;
    /** 医嘱分类: 1药品 2检查 3检验 4治疗 5护理 6膳食 7其他 */
    private Integer orderCategory;
    /** 医嘱内容 */
    private String orderContent;
    /** 规格 */
    private String spec;
    /** 剂量 */
    private String dosage;
    /** 剂量单位 */
    private String dosageUnit;
    /** 用法编码 */
    private String usageCode;
    /** 频次编码 */
    private String freqCode;
    /** 数量 */
    private BigDecimal quantity;
    /** 单价 */
    private BigDecimal unitPrice;
    /** 是否必做: 1必做 0可选 */
    private Integer isMandatory;
    /** 排序号 */
    private Integer sortNo;
}
