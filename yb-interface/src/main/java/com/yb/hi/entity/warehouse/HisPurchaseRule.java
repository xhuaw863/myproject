package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 智能采购规则配置: 按机构/药库(空=全院默认)配置高低储/参考月数/发药量与出库量权重/ABC 占比阈值,
 * 供采购计划智能生成计算建议量。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_purchase_rule")
public class HisPurchaseRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 适用药库ID(空=全院默认) */
    private Long warehouseId;
    /** 规则名称 */
    private String ruleName;
    /** 参考月数(入出库/发药统计窗口) */
    private Integer referMonths;
    /** 低储标准(补货点) */
    private BigDecimal loQty;
    /** 高储标准 */
    private BigDecimal hiQty;
    /** 发药量权重 */
    private BigDecimal dispenseWeight;
    /** 出库量权重 */
    private BigDecimal stockoutWeight;
    /** ABC分类A累计占比(列名 abc_a_ratio, 显式映射避免驼峰转换歧义) */
    @TableField("abc_a_ratio")
    private BigDecimal abcARatio;
    /** ABC分类B累计占比(列名 abc_b_ratio) */
    @TableField("abc_b_ratio")
    private BigDecimal abcBRatio;
    /** 启用: 1是 0否 */
    private Integer enabled;
    /** 备注 */
    private String remark;
}
