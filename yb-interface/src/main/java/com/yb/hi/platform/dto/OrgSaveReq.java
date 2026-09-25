package com.yb.hi.platform.dto;

import lombok.Data;

/**
 * 机构新增/修改请求
 */
@Data
public class OrgSaveReq {
    private Long id;
    /** 机构编码(医共体内唯一) */
    private String orgCode;
    /** 机构名称 */
    private String orgName;
    /** 机构级别: 1-县级 2-乡镇 3-村(牵头与否见 isLead) */
    private Integer orgLevel;
    /** 是否牵头机构: 1-牵头 0-成员(null=不变更); 每医共体唯一, 服务端校验 */
    private Integer isLead;
    /** 上级机构ID(县级为0) */
    private Long parentId;
    /** 机构类型 */
    private String orgType;
    /** 定点医药机构编号(机构级) */
    private String fixmedinsCode;
    /** 定点医药机构名称(医保登记名) */
    private String fixmedinsName;
    /** 统一社会信用代码 */
    private String uscc;
    /** 定点医疗服务机构类型编码(cv_code:fixmedins_type) */
    private String fixmedinsType;
    /** 医院等级编码(cv_code:hosp_lv) */
    private String hospLv;
    /** 医疗机构执业许可证号 */
    private String pdLicenseNo;
    /** 编制床位数 */
    private Integer bedCnt;
    /** 收费价格档次: 1/2/3(医共体分级价格执行档) */
    private Integer priceLv;
    /** 行政区划代码 */
    private String admvsCode;
    /** 负责人 */
    private String leader;
    /** 联系电话 */
    private String phone;
    /** 机构地址 */
    private String address;
    /* ===== 机构级医保接口配置(空=继承租户/全局) ===== */
    private String mdtrtareaAdmvs;
    private String insuplcAdmdvs;
    private String apiUrl;
    private String fileDownloadUrl;
    private String recerSysCode;
    private String infver;
    private String opterType;
    private String opter;
    private String opterName;
    private String signNo;
    private String sm2PrivateKey;
    private String sm2PublicKey;
    private String encType;
    /** 模拟平台模式: 1-模拟 0-真实(空=继承) */
    private Integer mockEnabled;
    /** 排序号 */
    private Integer sortNo;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
}
