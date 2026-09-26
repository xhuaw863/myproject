package com.yb.hi.platform.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 机构树节点(县/乡/村三级)
 */
@Data
public class OrgNode {
    private Long id;
    private Long parentId;
    private String orgCode;
    private String orgName;
    /** 拼音简码(机构名首字母, 供前端下拉本地检索) */
    private String pyCode;
    /** 机构级别: 1-县级 2-乡镇 3-村(牵头与否见 isLead) */
    private Integer orgLevel;
    /** 是否牵头机构: 1-牵头 0-成员 */
    private Integer isLead;
    private String orgType;
    /** 机构类型名称(字典回填) */
    private String orgTypeName;
    /** 机构类型字典来源标识 */
    private String orgTypeSrc;
    private String fixmedinsCode;
    /** 定点医药机构名称(医保登记名) */
    private String fixmedinsName;
    /** 统一社会信用代码 */
    private String uscc;
    /** 定点医疗服务机构类型编码 */
    private String fixmedinsType;
    private String fixmedinsTypeName;
    private String fixmedinsTypeSrc;
    /** 医院等级编码 */
    private String hospLv;
    private String hospLvName;
    private String hospLvSrc;
    /** 医疗机构执业许可证号 */
    private String pdLicenseNo;
    /** 编制床位数 */
    private Integer bedCnt;
    /** 收费价格档次: 1/2/3(医共体分级价格执行档) */
    private Integer priceLv;
    private String admvsCode;
    private String leader;
    private String phone;
    private String address;
    /* ===== 机构级医保接口配置(供编辑回显) ===== */
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
    private String sm2PublicKey;
    private String encType;
    private Integer mockEnabled;
    private Integer sortNo;
    private Integer status;
    private List<OrgNode> children = new ArrayList<>();
}
