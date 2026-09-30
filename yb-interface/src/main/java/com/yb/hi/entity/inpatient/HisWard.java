package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 住院病区(床位管理的组织单元)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_ward")
public class HisWard extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 病区名称 */
    private String wardName;
    /** 病区编码 */
    private String wardCode;
    /** 关联科室ID(his_dept.id) */
    private Long deptId;
    /** 楼栋 */
    private String building;
    /** 楼层 */
    private String floor;
    /** 床位数 */
    private Integer bedCount;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String remark;
}
