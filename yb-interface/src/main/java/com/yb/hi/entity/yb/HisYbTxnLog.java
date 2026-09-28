package com.yb.hi.entity.yb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医保出站交易日志(M1): 全部 infno 出站交易统一落库,
 * 是 2601 冲正 omsgid(原发送方报文ID)的唯一持久化来源,
 * 也是补偿/对账定位"平台侧状态"的事实基础。
 * 状态: PENDING 已发/待回执; SUCCESS 平台成功; FAIL 平台明确拒绝; UNKNOWN 超时或网络异常(平台侧状态不可知); REVERSED 已冲正。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_yb_txn_log")
public class HisYbTxnLog extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_SUCCESS = "SUCCESS";
    public static final String ST_FAIL = "FAIL";
    public static final String ST_UNKNOWN = "UNKNOWN";
    public static final String ST_REVERSED = "REVERSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID(发起交易的收费/业务机构) */
    private Long orgId;
    /** 交易编号(2201/2204/2207/2208/2601/3301...) */
    private String infno;
    /** 发送方报文ID(机构12+时间14+顺序4; 2601 冲正的 omsgid 即取自这里) */
    private String msgid;
    /** 就诊ID(可从输入抽取时回填) */
    private String mdtrtId;
    /** 人员编号 */
    private String psnNo;
    /** 结算ID(2207 成功回执/2208 撤销目标) */
    private String setlId;
    /** 收费批次号(2204/2205/2207) */
    private String chrgBchno;
    /** 关联院内收费单/退费单ID(补偿任务回填) */
    private Long billId;
    /** 状态: PENDING/SUCCESS/FAIL/UNKNOWN/REVERSED */
    private String status;
    /** 错误信息(FAIL/UNKNOWN) */
    private String errMsg;
    /** 请求 input 原文(JSON) */
    private String inputJson;
    /** 响应原文(JSON) */
    private String outputJson;
}
