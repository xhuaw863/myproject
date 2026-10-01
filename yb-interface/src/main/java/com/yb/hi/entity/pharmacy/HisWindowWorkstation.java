package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 发药工作站↔窗口关联(登录用户/职工绑定到具体发药窗口)
 * 唯一键: tenant_id + window_id + user_id
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_window_workstation")
public class HisWindowWorkstation extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 窗口ID(his_pharmacy_window.id) */
    private Long windowId;
    /** 关联登录用户ID(sys_user.id) */
    private Long userId;
    /** 关联职工ID(his_staff.id) */
    private Long staffId;
    /** 备注 */
    private String remark;
}
