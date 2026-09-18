package com.yb.hi.entity.outpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 患者档案
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_patient")
public class HisPatient extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 院内患者号(就诊卡号) */
    private String patientNo;
    /** 医保人员编号 */
    private String psnNo;
    /** 姓名 */
    private String name;
    /** 性别: 男/女 */
    private String gender;
    /** 出生日期 */
    private LocalDate birthDate;
    /** 年龄 */
    private Integer age;
    /** 身份证号 */
    private String idCard;
    /** 联系电话 */
    private String phone;
    /** 住址 */
    private String address;
    /** 险种类型: 310-职工 390-居民 */
    private String insutype;
    /** 就诊凭证类型: 01-电子凭证 02-身份证 03-社保卡 */
    private String mdtrtCertType;
    /** 就诊凭证编号 */
    private String mdtrtCertNo;
    /** 参保地区划 */
    private String insuplcAdmdvs;
    /** 联系人 */
    private String contactName;
    /** 联系人电话 */
    private String contactPhone;
    /** 状态: 1-正常 0-停用 */
    private Integer status;
    /** 备注 */
    private String memo;
}
