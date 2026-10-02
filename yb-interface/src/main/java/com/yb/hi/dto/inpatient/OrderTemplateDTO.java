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
    /** 适用场景: 1普通住院 2手术医嘱(P2b) */
    private Integer applyScene;
    /** 手术模板目标阶段: 1术前 2术中 3术后(apply_scene=2 时有值) */
    private Integer surgeryPhase;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
