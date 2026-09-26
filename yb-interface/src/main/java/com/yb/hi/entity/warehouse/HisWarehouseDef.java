package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 药库定义(机构级多药库: 西药库/中药库/混合库, 药库库存/入出库按库房独立记账)
 * 唯一键: tenant_id + org_id + code
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_warehouse_def")
public class HisWarehouseDef extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 仓库编码(如 WH-WEST-01) */
    private String code;
    /** 仓库名称(如 西药库) */
    private String name;
    /** 仓库类型: WESTERN-西药库 / TCM-中药库 / MIXED-混合库 */
    private String warehouseType;
    /** 库房位置 */
    private String location;
    /** 负责人 */
    private String manager;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 排序号 */
    private Integer sortNo;
}
