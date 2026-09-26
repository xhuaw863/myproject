package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出库单主表(处方发药/报损/盘亏/调拨出)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_stock_out")
public class HisStockOut extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 药库ID(his_warehouse_def.id) */
    private Long warehouseId;
    /** 出库单号(CK+yyyyMMdd+4位序号) */
    private String outNo;
    /** 出库类型: 1处方发药 2报损 3盘亏 4调拨出 */
    private Integer outType;
    /** 关联单据ID(处方发药=处方ID等) */
    private Long refId;
    /** 关联单据号 */
    private String refNo;
    /** 总金额 */
    private BigDecimal totalAmount;
    /** 状态: 0草稿 1已确认 2已作废 */
    private Integer status;
    /** 确认人 */
    private String confirmBy;
    /** 确认时间 */
    private LocalDateTime confirmTime;
    /** 备注 */
    private String remark;
}
