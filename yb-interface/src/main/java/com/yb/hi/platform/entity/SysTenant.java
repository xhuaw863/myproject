package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 租户(医院) + 医保接口配置
 * 全局表, 不做租户隔离
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 医院登录码(唯一) */
    private String tenantCode;
    /** 医院名称 */
    private String tenantName;

    // ===== 医保接口配置 =====
    private String fixmedinsCode;
    private String fixmedinsName;
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
    /** 模拟平台模式: 1-模拟 0-真实 */
    private Integer mockEnabled;

    // ===== 租户状态 =====
    /** 状态: 1-启用 0-停用 */
    private Integer status;
    private LocalDateTime expireTime;
    private String contact;
    private String phone;
    private String address;
}
