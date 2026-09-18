package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【2203】门诊就诊信息 输入(节点: mdtrtinfo)
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class MdtrtInfoReq {

    /** 就诊ID */
    private String mdtrtId;
    /** 人员编号 */
    private String psnNo;
    /** 医疗类别 */
    private String medType;
    /** 就诊时间/开始时间 yyyy-MM-dd HH:mm:ss */
    private String begntime;
    /** 主要病情描述 */
    private String mainCondDscr;
    /** 病种类型代码 */
    private String diseTypeCode;
    /** 病种编码 */
    private String diseCodg;
    /** 病种名称 */
    private String diseName;
    /** 计划生育手术类别 */
    private String birctrlType;
    /** 计划生育手术或生育日期 yyyy-MM-dd */
    private String birctrlMatnDate;
    /** 字段扩展 */
    private String expContent;
}
