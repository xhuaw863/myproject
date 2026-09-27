package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统参数分组(全局共享, 无 tenant_id 列, 已入 IGNORE_TABLES)
 * group_code 唯一, 参数通过 sys_param.group_code 关联到分组。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_param_group")
public class SysParamGroup extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分组编码(唯一) */
    private String groupCode;
    /** 分组名称 */
    private String groupName;
    /** 排序号 */
    private Integer sortNo;
    /** 备注 */
    private String remark;
}
