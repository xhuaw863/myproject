package com.yb.hi.entity.yb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 医保补偿任务(M1): 医保交易出现 UNKNOWN(超时/网络异常)或本地终态回写失败时生成,
 * 由 CompTaskSweeper 定时扫描驱动(重试/补录/回退/告警), 保证平台侧与院内数据最终一致。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_comp_task")
public class HisCompTask extends BaseEntity {

    public static final String BIZ_CHARGE = "CHARGE";
    public static final String BIZ_REFUND = "REFUND";
    public static final String BIZ_PARTIAL_REFUND = "PARTIAL_REFUND";
    /** 住院结算 UNKNOWN(2304 超时/异常): ref_id=his_inp_settle.id, 2301->2304 链结果未知收敛 */
    public static final String BIZ_INP_SETTLE = "INP_SETTLE";
    /** 住院结算撤销 UNKNOWN(2305 超时/异常): ref_id=his_inp_settle.id */
    public static final String BIZ_INP_RTN = "INP_RTN";

    public static final String ACT_RESOLVE_UNKNOWN = "RESOLVE_UNKNOWN";

    public static final String ST_PENDING = "PENDING";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_DONE = "DONE";
    public static final String ST_DEAD = "DEAD";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型: CHARGE/REFUND/PARTIAL_REFUND/INP_SETTLE/INP_RTN */
    private String bizType;
    /** 关联业务主键(收费单ID等) */
    private Long refId;
    /** 动作: RESOLVE_UNKNOWN(核对UNKNOWN交易并补录/回退) 等 */
    private String action;
    /** 触发该任务的 UNKNOWN 交易日志ID(his_yb_txn_log.id) */
    private Long txnLogId;
    /** 状态: PENDING/RUNNING/DONE/DEAD */
    private String status;
    /** 已尝试次数 */
    private Integer attempts;
    /** 下次执行时间(指数退避) */
    private LocalDateTime nextRun;
    /** 备注/最近一次执行结果 */
    private String memo;
}
