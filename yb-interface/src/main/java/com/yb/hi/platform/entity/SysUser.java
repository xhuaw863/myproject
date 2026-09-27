package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 用户(职工登录账号), 按租户隔离
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号 */
    private String username;
    /** 密码(BCrypt散列) */
    private String password;
    /** 姓名 */
    private String realName;
    /** 角色(角色编码字符串, 兼容JWT/显示) */
    private String role;
    /** 关联职工ID */
    private Long staffId;
    /** 关联科室ID */
    private Long deptId;
    /** 授权科室范围(数据权限): 逗号分隔 his_dept.id; 空=仅主属科室 dept_id */
    private String deptScope;
    /** 归属机构ID(sys_org) */
    private Long orgId;
    /** 主角色ID(sys_role, 显示与无关联行时兜底; 判权走 sys_user_role 并集) */
    private Long roleId;
    /** 手机号 */
    private String phone;
    /** 状态: 1-启用 0-停用 */
    private Integer status;

    /** 可登录机构ID列表(多点执业, 含归属机构默认; 非DB列, 仅传输/回显) */
    @TableField(exist = false)
    private List<Long> loginOrgIds;

    /** 全部角色ID(sys_user_role 关联口径, 主角色置首; 非DB列, 仅列表回显) */
    @TableField(exist = false)
    private List<Long> roleIds;
}
