package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 药品调价明细: 快照调价前后进/零售价与当前在库量(预览影响), 生效时按此更新目录与库存零售价。
 */
@Data
@TableName("his_drug_price_adjust_item")
public class HisDrugPriceAdjustItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调价单ID */
    private Long priceAdjustId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码(快照) */
    private String drugCode;
    /** 药品名称(快照) */
    private String drugName;
    /** 规格(快照) */
    private String spec;
    /** 原进价 */
    private BigDecimal oldPurchase;
    /** 新进价 */
    private BigDecimal newPurchase;
    /** 原零售价 */
    private BigDecimal oldRetail;
    /** 新零售价 */
    private BigDecimal newRetail;
    /** 当前在库数量(预览影响) */
    private BigDecimal impactStockQty;
}
