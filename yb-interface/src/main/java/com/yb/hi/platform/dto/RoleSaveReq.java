package com.yb.hi.platform.dto;

import lombok.Data;

/**
 * 角色新增/修改请求(仅租户自定义角色可增改)
 */
@Data
public class RoleSaveReq {
    private Long id;
    /** 角色编码 */
    private String roleCode;
    /** 角色名称 */
    private String roleName;
    /** 备注 */
    private String remark;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
}
