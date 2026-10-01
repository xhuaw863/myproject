package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 手术费用模板(个人/科室/全院三级, 术后费用录入一键导入)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_fee_tpl")
public class HisSurgeryFeeTpl extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板名称 */
    private String tplName;
    /** 级别: 1个人 2科室 3全院 */
    private Integer tplLevel;
    /** 归属职工ID(个人级) */
    private Long ownerStaffId;
    /** 归属科室ID(科室级) */
    private Long deptId;
    /** 关联手术编码(可空=通用模板) */
    private String surgeryCode;
    /** 关联手术名称 */
    private String surgeryName;
    /** 备注 */
    private String remark;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
