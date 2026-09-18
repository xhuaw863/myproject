package com.yb.hi.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日期时间工具类
 * 医保接口时间格式: yyyy-MM-dd HH:mm:ss
 * 报文ID时间格式: yyyyMMddHHmmss
 */
public class DateUtil {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter COMPACT_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static String currentDateTime() {
        return LocalDateTime.now().format(DT_FORMAT);
    }

    public static String currentTimeCompact() {
        return LocalDateTime.now().format(COMPACT_FORMAT);
    }

    public static String currentDate() {
        return LocalDateTime.now().format(DATE_FORMAT);
    }

    public static String format(LocalDateTime dateTime) {
        return dateTime.format(DT_FORMAT);
    }
}
