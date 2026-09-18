package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【2202】门诊挂号撤销 输入(节点: data)
 * 输出: 无
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class OutpatientRegisterCancelReq {

    /** 人员编号 */
    private String psnNo;
    /** 就诊ID(2201返回) */
    private String mdtrtId;
    /** 住院/门诊号(院内唯一流水) */
    private String iptOtpNo;
    /** 字段扩展 */
    private String expContent;
}
