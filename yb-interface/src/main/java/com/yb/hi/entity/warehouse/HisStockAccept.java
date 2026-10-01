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
 * 财务验收单主表(单号 YS+yyyyMMdd+4位, 租户内唯一): 支持单张入库验收(accept_type=1, stock_in_id 指向该单)
 * 或按供应商集中验收(accept_type=2, 覆盖该供应商多张未验收入库单, bill_count 记录张数)。
 * 验收通过后回写来源入库单 accept_status=1。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_stock_accept")
public class HisStockAccept extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 验收单号(YS+yyyyMMdd+4位序号) */
    private String acceptNo;
    /** 药库ID */
    private Long warehouseId;
    /** 供应商ID(集中验收按供应商归集) */
    private Long supplierId;
    /** 验收方式: 1单张入库 2按供应商集中 */
    private Integer acceptType;
    /** 单张验收指向的入库单ID(集中验收为空) */
    private Long stockInId;
    /** 验收覆盖入库单张数 */
    private Integer billCount;
    /** 验收金额合计 */
    private BigDecimal totalAmount;
    /** 验收结论: 1合格 2异常 */
    private Integer conclusion;
    /** 验收人 */
    private String acceptBy;
    /** 验收时间 */
    private LocalDateTime acceptTime;
    /** 状态: 0草稿 1已验收 */
    private Integer status;
    /** 备注 */
    private String remark;
}
