package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /** 库存位类型: WAREHOUSE-药库(默认) / PHARMACY-药房库存位(承载药房自有库存, 两级库存基座) */
    private String kind;
    /** PHARMACY 型库存位回指的药房ID(his_pharmacy_def.id); WAREHOUSE 型为 null */
    private Long refPharmacyId;
    /** 库房位置 */
    private String location;
    /** 负责人 */
    private String manager;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 排序号 */
    private Integer sortNo;
    /** 归属科室(his_dept.id): 仅 kind=WAREHOUSE 药库使用, 与科室一一对应; PHARMACY 库存位随药房继承不单独绑定; 空=历史未绑定 */
    private Long deptId;
    /** 归属科室名称(服务端按 deptId 运行时回填, 不落库): 供前端"当前药库"上下文条展示所属科室 */
    @TableField(exist = false)
    private String deptName;
}
