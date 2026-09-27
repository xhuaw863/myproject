package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 药房药品定价(药房维度覆盖价): 有覆盖价则开方按本药房价计费, 清空(物理删)回落目录全局价。
 * 唯一键: tenant_id + org_id + pharmacy_id + drug_catalog_id
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pharmacy_drug_price")
public class HisPharmacyDrugPrice extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 药房ID(his_pharmacy_def.id) */
    private Long pharmacyId;
    /** 医共体药品目录ID(his_drug_catalog.id) */
    private Long drugCatalogId;
    /** 药房零售价(最小单位, 覆盖目录价) */
    private BigDecimal retailPrice;
}
