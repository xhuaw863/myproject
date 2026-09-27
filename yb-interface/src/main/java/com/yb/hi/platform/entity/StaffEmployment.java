package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 员工任职(his_staff × sys_org × his_dept): 多点执业/兼科室的业务事实载体。
 * 科室数据权限的权威来源; 主任职行(is_primary=1)单向回写 his_staff 的归属机构/所属科室展示列。
 * tenant_id 由多租户插件自动注入/过滤(同 sys_user_org 套路); 物理删除, 不 extends BaseEntity。
 */
@Data
@TableName("staff_employment")
public class StaffEmployment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 职工ID(his_staff) */
    private Long staffId;
    /** 机构ID(sys_org) */
    private Long orgId;
    /** 科室ID(his_dept, 须归属该机构) */
    private Long deptId;
    /** 是否主任职(行政所属): 每个 staff 至多一条 */
    private Integer isPrimary;
    /** 租户ID(多租户插件维护) */
    private Long tenantId;
}
