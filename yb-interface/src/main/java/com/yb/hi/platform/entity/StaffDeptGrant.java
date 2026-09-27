package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 科室临调授权(staff × org × dept + 期限): 任职事实之外的代理授权层,
 * 覆盖替班/临时调配/岗位兼权等不宜落"任职"实体的场景。
 * 科室授权范围 = staff_employment ∪ 本表有效期内的行 (管理角色豁免另计)。
 * tenant_id 由多租户插件维护; 物理删除; sys_user.dept_scope 存量迁移至此(org=用户归属机构)。
 */
@Data
@TableName("staff_dept_grant")
public class StaffDeptGrant {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 职工ID(his_staff) */
    private Long staffId;
    /** 机构ID(sys_org): 授权仅在登录该机构时生效 */
    private Long orgId;
    /** 科室ID(his_dept) */
    private Long deptId;
    /** 生效日(含; null=立即) */
    private LocalDate validFrom;
    /** 失效日(含; null=长期) */
    private LocalDate validTo;
    /** 授权来源(管理员账号名/初始化标记) */
    private String grantBy;
    /** 备注(替班原因等) */
    private String remark;
    /** 租户ID(多租户插件维护) */
    private Long tenantId;
}
