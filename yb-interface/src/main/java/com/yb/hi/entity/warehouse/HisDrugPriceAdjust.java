package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 药品调价单(药库/药房统一): 批量对药品目录进/零售价做草稿→生效批次调价, 生效后同步目录当前价与在库零售价, 并写 his_price_adjust 留痕。
 * 与医共体目录逐字段留痕 his_price_adjust 不同: 本表是"批次调价单头"(带明细/预览差额/状态机), 故独立命名。
 * 状态机: 0草稿 1已生效 2已作废。单号 TJ+yyyyMMdd+4位(租户内唯一)。目录价格是医共体级(牵头统一维护), 写操作限牵头。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_drug_price_adjust")
public class HisDrugPriceAdjust extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID(空=全医共体调价) */
    private Long orgId;
    /** 调价单号 */
    private String adjustNo;
    /** 范围: ALL/DRUG */
    private String scope;
    /** 调价域: CATALOG目录/WAREHOUSE药库/PHARMACY药房/ALL全部(默认 ALL=向后兼容旧无差别刷价) */
    private String priceDomain;
    /** 药库域目标库位(his_warehouse_def.id) */
    private Long targetWarehouseId;
    /** 药房域目标药房(his_pharmacy_def.id) */
    private Long targetPharmacyId;
    /** 到生效日是否自动生效: 1=调度器认领生效, 0=仅手动 */
    private Integer autoEffect;
    /** 生效日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effectiveDate;
    /** 状态: 0草稿 1已生效 2已作废 */
    private Integer status;
    /** 调价原因 */
    private String reason;
    /** 操作人 */
    private String operator;
    /** 生效时间 */
    private LocalDateTime effectTime;
    /** 在库金额影响合计(预览) */
    private BigDecimal totalDiffAmount;
    /** 备注 */
    private String remark;
}
