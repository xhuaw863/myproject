package com.yb.hi.framework.tenant;

/**
 * 登录用户上下文(ThreadLocal)
 */
public class UserContext {

    private static final ThreadLocal<LoginUser> USER = new ThreadLocal<>();

    public static void set(LoginUser user) {
        USER.set(user);
    }

    public static LoginUser get() {
        return USER.get();
    }

    public static Long userId() {
        LoginUser u = USER.get();
        return u == null ? null : u.getUserId();
    }

    public static String username() {
        LoginUser u = USER.get();
        return u == null ? null : u.getUsername();
    }

    public static String role() {
        LoginUser u = USER.get();
        return u == null ? null : u.getRole();
    }

    public static void clear() {
        USER.remove();
    }
}
