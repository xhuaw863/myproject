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
 * 库房月结记录(财务账+实物账双轨): 按 (机构, 药库, 期间) 汇总本期期初/期末与收支配对。
 * 记账标准 acct_standard: 1进价 3零售价(批发价2留待后补); 起始=上次月结终止+1, 首次由用户指定建账日。
 * status: 0进行中 1已月结 -1已取消; 取消仅允许最后一个月结月且逐月不跳月。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_stock_month_end")
public class HisStockMonthEnd extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 药库ID(空=全院口径) */
    private Long warehouseId;
    /** 本期起始日 */
    private LocalDate periodStart;
    /** 本期终止日 */
    private LocalDate periodEnd;
    /** 记账标准: 1进价 3零售价 */
    private Integer acctStandard;
    /** 状态: 0进行中 1已月结 -1已取消 */
    private Integer status;
    /** 期初金额(财务账) */
    private BigDecimal openingAmount;
    /** 期末金额(财务账) */
    private BigDecimal closingAmount;
    /** 本期收入(入库)金额 */
    private BigDecimal incomeAmount;
    /** 本期支出(出库)金额 */
    private BigDecimal expenseAmount;
    /** 期初数量(实物账) */
    private BigDecimal openingQty;
    /** 期末数量(实物账) */
    private BigDecimal closingQty;
    /** 本期入库数量 */
    private BigDecimal inQty;
    /** 本期出库数量 */
    private BigDecimal outQty;
    /** 参与结账品种数 */
    private Integer drugCount;
    /** 月结人 */
    private String confirmBy;
    /** 月结时间 */
    private LocalDateTime confirmTime;
    /** 备注 */
    private String remark;
}
