package com.yb.hi.entity.outpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 患者档案。
 * 字段对齐《湖北省健康医疗大数据采集规范》患者基本信息数据集(BASE_PERSON_YLFW):
 * 身份人口学(证件类别/民族/国籍/婚姻/文化程度/职业) + 现住址(area_code_2021 五级级联)
 * + 户籍/工作单位/联系人地址 + 联系人与患者关系。出生日期精确到秒(新生儿需精确时间)。
 * 编码字段一律"存编码 + 服务端回填名称与来源标识(三件套 code/name/src)"。
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
    /** 性别编码(医保字典 gend: 1男 2女 0未知 9未说明) */
    private String gender;
    /** 性别名称(服务端按 gender 回填) */
    private String genderName;
    /** 性别字典来源标识(cv_code:gend) */
    private String genderSrc;
    /** 出生日期时间(精确到秒; 新生儿需精确出生时间。对应 HEAD_BIRTHDAY) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime birthDate;
    /** 年龄 */
    private Integer age;
    /** 身份证号 */
    private String idCard;
    /** 联系电话 */
    private String phone;
    /** 住址/通讯地址(HEAD_ADDRESS: 患者本人或联系人通信的指定地点) */
    private String address;

    /* ---------- A 身份人口学(采集规范 BASE_PERSON_YLFW; 三件套 code/name/src) ---------- */
    /** 身份证件类别编码(医保字典 cv_code:psn_cert_type; 对应 HEAD_IDTYPE) */
    private String certType;
    /** 身份证件类别名称(服务端回填) */
    private String certTypeName;
    /** 身份证件类别来源标识(cv_code:psn_cert_type) */
    private String certTypeSrc;
    /** 民族编码(医保字典 cv_code:naty; 对应 MZ, GB 3304-1991) */
    private String nation;
    /** 民族名称(服务端回填) */
    private String nationName;
    /** 民族来源标识(cv_code:naty) */
    private String nationSrc;
    /** 国籍编码(hbvalue:GB/T 2659.1-2022; 对应 GJ, 默认156中国) */
    private String nationality;
    /** 国籍名称(服务端回填) */
    private String nationalityName;
    /** 国籍来源标识(hbvalue:GB/T 2659.1-2022) */
    private String nationalitySrc;
    /** 婚姻状况编码(hbvalue:GB/T 2261.2-2003; 对应 HY) */
    private String maritalStatus;
    /** 婚姻状况名称(服务端回填) */
    private String maritalStatusName;
    /** 婚姻状况来源标识(hbvalue:GB/T 2261.2-2003) */
    private String maritalStatusSrc;
    /** 文化程度编码(hbvalue:GB/T 4658-2006; 对应 EDUCATIONCODE) */
    private String eduLevel;
    /** 文化程度名称(服务端回填) */
    private String eduLevelName;
    /** 文化程度来源标识(hbvalue:GB/T 4658-2006) */
    private String eduLevelSrc;
    /** 职业类别编码(hbvalue:CV02.01.202; 对应 ZYLBDM) */
    private String occupation;
    /** 职业类别名称(服务端回填) */
    private String occupationName;
    /** 职业类别来源标识(hbvalue:CV02.01.202) */
    private String occupationSrc;
    /** 职业类别其他(职业为"其他"时填写; 对应 ZYLBQT) */
    private String occupationOther;

    /* ---------- B 现住址(area_code_2021 五级级联: 存编码+名称, 来源统一 area_code_2021) ---------- */
    /** 现住址-省编码(PRESENTADDRPROVINCE) */
    private String presentProv;
    /** 现住址-省名称(服务端回填) */
    private String presentProvName;
    /** 现住址-市编码(PRESENTADDRCITY) */
    private String presentCity;
    /** 现住址-市名称(服务端回填) */
    private String presentCityName;
    /** 现住址-区县编码(PRESENTADDRCOUNTY) */
    private String presentCounty;
    /** 现住址-区县名称(服务端回填) */
    private String presentCountyName;
    /** 现住址-乡镇/街道编码(XZZXZ) */
    private String presentTown;
    /** 现住址-乡镇/街道名称(服务端回填) */
    private String presentTownName;
    /** 现住址来源标识(area_code_2021) */
    private String presentSrc;
    /** 现住址-详细地址(村/街/路/门牌, XZZC+XZZMP) */
    private String presentDetail;
    /** 户籍地址(HKDZ+HKXXDZ, 文本) */
    private String householdAddr;
    /** 工作单位名称(GZDWMC) */
    private String employer;
    /** 工作单位电话(WORKADDRPHONE) */
    private String employerPhone;
    /** 工作单位地址(GZDWJDZ, 文本; 作为单位地址详细) */
    private String employerAddr;

    /* ---------- B2 出生地/户籍/通讯/单位/联系人 地址四级级联(编码+名称, 来源 area_code_2021; 详细沿用原文本列) ---------- */
    /** 出生地-省编码 */
    private String birthProv;
    /** 出生地-省名称(服务端回填) */
    private String birthProvName;
    /** 出生地-市编码 */
    private String birthCity;
    /** 出生地-市名称(服务端回填) */
    private String birthCityName;
    /** 出生地-区县编码 */
    private String birthCounty;
    /** 出生地-区县名称(服务端回填) */
    private String birthCountyName;
    /** 出生地-乡镇/街道编码 */
    private String birthTown;
    /** 出生地-乡镇/街道名称(服务端回填) */
    private String birthTownName;
    /** 出生地来源标识(area_code_2021) */
    private String birthSrc;
    /** 出生地-详细地址 */
    private String birthDetail;
    /** 户籍地址-省编码 */
    private String householdProv;
    /** 户籍地址-省名称(服务端回填) */
    private String householdProvName;
    /** 户籍地址-市编码 */
    private String householdCity;
    /** 户籍地址-市名称(服务端回填) */
    private String householdCityName;
    /** 户籍地址-区县编码 */
    private String householdCounty;
    /** 户籍地址-区县名称(服务端回填) */
    private String householdCountyName;
    /** 户籍地址-乡镇/街道编码 */
    private String householdTown;
    /** 户籍地址-乡镇/街道名称(服务端回填) */
    private String householdTownName;
    /** 户籍地址来源标识(area_code_2021) */
    private String householdSrc;
    /** 通讯地址-省编码 */
    private String mailProv;
    /** 通讯地址-省名称(服务端回填) */
    private String mailProvName;
    /** 通讯地址-市编码 */
    private String mailCity;
    /** 通讯地址-市名称(服务端回填) */
    private String mailCityName;
    /** 通讯地址-区县编码 */
    private String mailCounty;
    /** 通讯地址-区县名称(服务端回填) */
    private String mailCountyName;
    /** 通讯地址-乡镇/街道编码 */
    private String mailTown;
    /** 通讯地址-乡镇/街道名称(服务端回填) */
    private String mailTownName;
    /** 通讯地址来源标识(area_code_2021) */
    private String mailSrc;
    /** 单位地址-省编码 */
    private String empProv;
    /** 单位地址-省名称(服务端回填) */
    private String empProvName;
    /** 单位地址-市编码 */
    private String empCity;
    /** 单位地址-市名称(服务端回填) */
    private String empCityName;
    /** 单位地址-区县编码 */
    private String empCounty;
    /** 单位地址-区县名称(服务端回填) */
    private String empCountyName;
    /** 单位地址-乡镇/街道编码 */
    private String empTown;
    /** 单位地址-乡镇/街道名称(服务端回填) */
    private String empTownName;
    /** 单位地址来源标识(area_code_2021) */
    private String empSrc;
    /** 联系人地址-省编码 */
    private String contactProv;
    /** 联系人地址-省名称(服务端回填) */
    private String contactProvName;
    /** 联系人地址-市编码 */
    private String contactCity;
    /** 联系人地址-市名称(服务端回填) */
    private String contactCityName;
    /** 联系人地址-区县编码 */
    private String contactCounty;
    /** 联系人地址-区县名称(服务端回填) */
    private String contactCountyName;
    /** 联系人地址-乡镇/街道编码 */
    private String contactTown;
    /** 联系人地址-乡镇/街道名称(服务端回填) */
    private String contactTownName;
    /** 联系人地址来源标识(area_code_2021) */
    private String contactSrc;

    /** 险种类型编码(医保字典 insutype: 310职工 390居民等) */
    private String insutype;
    /** 险种名称(服务端回填) */
    private String insutypeName;
    /** 险种字典来源标识(cv_code:insutype) */
    private String insutypeSrc;
    /** 就诊凭证类型编码(医保字典 mdtrt_cert_type: 1电子凭证 2身份证 3社保卡) */
    private String mdtrtCertType;
    /** 就诊凭证类型名称(服务端回填) */
    private String mdtrtCertTypeName;
    /** 就诊凭证类型字典来源标识(cv_code:mdtrt_cert_type) */
    private String mdtrtCertTypeSrc;
    /** 就诊凭证编号 */
    private String mdtrtCertNo;
    /** 参保地区划编码(area_code_2021) */
    private String insuplcAdmdvs;
    /** 参保地区划名称(服务端回填) */
    private String insuplcAdmdvsName;
    /** 参保地区划来源标识(area_code_2021) */
    private String insuplcAdmdvsSrc;
    /* ---------- C 联系人与患者关系(三件套 code/name/src) ---------- */
    /** 联系人与患者关系编码(hbvalue:GB/T 4761-2008; 对应 LXRYHZGX) */
    private String contactRelation;
    /** 联系人与患者关系名称(服务端回填) */
    private String contactRelationName;
    /** 联系人与患者关系来源标识(hbvalue:GB/T 4761-2008) */
    private String contactRelationSrc;
    /** 联系人姓名(LXRXM) */
    private String contactName;
    /** 联系人电话(LXRDH) */
    private String contactPhone;
    /** 联系人身份证件号码(LXRSFZJHM) */
    private String contactIdCard;
    /** 联系人地址(LXRDZ, 文本) */
    private String contactAddr;
    /** 状态: 1-正常 0-停用 */
    private Integer status;
    /** 建档/首诊机构ID(归属标注, 医共体内跨机构共享同一档案) */
    private Long orgId;
    /** 备注 */
    private String memo;
}
