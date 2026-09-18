package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 诊断信息(节点: diseinfo)
 * 用于 2203门诊就诊信息上传 / 2401入院办理 / 2402出院办理
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class DiseInfoReq {

    /** 就诊ID(住院诊断需要) */
    private String mdtrtId;
    /** 人员编号(住院诊断需要) */
    private String psnNo;
    /** 诊断类别 */
    private String diagType;
    /** 诊断排序号 */
    private Integer diagSrtNo;
    /** 诊断代码 */
    private String diagCode;
    /** 诊断名称 */
    private String diagName;
    /** 入院病情 */
    private String admCond;
    /** 诊断科室 */
    private String diagDept;
    /** 诊断医生编码 */
    private String diseDorNo;
    /** 诊断医生姓名 */
    private String diseDorName;
    /** 诊断时间 yyyy-MM-dd HH:mm:ss */
    private String diagTime;
    /** 有效标志 */
    private String valiFlag;
    /** 主诊断标识 0-否 1-是 */
    private String maindiagFlag;
}
