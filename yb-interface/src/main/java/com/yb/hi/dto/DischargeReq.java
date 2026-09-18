package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【2402】出院办理 输入-出院信息(节点: dscginfo)
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class DischargeReq {

    /** 就诊ID */
    private String mdtrtId;
    /** 人员编号 */
    private String psnNo;
    /** 险种类型 */
    private String insutype;
    /** 出院时间/结束时间 yyyy-MM-dd HH:mm:ss */
    private String endtime;
    /** 病种编码 */
    private String diseCodg;
    /** 病种名称 */
    private String diseName;
    /** 手术操作代码 */
    private String oprnOprtCode;
    /** 手术操作名称 */
    private String oprnOprtName;
    /** 伴有并发症标志 */
    private String copFlag;
    /** 出院科室编码 */
    private String dscgDeptCodg;
    /** 出院科室名称 */
    private String dscgDeptName;
    /** 出院床位 */
    private String dscgBed;
    /** 离院方式 */
    private String dscgWay;
    /** 死亡日期 yyyy-MM-dd */
    private String dieDate;
    /** 字段扩展 */
    private String expContent;
}
