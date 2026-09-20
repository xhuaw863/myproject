package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 职工(医师/护士/药师/技师)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_staff")
public class HisStaff extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 职工工号(院内) */
    private String staffNo;
    /** 姓名 */
    private String staffName;
    /** 职工类别: 医师/护士/药师/技师/管理(无国标字典, 本地受控枚举) */
    private String staffType;
    /** 职工类别名称(服务端回填) */
    private String staffTypeName;
    /** 职工类别来源标识(local:staff_type) */
    private String staffTypeSrc;
    /** 性别编码(医保字典 gend: 1男 2女) */
    private String gender;
    /** 性别名称(服务端回填) */
    private String genderName;
    /** 性别字典来源标识(cv_code:gend) */
    private String genderSrc;
    /** 职称编码(卫生健康标准 CV08.30.005 专业技术职务类别: 1正高 2副高 3中级 4师级/助理 5士级) */
    private String titleCode;
    /** 职称名称(服务端按 titleCode 回填) */
    private String titleName;
    /** 职称字典来源标识(wst364:CV08.30.005) */
    private String titleSrc;
    /** 所属科室ID */
    private Long deptId;
    /** 主治医师医保编码(2201/2203) */
    private String atddrNo;
    /** 诊断医师医保编码 */
    private String diseDorNo;
    /** 身份证号 */
    private String idCard;
    /** 出生日期(可由身份证推导, 显式存储便于校验/统计) */
    private LocalDate birthDate;
    /** 国家医保业务编码(医师/药师/护士, 全国统一; 区别于院内 atddrNo) */
    private String medInsurCode;
    /** 执业类别编码(whvalue:CT98.00.024: 1临床/2口腔/3公共卫生/4中医) */
    private String pracCate;
    /** 执业类别名称(服务端回填) */
    private String pracCateName;
    /** 执业类别字典来源标识(whvalue:CT98.00.024) */
    private String pracCateSrc;
    /** 医师资格证号(执业医师法) */
    private String drQualCertNo;
    /** 医师执业证书编码(执业注册) */
    private String pracCertNo;
    /** 联系电话 */
    private String phone;
    /** 是否可挂号: 1-是 0-否 */
    private Integer canRegister;
    /** 默认挂号费(诊查费) */
    private BigDecimal regFee;
    /** 排序号 */
    private Integer sortNo;
    /** 状态: 1-在职 0-停用 */
    private Integer status;
    /** 归属机构ID(归属标注, 医共体内基础字典共享) */
    private Long orgId;
    /** 头像图片URL(本地上传, /uploads/...) */
    private String avatarUrl;
    /** 签名图片URL(用于电子处方/医学电子文档数字签名展示) */
    private String signImgUrl;

    /* ================= 医师处方权限(药事管理强管控项) =================
     * 依据《处方管理办法》《麻醉药品和精神药品管理条例》《抗菌药物临床应用管理办法》:
     * 处方权需授权留痕、专项药(精麻)单独授权、抗菌药物分级授权、权限定期复训失效。
     */
    /** 处方权(总): 1-具备处方资格 0-无(仅医师适用; 执业注册后经医务部门授权) */
    private Integer rxRight;
    /** 麻醉药品处方权: 1-有 0-无(需专项培训考核合格) */
    private Integer narcoticRight;
    /** 第一类精神药品处方权: 1-有 0-无(常与麻醉药品合并为"麻精一"授权) */
    private Integer psych1Right;
    /** 第二类精神药品处方权: 1-有 0-无 */
    private Integer psych2Right;
    /** 抗菌药物处方权级别编码(hbvalue:HBCV08.50.029: 11一级/非限制 12二级/限制 13三级/特殊使用) */
    private String antibioticLevel;
    /** 抗菌药物处方权级别名称(服务端回填) */
    private String antibioticLevelName;
    /** 抗菌药物处方权级别来源标识(hbvalue:HBCV08.50.029) */
    private String antibioticLevelSrc;
    /** 手术级别权限编码(cv_code:oprn_lv_code: 1一级/2二级/3三级/4四级/5其他; 可主刀的最高手术级别) */
    private String surgeryLevel;
    /** 手术级别权限名称(服务端回填) */
    private String surgeryLevelName;
    /** 手术级别权限来源标识(cv_code:oprn_lv_code) */
    private String surgeryLevelSrc;
    /** 处方权授权机构(医务科/授权部门) */
    private String rxAuthOrg;
    /** 处方权授权文号(授权文件编号, 便于追溯) */
    private String rxAuthNo;
    /** 处方权授权日期 */
    private LocalDate rxAuthDate;
    /** 处方权有效期至(精麻权限需定期复训考核, 到期失效) */
    private LocalDate rxValidUntil;

    /** 备注 */
    private String memo;
}
