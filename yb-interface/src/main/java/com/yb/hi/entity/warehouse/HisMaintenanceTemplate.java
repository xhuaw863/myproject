package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 药品养护模板: 保存一组可复用的养护筛选条件(药库/剂型/储存条件/关键字) + 默认养护措施,
 * "按模板建单"时据此从在库库存(his_drug_stock)自动生行并预填措施。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_maintenance_template")
public class HisMaintenanceTemplate extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板名称 */
    private String templateName;
    /** 适用药库(空=全部) */
    private Long warehouseId;
    /** 筛选:剂型 */
    private String dosform;
    /** 筛选:储存条件 */
    private String storageCond;
    /** 筛选:药品名称/编码关键字 */
    private String drugKeyword;
    /** 默认养护措施 */
    private String defaultMeasure;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String remark;
}
