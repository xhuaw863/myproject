package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 药房定义(机构级多药房: 门诊药房/住院药房/中药房, 发药记录按药房归属)
 * 唯一键: tenant_id + org_id + code
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pharmacy_def")
public class HisPharmacyDef extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 药房编码 */
    private String code;
    /** 药房名称(如 门诊药房 / 住院药房 / 中药房) */
    private String name;
    /** 药房类型: OUTPATIENT-门诊药房 / INPATIENT-住院药房 / TCM-中药房 */
    private String pharmacyType;
    /** 关联药库ID(his_warehouse_def.id) */
    private Long warehouseId;
    /** 药房位置 */
    private String location;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 排序号 */
    private Integer sortNo;
}
