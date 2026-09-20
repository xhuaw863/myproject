package com.yb.hi.platform.dto;

import lombok.Data;

/**
 * 用户新增/修改请求
 */
@Data
public class UserSaveReq {
    private Long id;
    private String username;
    /** 明文密码(新增必填, 修改忽略) */
    private String password;
    private String realName;
    private String role;
    private Long staffId;
    private Long deptId;
    /** 授权科室范围(逗号分隔 dept_id, 空=仅主属科室) */
    private String deptScope;
    /** 归属机构ID */
    private Long orgId;
    /** 权威角色ID(sys_role) */
    private Long roleId;
    private String phone;
    private Integer status;
}
