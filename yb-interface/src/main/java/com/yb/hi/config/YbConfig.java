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
    /** 结算经办机构(3201/3202 setl_optins, 表: 6位; 与平台确认后配置) */
    private String setlOptins;
    /** 清算类别(3201/3202 clr_type, 6位; 与平台确认后配置) */
    private String clrType;
    /** 退费结算标志-3201(6位, 规范表196; 与平台确认后配置) */
    private String refdSetlFlag3201;
    /** 退费结算标志-3202(3位, 规范表198; 注意与 3201 长度不同) */
    private String refdSetlFlag3202;
    /** 对账任务 cron(默认每日 07:00 对 T-1; 避开平台结算高峰期) */
    private String reconCron = "0 0 7 * * *";
    /** 目录对照上传(3301/3302)list_type 目录类别(规范第6章值域, 与平台确认后按目录类型配置; 未配置时对应目录类型拒绝上传) */
    private String listTypeDrug;
    /** 3301/3302 list_type: 耗材目录类别 */
    private String listTypeCons;
    /** 3301/3302 list_type: 医疗服务项目目录类别 */
    private String listTypeCharge;
}
