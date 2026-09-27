package com.yb.hi.framework.tenant;

import lombok.Data;

import java.util.List;

/**
 * 登录用户信息(存于 UserContext 与 JWT 载荷)
 */
@Data
public class LoginUser {

    /** 用户ID */
    private Long userId;
    /** 租户ID */
    private Long tenantId;
    /** 登录账号 */
    private String username;
    /** 姓名 */
    private String realName;
    /** 主角色编码(显示与旧令牌兼容; 判权请用 {@link #hasRole}) */
    private String role;
    /** 关联职工ID */
    private Long staffId;
    /** 关联科室ID */
    private Long deptId;
    /** 归属机构ID */
    private Long orgId;
    /** 主角色ID(sys_role, 显示与旧令牌兼容) */
    private Long roleId;
    /** 全部角色编码(医共体一人多角色, 含主角色; 旧令牌可为 null) */
    private List<String> roles;
    /** 全部角色ID(sys_role, 多角色并集菜单解析用) */
    private List<Long> roleIds;
    /** 是否牵头机构(org_level=1): 牵头可维护全医共体基础数据, 非牵头只读 */
    private Boolean leadOrg;
    /** 医院名称 */
    private String tenantName;

    /** 任一角色命中即视为拥有该角色; roles 集合缺失时回落主角色(兼容改造前签发的旧令牌) */
    public boolean hasRole(String code) {
        if (code == null) {
            return false;
        }
        if (roles != null && !roles.isEmpty()) {
            return roles.contains(code);
        }
        return code.equals(role);
    }

    /** 拥有其中任一角色即通过(守卫档位: 如 ADMIN/SUPER_ADMIN 均可) */
    public boolean hasAnyRole(String... codes) {
        if (codes == null) {
            return false;
        }
        for (String c : codes) {
            if (hasRole(c)) {
                return true;
            }
        }
        return false;
    }
}
