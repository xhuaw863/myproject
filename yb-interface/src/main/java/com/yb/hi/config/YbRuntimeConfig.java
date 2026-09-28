package com.yb.hi.config;

import lombok.Data;

/**
 * 医保接口运行时配置(按当前租户解析后的最终生效值)
 * 由 TenantYbConfigResolver 合并"全局默认(YbConfig) + 租户配置(sys_tenant)"得到。
 */
@Data
public class YbRuntimeConfig {
    private String apiUrl;
    private String fileDownloadUrl;
    private String fixmedinsCode;
    private String fixmedinsName;
    private String mdtrtareaAdmvs;
    /** 参保地医保区划(规范表3: 交易输入含人员编号时必填; 机构/租户配置的默认参保地, 患者级以 HisPatientInsu 为准) */
    private String insuplcAdmdvs;
    private String recerSysCode;
    private String infver;
    private String opterType;
    private String opter;
    private String opterName;
    private String signNo;
    private String sm2PrivateKey;
    private String sm2PublicKey;
    private String encType;
    private boolean mockEnabled = true;
}
