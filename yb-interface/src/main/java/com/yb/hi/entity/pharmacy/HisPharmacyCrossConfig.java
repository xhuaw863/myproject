package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 跨药房发药配置(源药房↔目标药房, 允许跨院区/跨药房发药)
 * 唯一键: tenant_id + source_pharmacy_id + target_pharmacy_id
 * 实际跨药房发药复用 PharmacyService.transferOptions/transferPrescription 路径。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pharmacy_cross_config")
public class HisPharmacyCrossConfig extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 源药房ID(his_pharmacy_def.id) */
    private Long sourcePharmacyId;
    /** 目标药房ID(his_pharmacy_def.id) */
    private Long targetPharmacyId;
    /** 允许跨状态发药: 1是 0否 */
    private Integer allowCrossStatus;
    /** 启用: 1是 0否 */
    private Integer enabled;
    /** 备注 */
    private String remark;
}
