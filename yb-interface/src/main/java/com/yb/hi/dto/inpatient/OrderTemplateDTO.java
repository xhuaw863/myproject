package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.util.List;

/**
 * 住院医嘱模板/套餐请求(items 列表序列化为 his_order_template.items JSON 落库)
 */
@Data
public class OrderTemplateDTO {

    /** 模板名称 */
    private String templateName;
    /** 模板级别: 1个人 2科室 3全院 */
    private Integer templateType;
    /** 范围类型: 1单条 2套餐 */
    private Integer scopeType;
    /** 科室ID(his_dept.id, 科室级模板) */
    private Long deptId;
    /** 医生ID(his_staff.id, 个人级模板) */
    private Long doctorId;
    /** 医嘱项列表 */
    private List<OrderTemplateItemDTO> items;
    /** 适用病种编码 */
    private String diseaseCode;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
