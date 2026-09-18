package com.yb.hi.platform.dto;

import lombok.Data;

/**
 * 医院(租户)注册请求
 * 注册时同步创建该医院的管理员账号
 */
@Data
public class TenantRegisterReq {
    /** 医院登录码(唯一, 自行指定) */
    private String tenantCode;
    /** 医院名称 */
    private String tenantName;
    /** 联系人 */
    private String contact;
    /** 联系电话 */
    private String phone;
    /** 医院地址 */
    private String address;

    // 医保接口配置(可选, 未填则用默认/模拟)
    private String fixmedinsCode;
    private String fixmedinsName;
    private String mdtrtareaAdmvs;
    private String insuplcAdmdvs;
    private String apiUrl;
    /** 模拟平台模式: 1-模拟(默认) 0-真实 */
    private Integer mockEnabled;

    // 管理员账号
    private String adminUsername;
    private String adminPassword;
    private String adminName;
}
