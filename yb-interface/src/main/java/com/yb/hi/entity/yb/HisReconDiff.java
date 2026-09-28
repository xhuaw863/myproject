package com.yb.hi.entity.yb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 医保对账差异明细(M3): 3202 返回不一致的结算明细逐条留痕(表201 输出文件解析),
 * 人工核对平台侧后按 3202 重对或线下处理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_recon_diff")
public class HisReconDiff extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 对账任务ID(his_recon_task.id) */
    private Long reconTaskId;
    /** 对账日期 */
    private LocalDate stmtDate;
    /** 结算ID(中心端多条时为空, 规范表201说明7) */
    private String setlId;
    /** 就诊ID(中心端多条时为空) */
    private String mdtrtId;
    /** 人员编号 */
    private String psnNo;
    /** 原交易报文ID */
    private String msgid;
    /** 核对结果(表201 stmt_rslt, 6位) */
    private String stmtRslt;
    /** 退费结算标志(3位) */
    private String refdSetlFlag;
    /** 说明(表201 memo, 500) */
    private String memo;
    /** 医疗费总额-平台 */
    private BigDecimal medfeeSumamt;
    /** 基金支付总额-平台 */
    private BigDecimal fundPaySumamt;
    /** 个账支付-平台 */
    private BigDecimal acctPay;
    /** 处理状态: 0待处理 1已核对 2已平账 */
    private Integer status;
    /** 处理备注 */
    private String handleMemo;
    /** 处理时间 */
    private java.time.LocalDateTime handleTime;
    /** 处理人 */
    private String handleBy;
}
