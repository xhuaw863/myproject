package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 处方发药默认药房路由(P5): 一条规则 = (科室 × 时段 × 药品大类) → 发药药房。
 * deptId/timeSlot/drugMajorClass 任一为 null 表示该维度不限(通配), 解析按精确度优先级链匹配,
 * 未命中回落 his_dept.def_pharmacy_west/tcm。同(科室+时段+大类)组合由服务层判重(允 null 维度故不加 DB 唯一键)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_rx_pharmacy_route")
public class HisRxPharmacyRoute extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 开单科室ID(his_dept.id, null=全院该维度) */
    private Long deptId;
    /** 班次时段码(his_shift_dict.code, null=不限) */
    private String timeSlot;
    /** 药品大类(his_drug_catalog.major_class 文本, null=不限) */
    private String drugMajorClass;
    /** 命中发药药房ID(his_pharmacy_def.id) */
    private Long pharmacyId;
    /** 优先级(数字小者优先, 同级按精确度) */
    private Integer priority;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String remark;
}
