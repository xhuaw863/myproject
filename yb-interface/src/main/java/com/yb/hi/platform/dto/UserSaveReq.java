package com.yb.hi.platform.dto;

import lombok.Data;

import java.util.List;

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
    /** 主角色ID(sys_role, 显示与旧数据兜底; 兼容旧前端单角色传参) */
    private Long roleId;
    /** 全部角色ID(医共体一人多角色, 主角色置首); null=不改动角色关联(旧前端兼容) */
    private List<Long> roleIds;
    private String phone;
    private Integer status;
    /** 可登录机构ID列表(多点执业); 归属机构 orgId 总是保留, null=不改动 */
    private List<Long> loginOrgIds;
}
