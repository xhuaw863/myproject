package com.yb.hi.platform.dto;

import lombok.Data;

import java.util.List;

/**
 * 登录响应
 */
@Data
public class LoginResp {
    private String token;
    private Long userId;
    private Long tenantId;
    private String tenantCode;
    private String tenantName;
    private String username;
    private String realName;
    private String role;
    private Long staffId;
    private Long deptId;
    /** 归属机构ID */
    private Long orgId;
    /** 权威角色ID */
    private Long roleId;
    /** 归属机构名称(前端显示) */
    private String orgName;
    /** 是否牵头机构(org_level=1): 前端据此渲染基础数据只读态 */
    private Boolean leadOrg;
    /** 角色名称(支持租户自定义角色名, 前端显示; 多角色以 " / " 连接) */
    private String roleName;
    /** 全部角色编码(医共体一人多角色, 主角色置首) */
    private List<String> roles;
    /** 归属机构ID(默认可登录机构) */
    private Long homeOrgId;
    /** 可登录机构列表(多点执业, 含归属机构); 前端据此渲染"切换机构" */
    private List<OrgOption> allowedOrgs;
}
