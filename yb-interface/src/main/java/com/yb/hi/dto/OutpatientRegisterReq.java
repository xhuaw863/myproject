package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【2201】门诊挂号 输入(节点: data)
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class OutpatientRegisterReq {

    /** 人员编号 */
    private String psnNo;
    /** 险种类型 */
    private String insutype;
    /** 挂号时间/开始时间 yyyy-MM-dd HH:mm:ss */
    private String begntime;
    /** 就诊凭证类型 01-电子凭证 02-身份证 03-社保卡 */
    private String mdtrtCertType;
    /** 就诊凭证编号 */
    private String mdtrtCertNo;
    /** 住院/门诊号(院内唯一流水) */
    private String iptOtpNo;
    /** 医师编码 */
    private String atddrNo;
    /** 医师姓名 */
    private String drName;
    /** 科室编码 */
    private String deptCode;
    /** 科室名称 */
    private String deptName;
    /** 科别 */
    private String caty;
    /** 医疗类别(2201A新增) */
    private String medType;
    /** 字段扩展 */
    private String expContent;
}
