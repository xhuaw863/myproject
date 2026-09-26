package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 盘点单主表(按药库整库盘点; 确认后按明细差异生成盘盈入库/盘亏出库)
 * 唯一键: tenant_id + check_no
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_stock_check")
public class HisStockCheck extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 药库ID(his_warehouse_def.id) */
    private Long warehouseId;
    /** 盘点单号(PD+yyyyMMdd+4位序号) */
    private String checkNo;
    /** 盘点日期 */
    private LocalDate checkDate;
    /** 状态: 0进行中 1已完成 2已作废 */
    private Integer status;
    /** 盘点人 */
    private String checkBy;
    /** 确认人 */
    private String confirmBy;
    /** 确认时间 */
    private LocalDateTime confirmTime;
    /** 盘盈金额合计 */
    private BigDecimal profitAmount;
    /** 盘亏金额合计 */
    private BigDecimal lossAmount;
    /** 备注 */
    private String remark;
}
