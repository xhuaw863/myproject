package com.yb.hi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "yb")
public class YbConfig {
    private String apiUrl;
    private String fileDownloadUrl;
    private String fixmedinsCode;
    private String fixmedinsName;
    private String mdtrtareaAdmvs;
    private String recerSysCode;
    private String infver;
    private String opterType;
    private String opter;
    private String opterName;
    private String signNo;
    private String sm2PrivateKey;
    private String sm2PublicKey;
    private String encType;
    private String dictFilePath;
    /** 模拟医保平台模式: true-本地模拟响应(不调用真实平台), false-调用真实平台 */
    private boolean mockEnabled = true;
    private int connectTimeout = 30000;
    private int readTimeout = 60000;
}
