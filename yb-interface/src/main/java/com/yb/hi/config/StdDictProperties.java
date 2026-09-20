package com.yb.hi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 标准字典导入配置(对应 application.yml 中 std-dict.*)
 * 标准字典 = 湖北省医保编码数据库(主) + 字典标准文件夹中的国家临床版/中医分类等。
 */
@Data
@Component
@ConfigurationProperties(prefix = "std-dict")
public class StdDictProperties {

    /** 标准字典源文件根目录(字典标准文件夹绝对路径) */
    private String basePath = "D:/study/ybtest/字典标准";

    /** 是否启用标准字典导入/查询接口 */
    private boolean enabled = true;

    /** 启动时自动全量导入(默认关闭, 通过接口按需触发) */
    private boolean autoImportOnStartup = false;

    /** 批量提交大小 */
    private int batchSize = 1000;
}
