package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

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
    /** 角色 */
    private String role;
    /** 关联职工ID */
    private Long staffId;
    /** 关联科室ID */
    private Long deptId;
    /** 手机号 */
    private String phone;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
}
