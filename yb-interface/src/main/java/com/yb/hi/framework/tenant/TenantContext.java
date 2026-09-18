package com.yb.hi.framework.tenant;

/**
 * 租户上下文(ThreadLocal)
 * 保存当前请求所属医院(租户)ID, 供 MyBatis-Plus 租户插件与业务层使用。
 */
public class TenantContext {

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();

    public static void set(Long tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static Long get() {
        return TENANT_ID.get();
    }

    /** 获取租户ID, 为空时抛异常(用于必须租户上下文的场景) */
    public static Long require() {
        Long id = TENANT_ID.get();
        if (id == null) {
            throw new IllegalStateException("租户上下文未初始化");
        }
        return id;
    }

    public static void clear() {
        TENANT_ID.remove();
    }
}
