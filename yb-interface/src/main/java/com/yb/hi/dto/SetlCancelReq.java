package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 结算撤销 输入(节点: data)
 * 用于 2208门诊结算撤销 / 2305住院结算撤销
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class SetlCancelReq {

    /** 结算ID */
    private String setlId;
    /** 就诊ID */
    private String mdtrtId;
    /** 人员编号 */
    private String psnNo;
    /** 字段扩展 */
    private String expContent;
}
