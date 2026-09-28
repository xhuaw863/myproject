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
    /** 定点医药机构编号(表3: 14位) */
    private String fixmedinsCode;
    private String fixmedinsName;
    /** 就医地医保区划(表3) */
    private String mdtrtareaAdmvs;
    private String recerSysCode;
    private String infver;
    /** 经办人类别(表3, 代码域: 1-经办人 2-自助终端 3-移动终端) */
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
