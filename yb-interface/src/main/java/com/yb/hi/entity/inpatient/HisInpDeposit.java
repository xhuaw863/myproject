package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 住院预交金流水(缴纳/退还双向, balance_after 记录操作后余额便于对账)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_deposit")
public class HisInpDeposit extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 金额 */
    private BigDecimal amount;
    /** 支付方式(his_pay_method_dict.code 规范码; 历史行可能为数字/小写旧值, 经字典 legacy_codes 回显) */
    private String payType;
    /** 方向: 1缴纳 2退还 */
    private Integer direction;
    /** 操作后余额 */
    private BigDecimal balanceAfter;
    /** 操作员ID(his_staff.id) */
    private Long operatorId;
    /** 收据号 */
    private String receiptNo;
    /** 备注 */
    private String remark;
}
