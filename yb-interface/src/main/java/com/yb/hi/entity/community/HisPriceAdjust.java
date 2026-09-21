package com.yb.hi.entity.community;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 医共体目录调价留痕(三目录统一): 调价文号 + 生效日期必录, 目录当前价随调价同步。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_price_adjust")
public class HisPriceAdjust extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目录类型: charge/drug/cons */
    private String catalogType;
    /** 目录记录ID */
    private Long catalogId;
    /** 目录名称(冗余) */
    private String catalogName;
    /** 调价字段: price_l1/l2/l3 或 purchase_price/retail_price/charge_price */
    private String priceField;
    /** 调价字段中文名 */
    private String priceLabel;
    /** 收费项目价格档次:1/2/3(药耗为空) */
    private Integer orgLevel;
    /** 原价 */
    private BigDecimal oldPrice;
    /** 新价 */
    private BigDecimal newPrice;
    /** 调价文号 */
    private String adjustDocNo;
    /** 生效日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effDate;
    /** 调价原因 */
    private String reason;
    /** 操作人姓名 */
    private String operatorName;
}
