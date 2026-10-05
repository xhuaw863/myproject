package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 开立项目自动计费规则(需求2.2.2.3.14.3): 按部位数/指标数/会诊数/草药制法等维度附加计费(如CT增强按部位加收)。
 * 数据源: 本表(item_id 关联 his_base_item); 开项目时命中维度自动追加附加收费行(C2 接线开立流程)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_charge_addon_rule")
public class HisChargeAddonRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID(空=租户通用) */
    private Long orgId;
    /** 科室ID(空=全院通用) */
    private Long deptId;
    /** 主项目ID(his_base_item.id): 开立该项目时触发加收 */
    private Long itemId;
    /** 主项目名称(冗余) */
    private String itemName;
    /** 加收维度: part部位数/index指标数/consult会诊数/herb_process草药制法 */
    private String dimType;
    /** 触发阈值(维度数量达到该值起加收, 如部位数>=1) */
    private Integer dimThreshold;
    /** 计价方式: fixed固定单价/formula公式 */
    private String calcMode;
    /** 加收单位价格(按超出阈值数量计费; fixed 模式使用) */
    private BigDecimal unitPrice;
    /** 加收比例(ratio 模式使用: 如 0.5000=每增加一部位按主项目50%加收) */
    private BigDecimal dimRatio;
    /** 计费公式(formula 模式使用, 服务端求值, 禁止前端传入) */
    private String formula;
    /** 加收项编码(his_base_item.item_code): 加收落到该收费项目 */
    private String addonItemCode;
    /** 加收项名称(冗余) */
    private String addonItemName;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
