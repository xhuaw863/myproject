package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 药品库存(批次级独立记账, 有效期预警)
 * 唯一键: tenant_id + org_id + drug_catalog_id + batch_no
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_drug_stock")
public class HisDrugStock extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码 */
    private String drugCode;
    /** 药品名称 */
    private String drugName;
    /** 规格 */
    private String spec;
    /** 剂型 */
    private String dosform;
    /** 批次号 */
    private String batchNo;
    /** 生产厂家 */
    private String manufacturer;
    /** 库存数量 */
    private BigDecimal qty;
    /** 进价 */
    private BigDecimal costPrice;
    /** 零售价 */
    private BigDecimal retailPrice;
    /** 生产日期 */
    private LocalDate prodDate;
    /** 有效期 */
    private LocalDate expDate;
    /** 预警量(qty<=warn_qty 触发低库存预警) */
    private BigDecimal warnQty;
    /** 状态: 1正常 0停用 */
    private Integer status;
}
