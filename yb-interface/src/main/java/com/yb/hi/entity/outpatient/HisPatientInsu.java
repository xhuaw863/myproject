package com.yb.hi.entity.outpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 患者医保参保信息(档案子表)。
 * 医保读卡(【1101】人员基本信息获取)返回的参保信息列表(输出节点 insuinfo)完整保存; 一人可同时/历史拥有多条参保记录
 * (如职工+居民、参保+停保), 故每条记录独立一行, 以 patient_id 关联 his_patient。
 * 编码字段一律"存编码 + 服务端回填名称与来源标识(三件套 code/name/src)"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_patient_insu")
public class HisPatientInsu extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 患者ID(his_patient.id) */
    private Long patientId;
    /* ---------- 字段与【1101】人员基本信息获取输出节点 insuinfo(参保信息列表, 多行)一一对应; psn_no 取自 baseinfo ---------- */
    /** 人员编号(1101 baseinfo.psn_no, 随参保记录保存便于展示) */
    private String psnNo;
    /** 余额(insuinfo.balc, 16,2) */
    private BigDecimal balc;
    /** 险种类型编码(insuinfo.insutype, cv_code:insutype) */
    private String insutype;
    /** 险种名称(服务端回填) */
    private String insutypeName;
    /** 险种来源标识(cv_code:insutype) */
    private String insutypeSrc;
    /** 人员类别编码(insuinfo.psn_type, cv_code:psn_type) */
    private String psnType;
    /** 人员类别名称(服务端回填) */
    private String psnTypeName;
    /** 人员类别来源标识(cv_code:psn_type) */
    private String psnTypeSrc;
    /** 人员参保状态编码(insuinfo.psn_insu_stas, cv_code:psn_insu_stas: 1-参保 2-停保) */
    private String psnInsuStas;
    /** 人员参保状态名称(服务端回填) */
    private String psnInsuStasName;
    /** 人员参保状态来源标识(cv_code:psn_insu_stas) */
    private String psnInsuStasSrc;
    /** 个人参保日期(insuinfo.psn_insu_date) */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate psnInsuDate;
    /** 暂停参保日期(insuinfo.paus_insu_date, null=当前在保) */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate pausInsuDate;
    /** 公务员标志编码(insuinfo.cvlserv_flag, cv_code:cvlserv_flag: 1-是 0-否) */
    private String cvlservFlag;
    /** 公务员标志名称(服务端回填) */
    private String cvlservFlagName;
    /** 公务员标志来源标识(cv_code:cvlserv_flag) */
    private String cvlservFlagSrc;
    /** 参保地医保区划编码(insuinfo.insuplc_admdvs, area_code_2021) */
    private String insuplcAdmdvs;
    /** 参保地医保区划名称(服务端回填) */
    private String insuplcAdmdvsName;
    /** 参保地医保区划来源标识(area_code_2021) */
    private String insuplcAdmdvsSrc;
    /** 单位名称(insuinfo.emp_name, 参保单位) */
    private String empName;
    /** 记录来源: 医保读卡(1101)/手工 */
    private String src;
}
