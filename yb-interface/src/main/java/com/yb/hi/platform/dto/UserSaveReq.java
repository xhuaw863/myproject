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
    private String phone;
    private Integer status;
}
