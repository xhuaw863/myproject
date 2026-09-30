package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 住院医嘱模板医嘱项(与住院医嘱开立字段对齐, 作为 his_order_template.items JSON 数组元素)
 */
@Data
public class OrderTemplateItemDTO {

    /** 医嘱类型: 1长期 2临时 */
    private Integer orderType;
    /** 医嘱分类: 1药品 2检查 3检验 4治疗 5护理 6膳食 7其他 */
    private Integer orderCategory;
    /** 医嘱内容 */
    private String orderContent;
    /** 收费项目ID */
    private Long chargeItemId;
    /** 药品ID(药品目录) */
    private Long drugId;
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
}
