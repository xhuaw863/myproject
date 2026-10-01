package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 开单科室→窗口定向规则(如急诊/发热门诊固定投递到指定窗口)
 * 唯一键: tenant_id + dept_id + window_id
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_window_dept_rule")
public class HisWindowDeptRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 开单科室ID(his_dept.id) */
    private Long deptId;
    /** 定向窗口ID(his_pharmacy_window.id) */
    private Long windowId;
    /** 备注 */
    private String remark;
}
