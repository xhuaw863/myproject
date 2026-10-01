package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 药品养护单明细: 一行一(药品+批次), 记录养护措施/结果/结论。
 * (沿用请领/调拨/验收/付款明细惯例: 不映射审计列, 主表统一软删)
 */
@Data
@TableName("his_drug_maintenance_item")
public class HisDrugMaintenanceItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 养护单ID */
    private Long mntId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码 */
    private String drugCode;
    /** 药品名称 */
    private String drugName;
    /** 规格 */
    private String spec;
    /** 批次号 */
    private String batchNo;
    /** 生产厂家 */
    private String manufacturer;
    /** 剂型 */
    private String dosform;
    /** 储存条件 */
    private String storageCond;
    /** 在库数量 */
    private BigDecimal qty;
    /** 有效期 */
    private LocalDate expDate;
    /** 养护措施 */
    private String measure;
    /** 养护结果: 1合格 2异常 */
    private Integer result;
    /** 养护人 */
    private String handler;
    /** 结论/异常描述 */
    private String conclusion;
    /** 备注 */
    private String remark;
}
