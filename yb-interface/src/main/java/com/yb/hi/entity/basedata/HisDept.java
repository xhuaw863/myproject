package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 科室
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_dept")
public class HisDept extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 科室编码(院内) */
    private String deptCode;
    /** 科室名称 */
    private String deptName;
    /** 科室类型: 临床/医技/行政 */
    private String deptType;
    /** 医保科别(用于2201) */
    private String deptCaty;
    /** 医保科室编码 */
    private String ybDeptCode;
    /** 联系电话 */
    private String phone;
    /** 位置描述 */
    private String locDesc;
    /** 排序号 */
    private Integer sortNo;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
    /** 备注 */
    private String memo;
}
