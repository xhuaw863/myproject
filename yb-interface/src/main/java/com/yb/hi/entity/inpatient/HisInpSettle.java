package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 住院结算(出院/中途/退费三类, 医保结算状态与 2207/2208 链路对齐)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_settle")
public class HisInpSettle extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 结算单号 */
    private String settleNo;
    /** 总金额 */
    private BigDecimal totalAmount;
    /** 自付金额 */
    private BigDecimal selfPay;
    /** 基金支付 */
    private BigDecimal fundPay;
    /** 现金支付 */
    private BigDecimal cashPay;
    /** 个账支付 */
    private BigDecimal acctPay;
    /** 预交金抵扣 */
    private BigDecimal depositDeduct;
    /** 退还金额 */
    private BigDecimal refundAmount;
    /** 结算类型: 1出院结算 2中途结算 3退费 */
    private Integer settleType;
    /** 医保状态: 0未结算 1结算中 2已结算 3撤销中 4已撤销 */
    private Integer ybStatus;
    /** 结算时间 */
    private LocalDateTime settleTime;
    /** 操作员ID(his_staff.id) */
    private Long operatorId;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** DRG分组编码 */
    private String drgGroupCode;
    /** DIP病种编码 */
    private String dipCode;
    /** 支付方式 */
    private Integer payMethod;
}
