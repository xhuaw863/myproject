package com.yb.hi.entity.yb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 医保对账任务(M3): 每日 3201 对总账(按险种逐条), 不平再跑 3202 对明细账。
 * 结果三分: 平(1)/不平(2)/失败(9); 差异明细落 his_recon_diff 供人工核对。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_recon_task")
public class HisReconTask extends BaseEntity {

    public static final String RESULT_MATCH = "1";
    public static final String RESULT_DIFF = "2";
    public static final String RESULT_FAIL = "9";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID(租户级对账为空) */
    private Long orgId;
    /** 对账日期(T-1 结算日) */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate stmtDate;
    /** 险种类型(3201 分组维度) */
    private String insutype;
    /** 对账类型: TOTAL(3201总账)/DETAIL(3202明细账) */
    private String reconType;
    /** 结果: 1平 2不平 9失败 */
    private String result;
    /** 医疗费总额-院内口径 */
    private BigDecimal medfeeLocal;
    /** 医疗费总额-平台回执口径 */
    private BigDecimal medfeeRemote;
    /** 基金支付总额-院内口径 */
    private BigDecimal fundLocal;
    /** 基金支付总额-平台回执口径 */
    private BigDecimal fundRemote;
    /** 个账支付-院内口径(3202为现金) */
    private BigDecimal acctLocal;
    /** 个账支付-平台回执口径 */
    private BigDecimal acctRemote;
    /** 结算笔数-院内口径 */
    private Integer cntLocal;
    /** 结算笔数-平台回执口径 */
    private Integer cntRemote;
    /** 3202 明细文件查询号(9101 返回) */
    private String fileQuryNo;
    /** 平台回执信息(表197 stmt_rslt/stmt_rslt_dscr 或差异说明) */
    private String stmtRslt;
    /** 对账执行时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reconTime;
    /** 备注 */
    private String memo;
}
