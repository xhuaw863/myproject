package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【2601】冲正 输入(节点: data, 单行, 规范 5.2.7.1 仅 3 字段, 不增不减)
 * 可冲正清单: 2102/2103/2207/2208/2304/2305/2401/2304A/2102A。
 * omsgid 必须取原交易持久化 msgid(his_yb_txn_log 唯一来源), 不得重新生成。
 * 输出: 无节点——冲正成功与否须以 3201/3202 对账复核, 不能仅凭调用无异常判定完成。
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class ReverseReq {

    /** 人员编号(30, Y)——原发送方报文的 psn_no */
    private String psnNo;
    /** 原发送方报文ID(30, Y)——原交易的 msgid */
    private String omsgid;
    /** 原交易编号(5, Y)——如 2207/2208/2304/2305 */
    private String oinfno;
}
