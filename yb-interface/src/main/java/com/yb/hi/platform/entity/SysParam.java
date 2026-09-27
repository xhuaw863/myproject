package com.yb.hi.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 系统参数(支持 全局/租户/机构/科室 多作用域)
 * tenant_id 为显式列并显式映射(与 SysTenant 类似): 全局行 tenant_id=0 需跨租户可见,
 * 故 sys_param 已入 MybatisPlusConfig.IGNORE_TABLES, 租户隔离由 Service 层在 Wrapper 中手动处理。
 * 唯一键 uk_param_scope(param_key, scope_level, scope_id) 保证同一键同一作用域仅一行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_param")
public class SysParam extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户ID(0=全局行, 跨租户可见; 显式映射, 不走 MP 租户插件) */
    private Long tenantId;
    /** 参数键 */
    private String paramKey;
    /** 参数值(按 dataType 解析) */
    private String paramValue;
    /** 作用域层级: 0全局 1租户 2机构 3科室 */
    private Integer scopeLevel;
    /** 作用域对象ID(配合 scopeLevel, 0=无) */
    private Long scopeId;
    /** 分组编码(sys_param_group.group_code) */
    private String groupCode;
    /** 参数名称 */
    private String paramName;
    /** 数据类型: string/int/decimal/bool/enum */
    private String dataType;
    /** 默认值 */
    private String defaultValue;
    /** 枚举选项(逗号分隔, dataType=enum 时有效) */
    private String enumOptions;
    /** 最小值约束(数值型) */
    private BigDecimal minValue;
    /** 最大值约束(数值型) */
    private BigDecimal maxValue;
    /** 是否必填: 1是 0否 */
    private Integer required;
    /** 允许的作用域层级列表(逗号分隔, 默认 0,1,2,3) */
    private String allowScope;
    /** 备注 */
    private String remark;
}
