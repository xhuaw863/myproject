package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【2401】入院办理 输入-就诊信息(节点: mdtrtinfo)
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class AdmissionReq {

    /** 人员编号 */
    private String psnNo;
    /** 险种类型 */
    private String insutype;
    /** 联系人姓名 */
    private String conerName;
    /** 联系电话 */
    private String tel;
    /** 入院时间/开始时间 yyyy-MM-dd HH:mm:ss */
    private String begntime;
    /** 就诊凭证类型 */
    private String mdtrtCertType;
    /** 就诊凭证编号 */
    private String mdtrtCertNo;
    /** 医疗类别 */
    private String medType;
    /** 住院号(院内就诊流水号) */
    private String iptNo;
    /** 病历号 */
    private String medrcdno;
    /** 主治医生编码 */
    private String atddrNo;
    /** 主诊医师姓名 */
    private String chfpdrName;
    /** 入院诊断描述 */
    private String admDiagDscr;
    /** 入院科室编码 */
    private String admDeptCodg;
    /** 入院科室名称 */
    private String admDeptName;
    /** 入院床位 */
    private String admBed;
    /** 住院主诊断代码 */
    private String dscgMaindiagCode;
    /** 住院主诊断名称 */
    private String dscgMaindiagName;
    /** 主要病情描述 */
    private String mainCondDscr;
    /** 病种编码 */
    private String diseCodg;
    /** 病种名称 */
    private String diseName;
    /** 手术操作代码 */
    private String oprnOprtCode;
    /** 手术操作名称 */
    private String oprnOprtName;
    /** 病种类型 */
    private String diseTypeCode;
    /** 参保地医保区划 */
    private String insuplcAdmdvs;
    /** 就医地医保区划 */
    private String mdtrtareaAdmvs;
    /** 字段扩展 */
    private String expContent;
}
