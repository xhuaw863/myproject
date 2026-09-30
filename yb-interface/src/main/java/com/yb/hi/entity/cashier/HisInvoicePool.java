package com.yb.hi.entity.cashier;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 发票号池(机构级发票号段, 收费时按池顺序取号)
 * 唯一键: tenant_id + org_id + pool_code
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_invoice_pool")
public class HisInvoicePool extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 号池编码 */
    private String poolCode;
    /** 发票类型: NORMAL-纸质发票 / ELECTRONIC-电子发票 */
    private String invoiceType;
    /** 发票号前缀 */
    private String prefix;
    /** 起始号 */
    private Long startNo;
    /** 结束号 */
    private Long endNo;
    /** 当前已用号(下一个待取号=currentNo+1) */
    private Long currentNo;
    /** 状态: 0未启用 1使用中 2已用完 */
    private Integer status;
    /** 分配人 */
    private String allocBy;
    /** 分配时间 */
    private LocalDateTime allocTime;

    /* ===== 派生展示字段(非表列, poolPage 查询后由 Service 填充; 供前端只读展示, 避免前端对大整数号段做加减) ===== */
    /** 总号数 = endNo - startNo + 1(含起止) */
    @TableField(exist = false)
    private Long totalQty;
    /** 已用号数 = currentNo - startNo + 1(未启用为 0, 越界收敛至 [0, totalQty]) */
    @TableField(exist = false)
    private Long usedQty;
    /** 已用百分比(0-100, 四舍五入) */
    @TableField(exist = false)
    private Integer usedPercent;
}
