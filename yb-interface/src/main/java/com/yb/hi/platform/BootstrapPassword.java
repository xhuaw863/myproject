package com.yb.hi.platform;

/**
 * 平台引导账号口令解析(B6):
 * 种子账号(平台超管/演示医院管理员)口令优先取环境变量 HIS_BOOTSTRAP_PASSWORD,
 * 未注入时回落内置演示口令(仅限开发/演示环境; 生产部署必须注入独立口令)。
 */
public final class BootstrapPassword {

    /** 内置演示口令(仅限开发/演示) */
    public static final String DEFAULT = "admin123";

    /** 生产注入口令的环境变量名 */
    public static final String ENV_NAME = "HIS_BOOTSTRAP_PASSWORD";

    private BootstrapPassword() {
    }

    /** 解析种子口令: 环境变量优先, 未注入回落内置演示口令 */
    public static String resolve() {
        String p = System.getenv(ENV_NAME);
        return (p == null || p.trim().isEmpty()) ? DEFAULT : p.trim();
    }

    /** 是否回落了内置演示口令(供初始化日志强提醒, 不在日志中打印口令本身) */
    public static boolean isDefault(String pwd) {
        return DEFAULT.equals(pwd);
    }
}
